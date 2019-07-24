package com.esri.apl.signalstrengthlogger;

import android.Manifest;
import android.app.backup.BackupManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class FrgPrefs extends PreferenceFragmentCompat {
  private final static String TAG = "FrgPrefs";
  // onPermissionsResult needs to know when the permissions request was made
  private final static int REQ_PERMISSIONS_ON_STARTUP = 1;
  private final static int REQ_PERMISSIONS_ON_STARTLOGGING = 2;

  private Context mCtx;
  private SharedPreferences mSharedPrefs;

  private final static String[] PERMISSIONS_NEEDED =
      new String[] {Manifest.permission.READ_PHONE_STATE, Manifest.permission.ACCESS_FINE_LOCATION};

  @Override
  public void onAttach(Context context) {
    super.onAttach(context);
    this.mCtx = context;
  }

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    checkAndRequestPermissions(REQ_PERMISSIONS_ON_STARTUP);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    setupPreferencesScreen();
  }

  /**
   * Check to see whether necessary permissions have been granted.
   * @param req_code The proper REQ_ constant defined at the top of this class
   * @return true if all permissions have been granted; false if not or if user response is pending
   */
  private boolean checkAndRequestPermissions(int req_code) {
    List<String> permsNeeded = new ArrayList<>();
    for (int iPerm = 0; iPerm < PERMISSIONS_NEEDED.length; iPerm++) {
      if (ContextCompat.checkSelfPermission(
          mCtx, PERMISSIONS_NEEDED[iPerm]) != PackageManager.PERMISSION_GRANTED)
        permsNeeded.add(PERMISSIONS_NEEDED[iPerm]);
    }
    int iPermsNeeded = permsNeeded.size();
    if (iPermsNeeded > 0)
      requestPermissions(permsNeeded.toArray(new String[iPermsNeeded]), req_code);

    return (iPermsNeeded == 0);
  }

  private void setupPreferencesScreen() {
    // Compute a first-time default for device id
    mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(mCtx);

    String deviceId = mSharedPrefs.getString(getString(R.string.pref_key_device_id), "");
    if (TextUtils.isEmpty(deviceId)) {
      deviceId = UUID.randomUUID().toString();
      mSharedPrefs.edit().putString(getString(R.string.pref_key_device_id), deviceId).commit();
    }

    // Add 'general' preferences.
    addPreferencesFromResource(R.xml.prefs_ui);

    // Set app version as a preference category title
    String version = null;
    try {
      PackageInfo pinfo = 
          getActivity().getPackageManager().getPackageInfo(getActivity().getPackageName(), 0);
      version = pinfo.versionName;
    } catch (PackageManager.NameNotFoundException e) {
      Log.e(TAG, "Error getting app version info", e);
    }
    findPreference(getString(R.string.pref_key_app_version))
        .setTitle(getString(R.string.app_version, version));

    // Bind the summaries of EditText/List/Dialog/Ringtone preferences to
    // their values. When their values change, their summaries are updated
    // to reflect the new value, per the Android Design guidelines.
    // Ordinarily wouldn't bind on/off but need to start/stop service when changed
    listenToPreferenceChanges(findPreference(
        getString(R.string.pref_key_logging_enabled)));
    listenToPreferenceChanges(findPreference(
        getString(R.string.pref_key_feat_svc_url)));
    listenToPreferenceChanges(findPreference(
        getString(R.string.pref_key_user_id)));
    listenToPreferenceChanges(findPreference(
        getString(R.string.pref_key_user_pw)));
    listenToPreferenceChanges(findPreference(
        getString(R.string.pref_key_token_url)));
    listenToPreferenceChanges(findPreference(
        getString(R.string.pref_key_tracking_displacement)));
    listenToPreferenceChanges(findPreference(
            getString(R.string.pref_key_tracking_interval)));
    listenToPreferenceChanges(findPreference(
            getString(R.string.pref_key_sync_interval)));
    listenToPreferenceChanges(findPreference(
        getString(R.string.pref_key_device_id)));

  }

  /** If user changes user id or password, invalidate stored AGOL token **/
  private void clearTokenPrefs() {
    SharedPreferences.Editor editor = mSharedPrefs.edit();
    editor.remove(getString(R.string.pref_key_agol_token));
    editor.remove(getString(R.string.pref_key_agol_token_expiration_epoch));
    editor.remove(getString(R.string.pref_key_agol_ssl));
    editor.apply();
  }
  /**
   * A preference value change listener that updates the preference's summary
   * to reflect its new value.
   */
  private Preference.OnPreferenceChangeListener mBindPreferenceSummaryToValueListener =
      new Preference.OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object value) {
          // Request backup?
          if (Arrays.asList(
              getString(R.string.pref_key_tracking_displacement),
              getString(R.string.pref_key_tracking_interval),
              getString(R.string.pref_key_sync_interval),
              getString(R.string.pref_key_feat_svc_url),
              getString(R.string.pref_key_token_url),
              getString(R.string.pref_key_device_id)
          ).contains(preference.getKey())) {
            Log.d(TAG, "Backup requested");
            mSharedPrefs.edit().putLong(getString(R.string.pref_key_last_modified_datetime),
                System.currentTimeMillis()).apply();
            new BackupManager(getContext()).dataChanged();
          }

          String stringValue = TextUtils.isEmpty(String.valueOf(value))
              ? "<Unspecified>" : String.valueOf(value);

          if (preference instanceof ListPreference) {
            // For list preferences, look up the correct display value in
            // the preference's 'entries' list.
            ListPreference listPreference = (ListPreference) preference;
            int index = listPreference.findIndexOfValue(stringValue);

            // Set the summary to reflect the new value.
            preference.setSummary(
                index >= 0
                    ? listPreference.getEntries()[index]
                    : null);
          } else if (preference.getKey().equals(getString(R.string.pref_key_user_id))) {
            clearTokenPrefs();
            String sUserIdTitle = TextUtils.isEmpty(String.valueOf(value))
                ? "<Unspecified> (feature service must be public)" : String.valueOf(value);
            preference.setSummary(sUserIdTitle);
          } else if (preference.getKey().equals(getString(R.string.pref_key_user_pw))) {
            clearTokenPrefs();
            String sPwMask;
            if (TextUtils.isEmpty(String.valueOf(value))) {
              sPwMask = stringValue;
            } else {
              // Replace password with asterisks
              int pwLen = stringValue.toString().length();
              sPwMask = new String(new char[pwLen]).replace("\0", "*");
            }
            preference.setSummary(sPwMask);
          } else if (preference.getKey().equals(getString(
              R.string.pref_key_logging_enabled))
              && value != null && value instanceof Boolean) {
            // Start/stop the tracking service
            boolean isTrackingEnabled = (boolean) value;

            if (!isTrackingEnabled) stopLogging();
            else { // Trying to start logging
              // Check that permissions have been granted
              if (checkAndRequestPermissions(REQ_PERMISSIONS_ON_STARTLOGGING))
                startLogging();
              // Else if user grants permissions, start logging in onRequestPermissionsResult()
            }
            return false;
          } else if (preference.getKey().equals(getString(R.string.pref_key_tracking_interval))) {
            return validateInterval(
                    preference,
                    stringValue,
                    getString(R.string.pref_min_tracking_interval),
                    getString(R.string.pref_max_tracking_interval),
                    R.string.msg_validation_interval_displacement_sync);
          } else if (preference.getKey().equals(getString(R.string.pref_key_tracking_displacement))) {
            return validateInterval(
                    preference,
                    stringValue,
                    getString(R.string.pref_min_tracking_displacement),
                    getString(R.string.pref_max_tracking_displacement),
                    R.string.msg_validation_interval_displacement_sync);
          } else if (preference.getKey().equals(getString(R.string.pref_key_sync_interval))) {
            return validateInterval(
                    preference,
                    stringValue,
                    getString(R.string.pref_min_sync_interval),
                    getString(R.string.pref_max_sync_interval),
                    R.string.msg_validation_interval_displacement_sync);
          } else {
            // For all other preferences, set the summary to the value's
            // simple string representation.
            preference.setSummary(stringValue);
          }

          return true;
        }
        private boolean validateInterval(Preference preference,
                                         String val, String min, String max,
                                         int msgValidationKey) {
          // Validate tracking interval
          int iMinInterval = Integer.parseInt(min);
          int iMaxInterval = Integer.parseInt(max);

          int iInterval;
          boolean bIsValidInterval = true;

          // Domain validation
          if (TextUtils.isEmpty(val) || !TextUtils.isDigitsOnly(val)) {
            bIsValidInterval = false;
          } else { // Defined-limit validation
            try {
              iInterval = Integer.parseInt(val);
              if (iInterval < iMinInterval || iInterval > iMaxInterval) {
                bIsValidInterval = false;
              }
            } catch (Exception e) {
              bIsValidInterval = false;
            }
          }
          if (!bIsValidInterval) {
            Toast toast = Toast.makeText(
                    mCtx,
                    getString(msgValidationKey, iMinInterval, iMaxInterval),
                    Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER_VERTICAL, 0, 0);
            toast.show();
          } else
            preference.setSummary(val);

          return bIsValidInterval;
        }
      };

  /**
   * Binds a preference's summary to its value. More specifically, when the
   * preference's value is changed, its summary (line of text below the
   * preference title) is updated to reflect the value. The summary is also
   * immediately updated upon calling this method. The exact display format is
   * dependent on the type of preference.
   *
   * @see #mBindPreferenceSummaryToValueListener
   */
  private void listenToPreferenceChanges(Preference preference) {
    // Set the listener to watch for value changes.
    preference.setOnPreferenceChangeListener(mBindPreferenceSummaryToValueListener);

    // Trigger the listener immediately with the preference's
    // current value.
    mBindPreferenceSummaryToValueListener.onPreferenceChange(preference,
        PreferenceManager
            .getDefaultSharedPreferences(preference.getContext())
            .getAll()
            .get(preference.getKey()));
  }

  /** Do what's needed to start logging, including starting the service and setting the switch and title */
  private void startLogging() {
    SwitchPreferenceCompat prefLogging = (SwitchPreferenceCompat) getPreferenceScreen()
        .findPreference(getString(R.string.pref_key_logging_enabled));
    prefLogging.setChecked(true);
    prefLogging.setTitle(R.string.pref_title_logging_enabled);

    Intent intent = new Intent();
    intent.setClass(mCtx, SvcLocationLogger.class);
    getActivity().startService(intent);
  }

  /** Do what's needed to stop logging, including stopping the service and setting the switch and title */
  private void stopLogging() {
    SwitchPreferenceCompat prefLogging = (SwitchPreferenceCompat) getPreferenceScreen()
        .findPreference(getString(R.string.pref_key_logging_enabled));
    prefLogging.setChecked(false);
    prefLogging.setTitle(R.string.pref_title_logging_disabled);

    Intent intent = new Intent(mCtx, SvcLocationLogger.class);
    getActivity().stopService(intent);
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    boolean allPermsGranted = true;
    for (int perm : grantResults)
      if (perm != PackageManager.PERMISSION_GRANTED) {
        allPermsGranted = false;
        break;
      }

    if (!allPermsGranted) {
      // Present rationale
      AlertDialog dlg = new AlertDialog.Builder(mCtx)
          .setMessage("Location and phone signal strength permissions must be granted to allow logging to begin.")
          .setTitle("Permission Needed")
          .setPositiveButton(android.R.string.ok, null)
          .create();
      dlg.show();
    } else if (requestCode == REQ_PERMISSIONS_ON_STARTLOGGING) {
      startLogging();
    }
  }
}
