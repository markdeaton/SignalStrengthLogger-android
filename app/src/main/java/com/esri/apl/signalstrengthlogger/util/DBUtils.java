package com.esri.apl.signalstrengthlogger.util;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.ConnectivityManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.WorkerThread;

import com.esri.apl.signalstrengthlogger.R;
import com.esri.apl.signalstrengthlogger.data.TokenInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Utilities for working with a local SQLite database to cache location points */
public class DBUtils {
  private static final String TAG = "DBUtils";

  // Methods that help create the JSON for adding a feature to the REST based Feature Service
  private static String formatParamForJSON(int param) {
    if (param == Integer.MIN_VALUE) return "null";
    else return String.format("%d", param);
  }
  private static String formatParamForJSON(long param) {
    if (param == Long.MIN_VALUE) return "null";
    else return String.format("%d", param);
  }
  private static String formatParamForJSON(String param) {
    if (TextUtils.isEmpty(param)) return "null";
    else return param;
  }
  private static String formatParamForJSON(double param) {
    if (param == Double.NaN) return "null";
    else return Double.toString(param); //String.format("%f", param);
  }
  private static String formatParamForJSON(float param) {
    if (param == Float.NaN) return "null";
    else return Float.toString(param); //String.format("%f", param);
  }
  // TODO Change this if you want to change the database schema and what's being recorded
  public static String jsonForOneFeature(Context ctx,
                                          double x, double y, double z, float signal, long dateTime,
                                          String osName, String osVersion, String phoneModel,
                                          String deviceId, String carrierId) {
    String json = ctx.getString(R.string.add_one_feature_json,
        formatParamForJSON(x),
        formatParamForJSON(y),
        formatParamForJSON(z),
        formatParamForJSON(signal),
        formatParamForJSON(dateTime),
        formatParamForJSON(osName),
        formatParamForJSON(osVersion),
        formatParamForJSON(phoneModel),
        formatParamForJSON(deviceId),
        formatParamForJSON(carrierId)
    );
    return json;
  }

  /** After features are sent to the Feature Service, they're flagged in the lcoal DB as posted
   * but left in place until the next logging session, just in case they need to be extracted.
   * In the next logging session, this method is called to purge old local records.
   * @param db The SQLite database (previously opened for read/write)
   * @param ctx The caller's context
   */
  public static void deletePreviouslyPostedRecords(SQLiteDatabase db, Context ctx) {
    String where = ctx.getString(R.string.whereclause_posted_records,
        ctx.getString(R.string.columnname_was_added_to_fc));
    String table = ctx.getString(R.string.tablename_readings);
    db.delete(table, where, null);
  }

  /** Determines how many local database records haven't yet been posted to the Feature Service.
   * @param db The SQLite database (previously opened for read/write)
   * @param ctx The caller's context
   * @return A SQLite cursor with the results of the query
   */
  public static Cursor getUnpostedRecords(SQLiteDatabase db, Context ctx) {
    String table = ctx.getString(R.string.viewname_unposted_records);
    return db.query(
        table, null, null, null,
        null, null, null);
  }

// TODO Change this if you want to change the database schema and what's being recorded
  /** Here's where locally cached records get posted up to the REST based Feature Service.
   * @param cur SQLite DB cursor to list of unposted records
   * @param ctx Service context
   * @param sharedPrefs Shared preferences
   * @return list of rowids of records that were successfully posted
   */
  @WorkerThread
  public static List<Long> postUnpostedRecords(
      Cursor cur, Context ctx, SharedPreferences sharedPrefs)
      throws IOException, JSONException {
    // Get JSON for adds
    List<String> jsonAdds = new ArrayList<>();
    while (cur.moveToNext()) {
      String jsonAdd = DBUtils.jsonForOneFeature(ctx,
          cur.getDouble(cur.getColumnIndex(ctx.getString(R.string.columnname_longitude))),
          cur.getDouble(cur.getColumnIndex(ctx.getString(R.string.columnname_latitude))),
          cur.getDouble(cur.getColumnIndex(ctx.getString(R.string.columnname_altitude))),
          cur.getFloat(cur.getColumnIndex(ctx.getString(R.string.columnname_signalstrength))),
          cur.getLong(cur.getColumnIndex(ctx.getString(R.string.columnname_date))),
          cur.getString(cur.getColumnIndex(ctx.getString(R.string.columnname_osname))),
          cur.getString(cur.getColumnIndex(ctx.getString(R.string.columnname_osversion))),
          cur.getString(cur.getColumnIndex(ctx.getString(R.string.columnname_phonemodel))),
          cur.getString(cur.getColumnIndex(ctx.getString(R.string.columnname_deviceid))),
          cur.getString(cur.getColumnIndex(ctx.getString(R.string.columnname_carrierid)))
      );
      jsonAdds.add(jsonAdd);
    }
    String sJsonAdds = "[" + TextUtils.join(",", jsonAdds.toArray(new String[]{})) + "]";

    // If user id specified, try to get a token to use
    TokenInfo tokenInfo = null;
    boolean bUserIdSpecified = !TextUtils.isEmpty(sharedPrefs.getString(ctx.getString(R.string.pref_key_user_id), null));
    if (bUserIdSpecified) tokenInfo = getAGOLToken(ctx, sharedPrefs);

    // Post via REST
    String svcUrl = sharedPrefs.getString(ctx.getString(R.string.pref_key_feat_svc_url), null)
            + "/addFeatures";
    List<Long> successes = new ArrayList<>();
    OkHttpClient http = new OkHttpClient();

    FormBody.Builder fBuild = new FormBody.Builder()
        .add("f", "json")
        .add("rollbackOnFailure", "false")
        .add("features", sJsonAdds);
    if (bUserIdSpecified) fBuild.add("token", tokenInfo.get_token());
    RequestBody reqBody = fBuild.build();
    Request req = new Request.Builder()
        .url(svcUrl)
        .post(reqBody)
        .header("referer", ctx.getString(R.string.agol_request_referer))
        .build();
    Response resp = http.newCall(req).execute();

    // Examine results and return list of rowids of successes
    String jsonRes = resp.body().string();
    JSONObject res = new JSONObject(jsonRes);

    // If error, exit
    if (res.has("error")) {
      throw new IOException("Error posting features: " + res.getJSONObject("error")
        .getJSONArray("details").join("; "));
    }

    // Otherwise, parse the results
    JSONArray addResults = res.getJSONArray("addResults");

    final int rowIdCol = cur.getColumnIndex(ctx.getString(R.string.columnname_rowid));

    cur.moveToFirst();
    for (int iRes = 0; iRes < addResults.length(); iRes++) {
      JSONObject addResult = addResults.getJSONObject(iRes);
      if (addResult.getString("success").equals("true")) {
        long iSuccess = cur.getLong(rowIdCol);
        successes.add(iSuccess);
      }
      cur.moveToNext();
    }

    return successes;
  }

  /** Gets a REST token from arcgis.com given the user-specified id and password.
   * Meant to be called from a worker thread.
   *
   * @param ctx Calling routine's context
   * @param prefs SharedPreferences object for this app
   * @return Json response from token generator, including token, expiration epoch, and ssl (true/false)
   * @throws IOException
   * @throws JSONException
   */
  @WorkerThread
  public static TokenInfo getAGOLToken(Context ctx, SharedPreferences prefs) throws IOException, JSONException {
    // If it takes longer than a half hour to do one sync, we've got bigger problems...
    final long ONEHALFHOUR_MILLIS = 30 * 60 * 1000;
    String token = prefs.getString(ctx.getString(R.string.pref_key_agol_token), null);
    long expiration = prefs.getLong(ctx.getString(R.string.pref_key_agol_token_expiration_epoch), Long.MIN_VALUE);
    boolean mustUseSSL = prefs.getBoolean(ctx.getString(R.string.pref_key_agol_ssl), true);
    TokenInfo tokenInfo;

    long now = System.currentTimeMillis();
    if (TextUtils.isEmpty(token) || expiration < now + ONEHALFHOUR_MILLIS) { // Get a new token
      Log.d(TAG, "Getting new token");
      OkHttpClient http = new OkHttpClient();

      int iDurationMins = ctx.getResources().getInteger(R.integer.agol_token_max_expiration);
      String referer = ctx.getString(R.string.agol_request_referer);
      String svcUrl = prefs.getString(ctx.getString(R.string.pref_key_token_url),
          ctx.getString(R.string.pref_default_token_url));
      String userId = prefs.getString(ctx.getString(R.string.pref_key_user_id), "");
      String password = prefs.getString(ctx.getString(R.string.pref_key_user_pw), "");

      RequestBody reqBody = new FormBody.Builder()
          .add("f", "json")
          .add("username", userId)
          .add("password", password)
          .add("referer", referer)
          .add("expiration", Integer.toString(iDurationMins))
          .build();
      Request req = new Request.Builder()
          .url(svcUrl)
          .post(reqBody)
          .build();

      Response resp = http.newCall(req).execute();
      // Examine results and return list of rowids of successes
      String jsonResp = resp.body().string();
      JSONObject res = new JSONObject(jsonResp);

      // If error, exit
      if (res.has("error")) {
        String msg = res.getJSONObject("error").getString("message");
        throw new IOException(msg + ": " + res.getJSONObject("error")
            .getJSONArray("details").join("; "));
      }
      tokenInfo = new TokenInfo(res);
      // Store token info for later use
      SharedPreferences.Editor editor = prefs.edit();
      editor.putString(ctx.getString(R.string.pref_key_agol_token), tokenInfo.get_token());
      editor.putLong(ctx.getString(R.string.pref_key_agol_token_expiration_epoch),
          tokenInfo.get_expirationEpoch());
      editor.putBoolean(ctx.getString(R.string.pref_key_agol_ssl), tokenInfo.get_mustUseSSL());
      editor.apply();
    } else { // The saved one's still good
      tokenInfo = new TokenInfo(token, expiration, mustUseSSL);
    }

    return tokenInfo;
  }

  /** Here's where newly posted records get flagged as having been posted, so they won't
   * get posted a second time and will eventually be purged from the local database.
   * Meant to be called from a worker thread.<p/>
   * Assumption: successes is a valid list (even if empty)
   * @param db The SqLite database (previously opened read/write)
   * @param ctx The caller's context
   * @param successes A list of unique SQLite row IDs for records that were successfully posted
   */
  @WorkerThread
  public static void updateNewlySentRecordsAsPosted(SQLiteDatabase db, Context ctx, List<Long> successes) {
    String table = ctx.getString(R.string.tablename_readings);
    String postStatusColumn = ctx.getString(R.string.columnname_was_added_to_fc);
    String where = postStatusColumn + " = 0 "
        + "AND rowid IN ("
        + TextUtils.join(",", successes.toArray(new Long[]{}))
        + ")";
    ContentValues postStatus = new ContentValues();
    postStatus.put(postStatusColumn, 1);
    db.update(table, postStatus, where, null);
  }

  /**
   * The number of records in the local database that still need to be posted.
   * Primarily used for display on the main activity.
   * @param db The local database (previously opened read/write)
   * @param ctx The caller's context
   * @return The number of records that still need to be posted to the Feature Service
   */
  public static long getUnpostedRecordsCount(SQLiteDatabase db, Context ctx) {
    String table = ctx.getString(R.string.tablename_readings);
    String[] cols = new String[]{"COUNT(1)"};
    String where = ctx.getString(R.string.whereclause_unposted_records,
        ctx.getString(R.string.columnname_was_added_to_fc));

    Cursor cur = db.query(table, cols, where, null, null, null, null);
    cur.moveToFirst();
    long count = cur.getLong(0);
    cur.close();

    return count;
  }

  /** Helper to get the value of a pref intended to be an int but forced to be a string by
   * EditTextPreference.
   * @param prefs SharedPreferences object
   * @param key The key of the SharedPreferences value desired
   * @param sDefaultVal The string default value of the preference you want
   * @return The integer stored as a string preference
   */
  public static int getIntFromStringPref(SharedPreferences prefs, String key, String sDefaultVal) {
    String sVal = prefs.getString(key, sDefaultVal);
    int val = Integer.MIN_VALUE;
    try {
      val = Integer.parseInt(sVal);
    } catch (NumberFormatException e) {
      Log.e(TAG, "getIntFromStringPref", e);
    }
    return val;
  }

  /**
   * @param ctx Context from caller for getting resources
   * @param writableDb The SQLITE database containing records needing synchronization
   * @param connMgr Connectivity manager (so we know if we're connected and can sync)
   * @param prefs Default shared preferences for this app
   * @return the number of records that needed synchronizing but failed for some reason (should be 0 unless an error occurred)
   */
  @WorkerThread
  public static long doSyncRecords(Context ctx, SQLiteDatabase writableDb, SharedPreferences prefs,
                                   ConnectivityManager connMgr) throws Exception {
    // Don't even try if disconnected or there's nothing to sync
    if (!NetUtils.isConnectedToNetwork(connMgr))
      throw new Exception("Disconnected from the internet");

    Cursor curUnposted = DBUtils.getUnpostedRecords(writableDb, ctx);
    if (curUnposted.getCount() <= 0) return 0;

    // Post adds
    List<Long> recIdsSuccessfullyPosted;
    try {
      recIdsSuccessfullyPosted = DBUtils.postUnpostedRecords(curUnposted, ctx, prefs);
    } catch (IOException e) {
      throw e;
    } catch (JSONException e) {
      throw new Exception("Error parsing add results", e);
    } finally {
      curUnposted.close();
    }

    // Update Sqlite to mark the records as posted
    DBUtils.updateNewlySentRecordsAsPosted(writableDb, ctx, recIdsSuccessfullyPosted);
    long unposted = DBUtils.getUnpostedRecordsCount(writableDb, ctx);
    return unposted;
  }
}
