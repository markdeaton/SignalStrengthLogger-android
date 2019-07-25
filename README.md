# SignalStrengthLogger-android
Detects and logs cell signal strength along with device location.


### What is it?
This app very simply gathers the device's location and the strength of its
cellular connection at specified time and space intervals,
and saves it to a feature service layer hosted on ArcGIS Online.
The app is very simple in design. It has a main settings page, including a switch to
    start and stop logging location and signal strength. The main activity also
    shows a line chart of the fifteen most recent signal reading values while logging.
    Logging is done in a service, so that it can run in the background even when
    other apps are running or when the display is turned off. A persistent
    notification tells the user that the logger is still active.
### Why build it?
This is an idea that could be adapted to log just about anything a mobile device is capable
of detecting.
### Requirements
* Android Studio 3+
* An Android device running Android 18 or above (JellyBean 4.3) and having GPS hardware
### Android concepts illustrated:
1. Foreground (high priority) logging service<p/>
    When logging is active, a persistent notification in the status bar signals
    that the logging service is running, and also how many locally saved records
    still need to be synchronized to the feature service layer.
    The logger is implemented as a foreground
    service, meaning it should almost never be shut down for lack of resources.
    Logging will continue while other apps are open and in the foreground, or even
    while the device display is turned off.
1. Obtaining cell signal strength value<p/>
    The `SvcLocationLogger.getCellSignalStrength()` method shows how to get the
    current cellular signal strength (values from 0-4). Other more detailed signal
    values are available, but the iOS platform can only read values of 0 to 4; to
    allow like comparisons between the two platforms, the Android version restricts itself
    to this value range.
1. Offline storage: saving to and updating a local Sqlite database<p/>
    This app is meant for use in remote areas where internet may not be available for
    much of the data collection exercise. It's important that it be able to save readings
    on the device and then send them to the feature service layer once internet is available.
    All readings are saved to a local SQLite database, and then synchronized on a schedule,
    or when internet connecivity is reestablished.
1. Using Google Play Services Fused Location Provider<p/>
    [Google recommends using the Google Location Services API](https://developer.android.com/reference/android/location/package-summary.html)
     when possible. This
    provides the best combination of location accuracy and battery use, and makes it
    easier to limit readings by time or by distance apart. This also means
    that only Google versions of Android will run this app (leaving out heavily
    customized versions of Android such as those on Kindle Fire devices). Basically, if
    your device has the Google Play Store app and runs Android 4.3 or higher, it should
    run this.
    The app provides settings for both time and distance between readings. Readings will be taken
    as **infrequently** as possible, according to the combination of those two settings.
    You can set either time or distance to zero, if you just want to limit by one of those
    two factors. [It's best not to set both to zero](https://developers.google.com/android/reference/com/google/android/gms/location/LocationRequest.html#setInterval(long)).
1. Using ArcGIS REST API to save features to a feature service layer<p/>
    This app does not use the Esri runtime for Android. It only needs to write data
    out to a feature service layer, not to read or to map it. So it makes http POST operations
    directly against the REST endpoint for the feature service layer. You can change this
    service in the settings page; note that it should **not** include the `addFeature` portion of
    the URL.
1. Generating tokens for updating secure feature services<p/>
    If you specify a username, password,
    and token generator URL, the app will generate and use a security token against
     your secured feature service layer.
     The token will be saved in shared preferences until either it expires or until
     the username or password settings are changed in the main activity.
1. Shared Preferences / PreferenceFragmentCompat<p/>
    All settings on the main activity are saved to persistent application settings
    (`SharedPreferences`).
1. Backing up & restoring data with Google backup service<p/>
    All settings except username and password are backed up to your Google account automatically.
    That means that if you uninstall and then reinstall the app on the same device
    under the same Google account, all those settings should be automatically restored.
    This assumes you've allowed app data backup in the device settings. For implementation
    details, see the manifest settings and the `PrefsBackup.java` class.
1. Charting ongoing result counts<p/>
    While logging, the main activity shows a line chart of the fifteen most recent signal
    strength values. I used the [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart) library
    to do this. When a reading is taken, the service broadcasts a list of the last fifteen readings;
     if the main activity is up and running and hasn't been killed due to non-use, it will
     receive these and display them.
### Third-party components and licenses
I used a handful of third-party components to build this app; all are licensed under
[Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0).
* [OKHTTP3](https://github.com/square/okhttp/tree/master/okhttp/src/main/java/okhttp3) (for POSTing
to the feature service layer)
* [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart)
* [Takisoft Android Support Preference v7 fix](https://github.com/Gericop/Android-Support-Preference-V7-Fix)
This fixes a strange incompatibility between Android's built-in styles and the
PreferenceFragmentCompat class that causes the app to crash when they're used together.
### To run it
1. Create a hosted feature layer to hold the results<p/>
    You'll need a hosted feature layer to hold the collected data.
    1. Download the template file geodatabase here: https://www.arcgis.com/home/item.html?id=a6ea4b56e9914f82a2616685aef94ec0
    1. Follow the instructions to publish it here: https://doc.arcgis.com/en/arcgis-online/share-maps/publish-features.htm#ESRI_SECTION1_F878B830119B4443A8CFDA8A96AAF7D1
1. Install by sideloading<p/>
    This app will run on devices that are running Android 4.3 (the last version of "Jelly Bean") or above. It will only run on Google versions of Android--not on proprietary versions of Android, such as the Amazon Kindle Fire devices. If you're running Android 4.3 or later on a device that has the Google Play Store app, you should be able to run this. (Oh, you'll need a functional cell plan as well.)
 You'll need to install this app through an alternative process called "sideloading".
    1. (Optional) Modify the app source code to point to the feature layer you created above;
        set this in `prefs.xml`, element `pref_default_feat_svc_url`.
        You can skip this step and enter the feature layer item URL as outlined
        in the *Settings* section below.
    1. Enable sideloading; more info on this is here: https://developer.android.com/distribute/marketing-tools/alternative-distribution.html#unknown-sources
    1. Build the binary .apk installer from source code.
    1. Copy the file onto your device.
    1. Open and install the copied .apk file.
1. Settings<p/>
    ![Settings in main activity](https://github.com/markdeaton/SignalStrengthLogger-android/blob/master/README-screenshots/Screenshot_20180312-113859.png)
    <br/>
    Tap the `Feature Service URL` item and enter the address of the feature service layer you've created
    and hosted.
    There are two settings affecting the logging frequency. You can set a distance between
    readings in meters and you can set a time between readings in seconds.
    Readings will be taken no more often than the combination of these settings.
    For example, a setting of ten meters and ten seconds means that the next reading
    won't be taken until the user has moved at least ten meters and at least ten seconds
    have passed. If you want to only limit readings by distance, you can set the
    seconds to zero. Please don't set both time and distance to zero.<p/>
    The User ID and password settings are for using secured services.
    If you are using your own ArcGIS Enterprise Portal (not ArcGIS.com), and you want to
    log to a secured service, you'll need to enable enter
    [your own portal's token generator URL](https://enterprise.arcgis.com/en/server/latest/administer/windows/acquiring-arcgis-tokens.htm)
     into the `Token Generator Service URL` setting.<p/>
    Start logging by tapping the switch control at the top of the settings page. You should
    see a fan-shaped icon (a little like the wifi icon) in the notification bar.
    That tells you that the app is logging readings in the background.
    ![Logger notification](https://github.com/markdeaton/SignalStrengthLogger-android/blob/master/README-screenshots/Screenshot_20180312-112536.png)
    <br/>
    It will continue logging until you tap the switch control again to turn logging off. As it
    logs, the main activity will show a line chart with the last fifteen signal strength
    readings.
    <br/>
    ![Logging with chart](https://github.com/markdeaton/SignalStrengthLogger-android/blob/master/README-screenshots/Screenshot_20180312-112638.png)
    <p/>
    You can turn the screen off or use other apps during logging, since it runs as a
    background service. An easy way to get back to the settings screen is to pull down
    the notification bar and tap the logger notification item.
    Features are logged to a local database, and then sent to the feature service when
    the internet is available.
### Synchronization
Synchronization is the process of copying location and signal readings from the device's
local database up to the hosted feature layer on ArcGIS Online.
There are three events that cause a synchronization:
1. There is a setting for the synchronization interval; the app will
try to sync whenever that many minutes have passed;
1. When internet connectivity has been lost and then restored;
1.  When the logging switch is turned off and logging stops
### Caveats
* If you're running Android "Marshmallow" (6.0) or above, the app will ask you for
permissions when you first start it up. It needs to be allowed get your location and the phone
signal strength in order to do its job. If you don't grant both these permissions,
you won't be allowed to start logging.
* If you turn off logging when you're disconnected from the internet,
it won't be able to send any unsynchronized records to the feature service.
Those features are still in the local database and once the device reconnects
to the internet, the "Sync Now" button should become enabled to let you start a
synchronization in this situation. In the worst case, you can start and stop logging
to initiate another synchronization.
* This was not tested on a dual-SIM system, so it may or may not work on a device
loaded with more than one SIM. It was only tested on GSM devices (AT&T and T-Mobile).
It *should* work on a Verizon plan, but there are no guarantees.
