package com.esri.apl.signalstrengthlogger;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.esri.apl.signalstrengthlogger.data.DBHelper;
import com.esri.apl.signalstrengthlogger.data.ReadingDataPoint;
import com.esri.apl.signalstrengthlogger.util.DBUtils;
import com.esri.apl.signalstrengthlogger.util.NetUtils;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Logs signal strength readings at the prescribed displacement intervals.
 * Location readings require explicitly granted permissions, which we assume were granted
 * in the initial activity, else this service would not have been started.
 */
@SuppressLint("MissingPermission")
public class SvcLocationLogger extends Service {
  private static final int SVC_NOTIF_ID = 1;
  private static final int SYNC_ERR_NOTIF_ID = 2;
  private static final int DB_ERR_NOTIF_ID = 3;
  private static final int LOC_ERR_NOTIF_ID = 4;
  private static final int ACT_PI_REQ_CODE = 1;
  private static final String TAG = "SvcLocationLogger";
  private static final int MS_PER_S = 1000;
  private static final int S_PER_MIN = 60;

  private TelephonyManager mTelMgr;
  private FusedLocationProviderClient mFusedLocationClient = null;
  private ConnectivityManager mConnectivityManager = null;

  private Timer mSyncTimer = new Timer();

  /** Don't use directly; use getter and setter instead. */
  private AtomicBoolean _isConnected = new AtomicBoolean(false);

  /** Don't use directly; use getter and setter instead. */
  private final AtomicLong _unsyncedRecordCount = new AtomicLong(0);

  private SharedPreferences mSharedPrefs = null;
  private SQLiteDatabase mDb;
  private final ArrayList<ReadingDataPoint> mChartData = new ArrayList<>();

  // Various fields needing one-time calculation
  private String mDeviceId;
  private static final String mOsName = "Android";
  private static final String mOsVersion = Integer.toString(Build.VERSION.SDK_INT);
  private static final String mPhoneModel = Build.MANUFACTURER + " " + Build.MODEL;
  private String mCellCarrierName;
  private int MAX_CHART_DATA_POINTS;

  @Override
  public IBinder onBind(Intent intent) {
    Log.d(TAG, "onBind");
    return null;
  }

  @Override
  public void onDestroy() {
    stopLogging();
    super.onDestroy();
    Log.d(TAG, "onDestroy");
  }

  @Override
  public void onCreate() {
    super.onCreate();

    // Get a notification channel ID
    createNotificationChannel();

    mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);

    mDeviceId = mSharedPrefs.getString(getString(R.string.pref_key_device_id), null);

    // This creates the API client, but doesn't call connect.
    mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
    mTelMgr = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);

    mConnectivityManager = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

    mCellCarrierName = "<Unobtainable>";
    if (mTelMgr.getPhoneType() == TelephonyManager.PHONE_TYPE_CDMA
        && mTelMgr.getSimState() == TelephonyManager.SIM_STATE_READY)
      mCellCarrierName = mTelMgr.getSimOperatorName();
    else mCellCarrierName = mTelMgr.getNetworkOperatorName();

    MAX_CHART_DATA_POINTS = getResources().getInteger(R.integer.max_chart_data_points);

    startLogging();

    Log.d(TAG, "onCreate");
  }

  private NotificationChannel createNotificationChannel() {
    NotificationChannel nc = null;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      NotificationManager nm = getSystemService(NotificationManager.class);
      // The channel could possibly have already been created...
      nc = nm.getNotificationChannel(getString(R.string.notif_chan_id));
      if (nc == null) {
        // ...but if not, create it here
        nc = new NotificationChannel(
                getString(R.string.notif_chan_id),
                getString(R.string.notif_chan_name),
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(nc);
      }
    }
    return nc;
  }

  /** Show a non-dismissible notification while the service is running.
   * Make it launch the main activity if the user taps it; this lets them make changes
   * or stop logging. */
  private Notification buildSvcForegroundNotification(String notText) {
    // Create notification and bring to foreground
    Intent intent = new Intent(this, ActMain.class);
    PendingIntent contentIntent =
        PendingIntent.getActivity(this, ACT_PI_REQ_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT);


    NotificationCompat.Builder nb = new NotificationCompat.Builder(this, getString(R.string.notif_chan_id))
        .setSmallIcon(R.drawable.ic_cellsignal)
        .setTicker(getString(R.string.notif_ticker))
        .setContentTitle(getString(R.string.notif_title))
        .setContentIntent(contentIntent);
    if (notText != null) nb.setContentText(notText);

    return nb.build();
  }

  private void updateSvcNotificationText(String notText) {
    Notification not = buildSvcForegroundNotification(notText);
    NotificationManagerCompat.from(this).notify(SVC_NOTIF_ID, not);
  }
  /**
   * Performs various tasks to start logging, including:
   * <ul>
   *     <li>Making the service foreground</li>
   *     <li>Creating a persistent notification</li>
   *     <li>Connecting to the API client and listening to location updates</li>
   * </ul>
   */
  private void startLogging() {
    Notification notification = buildSvcForegroundNotification(null);
    startForeground(SVC_NOTIF_ID, notification);

    // Doc says this call needs to be called from a worker thread since upgrading can be lengthy;
    // however, we know that the main form called this earlier, so we should be okay
    if (mDb == null || !mDb.isOpen()) {
      SQLiteOpenHelper dbhlp = new DBHelper(this);
      mDb = dbhlp.getWritableDatabase();
    }

    long unposted = DBUtils.getUnpostedRecordsCount(mDb, SvcLocationLogger.this);
    set_unsyncedRecordCount(unposted);
    DBUtils.deletePreviouslyPostedRecords(mDb, SvcLocationLogger.this);

    // Get initial connectivity status
    set_isConnected(NetUtils.isConnectedToNetwork(mConnectivityManager));

    // Start listening to location updates
    String sDisp = mSharedPrefs.getString(getString(R.string.pref_key_tracking_displacement), "1");
    int iDisp = Integer.parseInt(sDisp);
    String sInterval = mSharedPrefs.getString(getString(R.string.pref_key_tracking_interval), "5");
    int iInterval = Integer.parseInt(sInterval);
    LocationRequest locReq = (LocationRequest.create())
        .setInterval(iInterval * MS_PER_S)
        .setSmallestDisplacement(iDisp)
        .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

    Task<Void> tskReqLoc =  mFusedLocationClient.requestLocationUpdates(locReq, mLocationListener, Looper.myLooper());
    tskReqLoc.addOnFailureListener(new OnFailureListener() {
      @Override
      public void onFailure(@NonNull Exception e) {
        Log.e(TAG, "Error requesting updates", e);
        showErrorNotification(LOC_ERR_NOTIF_ID, getString(R.string.msg_err_location_request));
        // Stop logging and stop the service entirely
        stopSelf();
      }
    });

    // Start listening to internet connectivity changes (for synchronization)
    IntentFilter flt = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
    flt.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
    registerReceiver(mConnectivityStatusReceiver, flt);

    // Start synchronization on a timer
    String sDefaultSyncMins = getString(R.string.pref_default_sync_interval);
    int iSyncMins = DBUtils.getIntFromStringPref(mSharedPrefs,
        getString(R.string.pref_key_sync_interval), sDefaultSyncMins);
    int iSyncMS = iSyncMins * MS_PER_S * S_PER_MIN;

    mSyncTimer.schedule(new TTSyncToFC(), iSyncMS, iSyncMS);

    Log.d(TAG, "startLogging every " + iSyncMS + " ms");
  }

  /** Called when user has opted to stop logging and the main app has stopped the service.
   * Called from onDestroy() */
  private void stopLogging() {
    // Stop synchronizing on a timer
    mSyncTimer.cancel();

    new Thread(new Runnable() {
      @Override
      public void run() {
        // Sync updates to feature class
        Thread syncThread = syncRecordsToFCNow();

        // Stop listening to internet connectivity changes
        unregisterReceiver(mConnectivityStatusReceiver);

        // Stop listening to location updates
        mFusedLocationClient.removeLocationUpdates(mLocationListener);

        if (mSharedPrefs.getBoolean(getString(R.string.pref_key_logging_enabled), true))
          mSharedPrefs.edit()
              .putBoolean(getString(R.string.pref_key_logging_enabled), false)
              .apply();

        // Close database resources; unfortunately, syncing is done on a worker thread, and we
        // don't want to close the database before syncing finishes
        try {
          // Surrounding thread allows final sync without freezing the UI here
          syncThread.join();

          if (mDb != null && mDb.isOpen()) {
            mDb.close();
            mDb = null;
          }
          // Cleanup
          SQLiteDatabase.releaseMemory();
        } catch (InterruptedException e) {
          Log.e(TAG, "Error waiting for sync thread", e);
        }

        // Clear the persistent logger notification
        NotificationManagerCompat.from(SvcLocationLogger.this).cancel(SVC_NOTIF_ID);

        Log.d(TAG, "stopLogging");

      }
    }).start();
  }

  /** Here's where logging is done: when a location update is received. */
  private LocationCallback mLocationListener = new LocationCallback() {
    @Override
    public void onLocationResult(final LocationResult locationResult) {

      new Thread(new Runnable() {
        @Override
        public void run() {
          Location loc = locationResult.getLastLocation();
          if (loc != null) { // Save to DB
            int signalStrength = getCellSignalStrength();

            long datetime = loc.getTime();
            double x = loc.getLongitude();
            double y = loc.getLatitude();
            double z = loc.getAltitude();

            // TODO Change this if you want to change the database schema and what's being recorded
            // Save reading to DB
            ContentValues vals = new ContentValues();
            vals.put(getString(R.string.columnname_signalstrength), signalStrength);
            vals.put(getString(R.string.columnname_date), datetime);
            vals.put(getString(R.string.columnname_longitude), x);
            vals.put(getString(R.string.columnname_latitude), y);
            if (loc.hasAltitude())
              vals.put(getString(R.string.columnname_altitude), z);
            vals.put(getString(R.string.columnname_osname), mOsName);
            vals.put(getString(R.string.columnname_osversion), mOsVersion);
            vals.put(getString(R.string.columnname_phonemodel), mPhoneModel);
            vals.put(getString(R.string.columnname_deviceid), mDeviceId);
            vals.put(getString(R.string.columnname_carrierid), mCellCarrierName);

            try {
              mDb.insertOrThrow(getString(R.string.tablename_readings), null, vals);

              set_unsyncedRecordCount(get_unsyncedRecordCount() + 1);

              // Save to chart data
              ReadingDataPoint rdp = new ReadingDataPoint(
                  signalStrength, loc.getTime(), x, y, z, mOsName, mOsVersion,
                  mPhoneModel, mDeviceId, mCellCarrierName);
              mChartData.add(rdp);
              int overflow = mChartData.size() - MAX_CHART_DATA_POINTS;
              while (overflow-- > 0) {
                mChartData.remove(0);
              }
              Intent intent = new Intent();
              intent.setAction(getString(R.string.intent_category_chart_data_available));
              intent.putParcelableArrayListExtra(getString(R.string.extra_chart_data_list), mChartData);
              // Send only to local process
              LocalBroadcastManager.getInstance(SvcLocationLogger.this).sendBroadcast(intent);
              Log.d(TAG, "Sent chart data broadcast");
            } catch (SQLException e){
              String sErrMsg = (e.getCause() == null)
                  ? e.getLocalizedMessage()
                  : TextUtils.join("; ", new String[]{
                        e.getLocalizedMessage(), e.getCause().getLocalizedMessage()});
              showErrorNotification(DB_ERR_NOTIF_ID, sErrMsg);
              Log.e(TAG, "Error inserting reading", e);
            }
          }
        }
      }).start();
    }
  };


  /** Signal strength value
   *  Note: Suppress missing permission error, as this check was done in the main activity
   *  before this service was started.
   *  We could have logged a more granular cell signal level, but iOS can only log 0-4, and we need
   *  parity in the database.
   * @return 0 through 4 (0 even if signal missing or invalid)
   */
  private int getCellSignalStrength() {
    int strength = Integer.MIN_VALUE;
    List<CellInfo> cellInfos = mTelMgr.getAllCellInfo();   //This will give info of all sims present inside your mobile
    if(cellInfos!=null) {
      for (int i = 0; i < cellInfos.size(); i++) {
        if (cellInfos.get(i).isRegistered()) {
          if (cellInfos.get(i) instanceof CellInfoWcdma) {
            CellInfoWcdma cellInfoWcdma = (CellInfoWcdma) mTelMgr.getAllCellInfo().get(0);
            CellSignalStrengthWcdma cellSignalStrengthWcdma = cellInfoWcdma.getCellSignalStrength();
            strength = cellSignalStrengthWcdma.getLevel();
          } else if (cellInfos.get(i) instanceof CellInfoLte) {
            CellInfoLte cellInfoLte = (CellInfoLte) mTelMgr.getAllCellInfo().get(0);
            CellSignalStrengthLte cellSignalStrengthLte = cellInfoLte.getCellSignalStrength();
            strength = cellSignalStrengthLte.getLevel();
          } else if (cellInfos.get(i) instanceof CellInfoGsm) {
            CellInfoGsm cellInfogsm = (CellInfoGsm) mTelMgr.getAllCellInfo().get(0);
            CellSignalStrengthGsm cellSignalStrengthGsm = cellInfogsm.getCellSignalStrength();
            strength = cellSignalStrengthGsm.getLevel();
          }
        }
      }
    }
    strength = Math.max(0, strength);
    Log.d(TAG, "Strength: " + strength);

    return strength;
  }


  /**
   * Reads unposted records from local database and posts them as inserts to an online feature service.
   */
  private Thread syncRecordsToFCNow() {
    Thread thread = new Thread(new Runnable() {
      @Override
      public void run() {
        Log.d(TAG, "Starting sync");

        Context ctx = SvcLocationLogger.this;

        try {
          long unposted = DBUtils.doSyncRecords(ctx, mDb, mSharedPrefs, mConnectivityManager);

          set_unsyncedRecordCount(unposted);
          Log.d(TAG, unposted + " records are unposted");

          // Success; clear any error notification
          NotificationManagerCompat.from(ctx).cancel(SYNC_ERR_NOTIF_ID);
        } catch (Exception e) {
          Log.e(TAG, "Error synchronizing readings", e);
          String msg = e.getLocalizedMessage();
          if (e.getCause() != null)
            msg += ":\n" + e.getCause().getLocalizedMessage();
          showErrorNotification(SYNC_ERR_NOTIF_ID, msg);
        }
      }
    });
    thread.start();
    return thread;
  }
  private class TTSyncToFC extends TimerTask {
    @Override
    public void run() {
      syncRecordsToFCNow();
    }
  }

  /** The main activity might be gone from memory. The only UI a service has is notifications. */
  private void showErrorNotification(int notifId, String errText) {
    Context ctx = this;
    Notification notification = new NotificationCompat.Builder(ctx, getString(R.string.notif_chan_id))
        .setContentTitle(getString(R.string.title_err_synchronization))
        .setStyle(new NotificationCompat.BigTextStyle()
            .bigText(getString(R.string.msg_err_synchronization, errText)))
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setGroup(getString(R.string.group_err_synchronization))
        .build();
    NotificationManagerCompat.from(ctx)
        .notify(notifId, notification);
  }

  /** Listen to internet connectivity in order to sync */
  BroadcastReceiver mConnectivityStatusReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      // Do any disconnected/reconnected tasks in setter
      set_isConnected(NetUtils.isConnectedToNetwork(mConnectivityManager));
    }
  };


  private boolean get_isConnected() {
    return this._isConnected.get();
  }

  private void set_isConnected(boolean isConnected) {
    if (this._isConnected.get() != isConnected) {
      this._isConnected.set(isConnected);
      // If reconnecting, synchronize
      if (isConnected) syncRecordsToFCNow();
    }
  }

  public long get_unsyncedRecordCount() {
    return _unsyncedRecordCount.get();
  }

  public void set_unsyncedRecordCount(long unsyncedRecordCount) {
    Log.d(TAG, "set_unsyncedRecordCount => " + unsyncedRecordCount);
    this._unsyncedRecordCount.set(unsyncedRecordCount);

//    if (this._unsyncedRecordCount.get() == 0 || this._unsyncedRecordCount.get() % 5 == 0)
      updateSvcNotificationText(
          getString(R.string.notif_unsynced_records, this._unsyncedRecordCount.get()));

    // Broadcast record count to activity
    Intent intent = new Intent();
    intent.setAction(getString(R.string.intent_category_unsynced_count));
    intent.putExtra(getString(R.string.extra_unsynced_count), get_unsyncedRecordCount());
    // Send only to local process
    LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
  }
}
