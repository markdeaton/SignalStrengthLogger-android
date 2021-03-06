package com.esri.apl.signalstrengthlogger.data;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/** Information about a generated security token (if using a secure service) */
public class TokenInfo {
  private static final String TAG = "TokenInfo";

  private String _token;
  private long _expirationEpoch;
  private boolean _mustUseSSL;

  public TokenInfo(String token, long expiration, boolean mustUseSSL) {
    set_token(token);
    set_expirationEpoch(expiration);
    set_mustUseSSL(mustUseSSL);
  }

  public TokenInfo(JSONObject resp) {
    try {
      set_token(resp.getString("token"));
    } catch (JSONException e) {
      Log.e(TAG, "Error getting token", e);
    }
    try {
      set_expirationEpoch(resp.getLong("expires"));
    } catch (JSONException e) {
      Log.e(TAG, "Error getting token expiration", e);
    }
    try {
      set_mustUseSSL(resp.getBoolean("ssl"));
    } catch (JSONException e) {
      Log.e(TAG, "Error getting ssl flag", e);
    }
  }

  public String get_token() {
    return _token;
  }

  public void set_token(String token) {
    this._token = token;
  }

  public long get_expirationEpoch() {
    return _expirationEpoch;
  }

  public void set_expirationEpoch(long expirationEpoch) {
    this._expirationEpoch = expirationEpoch;
  }

  public boolean get_mustUseSSL() {
    return _mustUseSSL;
  }

  public void set_mustUseSSL(boolean mustUseSSL) {
    this._mustUseSSL = mustUseSSL;
  }

}
