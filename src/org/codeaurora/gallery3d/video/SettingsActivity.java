package org.codeaurora.gallery3d.video;

import android.app.ActionBar;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemProperties;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.RingtonePreference;
import android.preference.PreferenceScreen;
import android.provider.ContactsContract;
import android.provider.Settings.System;
import android.provider.Telephony;
import android.telephony.MSimTelephonyManager;
import android.telephony.TelephonyManager;
import android.text.method.DigitsKeyListener;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import com.android.gallery3d.R;

import java.util.ArrayList;

public class SettingsActivity extends PreferenceActivity {

    private static final String LOG_TAG = "SettingsActivity";

    public static final String PREFERENCE_RTP_MINPORT = "rtp_min_port";
    public static final String PREFERENCE_RTP_MAXPORT = "rtp_max_port";
    private static final String PREFERENCE_KEEP_ALIVE_INTERVAL_SECOND = "keep_alive_interval_second";
    private static final String PREFERENCE_CACHE_MIN_SIZE = "cache_min_size";
    private static final String PREFERENCE_CACHE_MAX_SIZE = "cache_max_size";
    public static final String PREFERENCE_BUFFER_SIZE = "buffer_size";
    public static final String PREFERENCE_APN = "apn";

    private static final int DEFAULT_RTP_MINPORT = 15550;
    private static final int DEFAULT_RTP_MAXPORT = 65535;
    private static final int DEFAULT_CACHE_MIN_SIZE = 4 * 1024 * 1024;
    private static final int DEFAULT_CACHE_MAX_SIZE = 20 * 1024 * 1024;
    private static final int DEFAULT_KEEP_ALIVE_INTERVAL_SECOND = 15;

    private SharedPreferences mPref;
    private EditTextPreference mRtpMinPort;
    private EditTextPreference mRtpMaxPort;
    private EditTextPreference mBufferSize;
    private PreferenceScreen mApn;
    private CheckBoxPreference mRepeat;

    private static final int SELECT_APN = 1;
    public static final String PREFERRED_APN_URI = "content://telephony/carriers/preferapn";
    private static final Uri PREFERAPN_URI = Uri.parse(PREFERRED_APN_URI);
    private static final int ID_INDEX = 0;
    private static final int NAME_INDEX = 1;

    private boolean mUseNvOperatorForEhrpd = SystemProperties.getBoolean(
            "persist.radio.use_nv_for_ehrpd", false);

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.rtsp_settings_preferences);

        mPref = getPreferenceScreen().getSharedPreferences();
        final String rtpMinPortStr = mPref.getString(PREFERENCE_RTP_MINPORT, Integer.toString(DEFAULT_RTP_MINPORT));
        final String rtpMaxPortStr = mPref.getString(PREFERENCE_RTP_MAXPORT, Integer.toString(DEFAULT_RTP_MAXPORT));
        final String bufferSizeStr = mPref.getString(PREFERENCE_BUFFER_SIZE, Integer.toString(DEFAULT_CACHE_MAX_SIZE));

        mRtpMinPort = (EditTextPreference) findPreference(PREFERENCE_RTP_MINPORT);
        mRtpMinPort.getEditText().setKeyListener(DigitsKeyListener.getInstance("0123456789"));
        mRtpMinPort.setSummary(rtpMinPortStr);
        mRtpMinPort.setText(rtpMinPortStr);
        mRtpMinPort.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                final String summary = newValue.toString();
                final int rtpMinPort;
                try {
                    rtpMinPort = Integer.valueOf(summary);
                } catch (NumberFormatException e) {
                    Log.e(LOG_TAG, "Failed to parse rtp min port");
                    return false;
                }
                mRtpMinPort.setSummary(summary);
                mRtpMinPort.setText(summary);
                Log.d("rtsp", "z66 summary = " + summary);
                System.putString(getContentResolver(), "streaming_min_udp_port", summary);
                enableRtpPortSetting();
                return true;
            }
        });

        mRtpMaxPort = (EditTextPreference) findPreference(PREFERENCE_RTP_MAXPORT);
        mRtpMaxPort.getEditText().setKeyListener(DigitsKeyListener.getInstance("0123456789"));
        mRtpMaxPort.setSummary(rtpMaxPortStr);
        mRtpMaxPort.setText(rtpMaxPortStr);
        mRtpMaxPort.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                final String summary = newValue.toString();
                final int rtpMaxPort;
                try {
                    rtpMaxPort = Integer.valueOf(summary);
                } catch (NumberFormatException e) {
                    Log.e(LOG_TAG, "Failed to parse rtp max port");
                    return false;
                }
                mRtpMaxPort.setSummary(summary);
                mRtpMaxPort.setText(summary);
                Log.w("rtsp", "z82 summary = " + summary);
                System.putString(getContentResolver(), "streaming_max_udp_port", summary);
                enableRtpPortSetting();
                return true;
            }
        });

        mBufferSize = (EditTextPreference) findPreference(PREFERENCE_BUFFER_SIZE);
        mBufferSize.getEditText().setKeyListener(DigitsKeyListener.getInstance("0123456789"));
        mBufferSize.setSummary(bufferSizeStr);
        mBufferSize.setText(bufferSizeStr);
        mBufferSize.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                final String summary = newValue.toString();
                final int bufferSize;
                try {
                    bufferSize = Integer.valueOf(summary);
                } catch (NumberFormatException e) {
                    Log.e(LOG_TAG, "Failed to parse buffer size");
                    return false;
                }
                mBufferSize.setSummary(summary);
                mBufferSize.setText(summary);
                enableBufferSetting();
                return true;
            }
        });

        mApn = (PreferenceScreen) findPreference(PREFERENCE_APN);
        mApn.setSummary(getDefaultApnName());
        mApn.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
                Intent intent = new Intent();
                intent.setClassName("com.android.settings", "com.android.settings.ApnSettings");
                startActivityForResult(intent, SELECT_APN);
                return true;
            }
        });

        ActionBar ab = getActionBar();
        ab.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
        ab.setDisplayHomeAsUpEnabled(true);
        ab.setTitle(R.string.setting);
    }

    private String getDefaultApnName() {

        // to find default key
        String key = null;
        String name = null;
        Cursor cursor = getContentResolver().query(PREFERAPN_URI, new String[] {
                "_id"
        }, null, null, Telephony.Carriers.DEFAULT_SORT_ORDER);
        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            key = cursor.getString(ID_INDEX);
            Log.v("settingActivty", "default apn key = " + key);
        }
        cursor.close();

        // to find default proxy
        String where = getOperatorNumericSelection();

        cursor = getContentResolver().query(Telephony.Carriers.CONTENT_URI, new String[] {
                "_id", "name", "apn", "type"
        }, where, null, Telephony.Carriers.DEFAULT_SORT_ORDER);

        while (cursor != null && cursor.moveToNext()) {
            String curKey = cursor.getString(cursor.getColumnIndex("_id"));
            String curName = cursor.getString(cursor.getColumnIndex("name"));
            if (curKey.equals(key)) {
                Log.d("rtsp", "getDefaultApnName, find, key=" + curKey + ",curName=" + curName);
                name = curName;
                break;
            }
        }

        if (cursor != null) {
            cursor.close();
        }
        return name;

    }

    private String getSelectedApnKey() {
        String key = null;

        Cursor cursor = getContentResolver().query(PREFERAPN_URI, new String[] {
                "_id", "name"
        }, null, null, null);
        if (cursor.getCount() > 0) {
            cursor.moveToFirst();
            key = cursor.getString(NAME_INDEX);
        }
        cursor.close();

        Log.w("rtsp", "getSelectedApnKey key = " + key);
        if (null == key)
            return new String("null");
        return key;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case SELECT_APN:
                setResult(resultCode);
                finish();
                Log.w("rtsp", "onActivityResult requestCode = " + requestCode);
                break;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // TODO Auto-generated method stub
        if (item.getItemId() == android.R.id.home) {
            finish();
        }
        return true;
    }

    private String getOperatorNumericSelection() {
        String[] mccmncs = getOperatorNumeric();
        String where;
        where = (mccmncs[0] != null) ? "numeric=\"" + mccmncs[0] + "\"" : "";
        where += (mccmncs[1] != null) ? " or numeric=\"" + mccmncs[1] + "\"" : "";
        Log.d("SettingsActivity", "getOperatorNumericSelection: " + where);
        return where;
    }

    private String[] getOperatorNumeric() {
        ArrayList<String> result = new ArrayList<String>();
        String mccMncFromSim = null;
        if (mUseNvOperatorForEhrpd) {
            String mccMncForEhrpd = SystemProperties.get("ro.cdma.home.operator.numeric", null);
            if (mccMncForEhrpd != null && mccMncForEhrpd.length() > 0) {
                result.add(mccMncForEhrpd);
            }
        }

        mccMncFromSim = TelephonyManager.getDefault().getSimOperator();

        if (mccMncFromSim != null && mccMncFromSim.length() > 0) {
            result.add(mccMncFromSim);
        }
        return result.toArray(new String[2]);
    }

    private void enableRtpPortSetting() {
        final String rtpMinPortStr = mPref.getString(PREFERENCE_RTP_MINPORT, Integer.toString(DEFAULT_RTP_MINPORT));
        final String rtpMaxPortStr = mPref.getString(PREFERENCE_RTP_MAXPORT, Integer.toString(DEFAULT_RTP_MAXPORT));
        final int rtpMinPort;
        final int rtpMaxPort;
        try {
            rtpMinPort = Integer.valueOf(rtpMinPortStr);
            rtpMaxPort = Integer.valueOf(rtpMaxPortStr);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, "Failed to parse rtp ports");
            return;
        }
        Intent intent = new Intent();
        intent.setClassName("com.android.settings", "com.android.settings.StreamingSettingsEnablerActivity");
        intent.putExtra(PREFERENCE_RTP_MINPORT, rtpMinPort);
        intent.putExtra(PREFERENCE_RTP_MAXPORT, rtpMaxPort);
        startActivity(intent);
    }

    private void enableBufferSetting() {
        final String bufferSizeStr = mPref.getString(PREFERENCE_BUFFER_SIZE, Integer.toString(DEFAULT_CACHE_MAX_SIZE));
        final int cacheMaxSize;
        try {
            cacheMaxSize = Integer.valueOf(bufferSizeStr);
        } catch (NumberFormatException e) {
            Log.e(LOG_TAG, "Failed to parse cache max size");
            return;
        }
        Intent intent = new Intent();
        intent.setClassName("com.android.settings", "com.android.settings.StreamingSettingsEnablerActivity");
        intent.putExtra(PREFERENCE_CACHE_MIN_SIZE, DEFAULT_CACHE_MIN_SIZE);
        intent.putExtra(PREFERENCE_CACHE_MAX_SIZE, cacheMaxSize);
        intent.putExtra(PREFERENCE_KEEP_ALIVE_INTERVAL_SECOND, DEFAULT_KEEP_ALIVE_INTERVAL_SECOND);
        startActivity(intent);
    }

}
