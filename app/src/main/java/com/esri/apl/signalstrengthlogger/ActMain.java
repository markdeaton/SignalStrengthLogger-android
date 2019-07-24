package com.esri.apl.signalstrengthlogger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.crashlytics.android.Crashlytics;
import com.esri.apl.signalstrengthlogger.data.DBHelper;
import com.esri.apl.signalstrengthlogger.data.ReadingDataPoint;
import com.esri.apl.signalstrengthlogger.util.DBUtils;
import com.esri.apl.signalstrengthlogger.util.NetUtils;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import java.util.ArrayList;
import java.util.List;

import io.fabric.sdk.android.Fabric;

public class ActMain extends AppCompatActivity {
  public final static String TAG = "ActMain";
  private final int REQ_MAKE_GOOGLE_APIS_AVAILABLE = 1;
  private LineChart mSignalChart;
  private View mLytSignalChart;
  private TextView mLblUnsyncedRecords;
  private Button mBtnSyncNow;

  private SQLiteDatabase mDb;
  private ConnectivityManager mConnMgr;
  private SharedPreferences mSharedPrefs;


  private long _unsyncedRecordCount = 0;
  private boolean _isInternetConnected;
  private boolean _isLogging;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    Fabric.with(this, new Crashlytics());

    setContentView(R.layout.act_main);

    mLblUnsyncedRecords = (TextView)findViewById(R.id.lblUnsyncedRecords);
    mBtnSyncNow = (Button)findViewById(R.id.btnSyncNow);
    mLytSignalChart = findViewById(R.id.lytSignalChart);

    mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
    mSharedPrefs.registerOnSharedPreferenceChangeListener(mPrefsChangeListener);

    set_isLogging(mSharedPrefs.getBoolean(getString(R.string.pref_key_logging_enabled), false));

    mConnMgr = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

    // Create the SQLite database if needed
    (new Thread(new Runnable() {
      @Override
      public void run() {
        DBHelper dbUtils = new DBHelper(ActMain.this);
        mDb = dbUtils.getWritableDatabase();
      }
    })).start();

    mBtnSyncNow.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        doSyncNow();
      }
    });

    // Chart initialization
    mSignalChart = (LineChart)findViewById(R.id.signalChart);
    mSignalChart.setDrawGridBackground(false);
    mSignalChart.setDescription(null);
    mSignalChart.getAxisRight().setEnabled(false);
    XAxis xaxis = mSignalChart.getXAxis();
    xaxis.setEnabled(false);
    YAxis yaxis = mSignalChart.getAxisLeft();
    yaxis.setTextSize(12);
    yaxis.setLabelCount(5, true);
    yaxis.setAxisMaximum(4f);
    yaxis.setAxisMinimum(0f);
    yaxis.setDrawZeroLine(true);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    mSharedPrefs.unregisterOnSharedPreferenceChangeListener(mPrefsChangeListener);
    mDb.close();
  }

  private void doSyncNow() {
    new Thread(new Runnable() {
      @Override
      public void run() {
        Context ctx = ActMain.this;
        ConnectivityManager connMgr =
            (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
          long unposted = DBUtils.doSyncRecords(ctx, mDb, mSharedPrefs, connMgr);
          set_unsyncedRecordCount(unposted);
        } catch (Exception e) {
          Log.e(TAG,"Error synchronizing", e);
          runOnUiThread(new Runnable() {
            @Override
            public void run() {
              Toast.makeText(ctx, getString(R.string.msg_err_synchronization, e.getMessage()),
                  Toast.LENGTH_LONG)
                  .show();
            }
          });
        }
      }
    }).start();
  }

  @Override
  protected void onStart() {
    super.onStart();

    // Start listening for internet connectivity changes
    IntentFilter fltConn = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
    fltConn.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
    registerReceiver(mConnectivityStatusReceiver, fltConn);

    // Start listening for chart data from the service
    IntentFilter fltCht = new IntentFilter();
    fltCht.addAction(getString(R.string.intent_category_chart_data_available));
    LocalBroadcastManager.getInstance(this).registerReceiver(mChartDataReceiver, fltCht);

    // Start listening for unsynchronized record counts
    IntentFilter fltUnsyncedCount = new IntentFilter();
    fltUnsyncedCount.addAction(getString(R.string.intent_category_unsynced_count));
    LocalBroadcastManager.getInstance(this).registerReceiver(mUnsyncedCountReceiver, fltUnsyncedCount);
  }

  @Override
  protected void onStop() {
    super.onStop();

    // Stop listening for unsynchronized record counts
    LocalBroadcastManager.getInstance(this).unregisterReceiver(mUnsyncedCountReceiver);

    // Stop listening for chart data
    LocalBroadcastManager.getInstance(this).unregisterReceiver(mChartDataReceiver);

    // Stop listening for internet connectivity changes
    unregisterReceiver(mConnectivityStatusReceiver);
  }

  @Override
  protected void onResume() {
    super.onResume();

    // Check to make sure Google Play Services is available
    GoogleApiAvailability gaa = GoogleApiAvailability.getInstance();
    int err = gaa.isGooglePlayServicesAvailable(this);
    if (err != ConnectionResult.SUCCESS) {
        new AlertDialog.Builder(this)
            .setMessage(R.string.msg_err_play_svcs_unavailable)
            .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialogInterface, int i) {
                finish();
              }
            })
            .create()
            .show();
    }



    if (mDb != null) { // Get # unposted
      long unposted = DBUtils.getUnpostedRecordsCount(mDb, this);
      set_unsyncedRecordCount(unposted);
    }
  }

  private BroadcastReceiver mConnectivityStatusReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      boolean isConnected = NetUtils.isConnectedToNetwork(mConnMgr);
      set_isInternetConnected(isConnected);
    }
  };

  public long get_unsyncedRecordCount() {
    return _unsyncedRecordCount;
  }
  public void set_unsyncedRecordCount(long unsyncedRecordCount) {
    this._unsyncedRecordCount = unsyncedRecordCount;
    String sUnsyncedRecords = getString(R.string.notif_unsynced_records, unsyncedRecordCount);
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        mLblUnsyncedRecords.setText(sUnsyncedRecords);
      }
    });
    setSyncButtonEnabled();
    doSyncIfNeeded();
  }
  public boolean get_isInternetConnected() {
    return _isInternetConnected;
  }
  public void set_isInternetConnected(boolean isInternetConnected) {
    this._isInternetConnected = isInternetConnected;
    doSyncIfNeeded();
    setSyncButtonEnabled();
  }
  private boolean get_isLogging() {
    return _isLogging;
  }
  public void set_isLogging(boolean isLogging) {
    this._isLogging = isLogging;
    setSyncButtonEnabled();
  }

  public boolean get_isSyncNeeded() {
    boolean bNeedToSync = get_unsyncedRecordCount() > 0
        && get_isInternetConnected()
        && !get_isLogging();
    return bNeedToSync;
  }

  private void doSyncIfNeeded() {
    // Enable sync button
    // *Try* to sync, knowing that circumstances outside the three variables we're tracking
    // may prevent it until later (most notably if username/pw are incorrect against a
    // secured service)
    if (get_isSyncNeeded()) doSyncNow();

    Log.d(TAG, "Need to sync: " + Boolean.toString(get_isSyncNeeded()));
  }

  private void setSyncButtonEnabled() {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        mBtnSyncNow.setEnabled(get_isSyncNeeded());
      }
    });
  }

  private BroadcastReceiver mChartDataReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      ArrayList<ReadingDataPoint> chartData =
          intent.getParcelableArrayListExtra(getString(R.string.extra_chart_data_list));
      Log.d(TAG, "onReceive chart data");

      if (chartData != null && chartData.size() > 0) {
        mLytSignalChart.setVisibility(View.VISIBLE);
        // Update chart
        List<Entry> chartEntries = new ArrayList<>();
        for (int i = 0; i < chartData.size(); i++) {
          chartEntries.add(new Entry(i, chartData.get(i).get_signalStrength()));
        }
        LineDataSet dataset = new LineDataSet(chartEntries, "Readings");
        dataset.setMode(LineDataSet.Mode.STEPPED);
        dataset.setCircleColor(Color.BLACK);
        dataset.setColor(Color.BLACK);
        dataset.setDrawValues(false);
        dataset.setFillColor(Color.DKGRAY);
        dataset.setFillAlpha(85);
        dataset.setDrawFilled(true);
        LineData data = new LineData(dataset);
        mSignalChart.setData(data);
        mSignalChart.getLegend().setEnabled(false);
        mSignalChart.invalidate();
      }
    }
  };
  private BroadcastReceiver mUnsyncedCountReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      // Get unsynced record count
      long unsyncedCount = intent.getLongExtra(getString(R.string.extra_unsynced_count), 0);
      set_unsyncedRecordCount(unsyncedCount);
    }
  };

  /** Listen to logging-switch pref so we know to enable-disable sync button */
  private SharedPreferences.OnSharedPreferenceChangeListener mPrefsChangeListener =
      new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
          if (s.equals(getString(R.string.pref_key_logging_enabled)))
            set_isLogging(sharedPreferences.getBoolean(s, false));
        }
      };
}
