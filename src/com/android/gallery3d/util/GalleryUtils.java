/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.gallery3d.util;

import android.annotation.TargetApi;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.net.Uri;
import android.os.ConditionVariable;
import android.os.Environment;
import android.os.StatFs;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.android.gallery3d.R;
import com.android.gallery3d.app.Gallery;
import com.android.gallery3d.app.PackagesMonitor;
import com.android.gallery3d.common.ApiHelper;
import com.android.gallery3d.data.DataManager;
import com.android.gallery3d.data.MediaItem;
import com.android.gallery3d.ui.TiledScreenNail;
import com.android.gallery3d.util.ThreadPool.CancelListener;
import com.android.gallery3d.util.ThreadPool.JobContext;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import java.io.UnsupportedEncodingException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.util.Calendar;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.view.Menu;
import android.view.MenuItem;

public class GalleryUtils {
    private static final String TAG = "GalleryUtils";
    private static final String MAPS_PACKAGE_NAME = "com.google.android.apps.maps";
    private static final String MAPS_CLASS_NAME = "com.google.android.maps.MapsActivity";
    private static final String CAMERA_LAUNCHER_NAME = "com.android.camera.CameraLauncher";

    public static final String MIME_TYPE_IMAGE = "image/*";
    public static final String MIME_TYPE_VIDEO = "video/*";
    public static final String MIME_TYPE_PANORAMA360 = "application/vnd.google.panorama360+jpg";
    public static final String MIME_TYPE_ALL = "*/*";

    private static final String DIR_TYPE_IMAGE = "vnd.android.cursor.dir/image";
    private static final String DIR_TYPE_VIDEO = "vnd.android.cursor.dir/video";

    private static final String PREFIX_PHOTO_EDITOR_UPDATE = "editor-update-";
    private static final String PREFIX_HAS_PHOTO_EDITOR = "has-editor-";

    private static final String KEY_CAMERA_UPDATE = "camera-update";
    private static final String KEY_HAS_CAMERA = "has-camera";

    private static float sPixelDensity = -1f;
    private static boolean sCameraAvailableInitialized = false;
    private static boolean sCameraAvailable;

    public static void initialize(Context context) {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager)
                context.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getMetrics(metrics);
        sPixelDensity = metrics.density;
        Resources r = context.getResources();
        TiledScreenNail.setPlaceholderColor(r.getColor(
                R.color.bitmap_screennail_placeholder));
        initializeThumbnailSizes(metrics, r);
    }

    private static void initializeThumbnailSizes(DisplayMetrics metrics, Resources r) {
        int maxPixels = Math.max(metrics.heightPixels, metrics.widthPixels);

        // For screen-nails, we never need to completely fill the screen
        MediaItem.setThumbnailSizes(maxPixels / 2, maxPixels / 5);
        TiledScreenNail.setMaxSide(maxPixels / 2);
    }

    public static float[] intColorToFloatARGBArray(int from) {
        return new float[] {
            Color.alpha(from) / 255f,
            Color.red(from) / 255f,
            Color.green(from) / 255f,
            Color.blue(from) / 255f
        };
    }

    public static float dpToPixel(float dp) {
        return sPixelDensity * dp;
    }

    public static int dpToPixel(int dp) {
        return Math.round(dpToPixel((float) dp));
    }

    public static int meterToPixel(float meter) {
        // 1 meter = 39.37 inches, 1 inch = 160 dp.
        return Math.round(dpToPixel(meter * 39.37f * 160));
    }

    public static byte[] getBytes(String in) {
        byte[] result = new byte[in.length() * 2];
        int output = 0;
        for (char ch : in.toCharArray()) {
            result[output++] = (byte) (ch & 0xFF);
            result[output++] = (byte) (ch >> 8);
        }
        return result;
    }

    // Below are used the detect using database in the render thread. It only
    // works most of the time, but that's ok because it's for debugging only.

    private static volatile Thread sCurrentThread;
    private static volatile boolean sWarned;

    public static void setRenderThread() {
        sCurrentThread = Thread.currentThread();
    }

    public static void assertNotInRenderThread() {
        if (!sWarned) {
            if (Thread.currentThread() == sCurrentThread) {
                sWarned = true;
                Log.w(TAG, new Throwable("Should not do this in render thread"));
            }
        }
    }

    private static final double RAD_PER_DEG = Math.PI / 180.0;
    private static final double EARTH_RADIUS_METERS = 6367000.0;

    public static double fastDistanceMeters(double latRad1, double lngRad1,
            double latRad2, double lngRad2) {
       if ((Math.abs(latRad1 - latRad2) > RAD_PER_DEG)
             || (Math.abs(lngRad1 - lngRad2) > RAD_PER_DEG)) {
           return accurateDistanceMeters(latRad1, lngRad1, latRad2, lngRad2);
       }
       // Approximate sin(x) = x.
       double sineLat = (latRad1 - latRad2);

       // Approximate sin(x) = x.
       double sineLng = (lngRad1 - lngRad2);

       // Approximate cos(lat1) * cos(lat2) using
       // cos((lat1 + lat2)/2) ^ 2
       double cosTerms = Math.cos((latRad1 + latRad2) / 2.0);
       cosTerms = cosTerms * cosTerms;
       double trigTerm = sineLat * sineLat + cosTerms * sineLng * sineLng;
       trigTerm = Math.sqrt(trigTerm);

       // Approximate arcsin(x) = x
       return EARTH_RADIUS_METERS * trigTerm;
    }

    public static double accurateDistanceMeters(double lat1, double lng1,
            double lat2, double lng2) {
        double dlat = Math.sin(0.5 * (lat2 - lat1));
        double dlng = Math.sin(0.5 * (lng2 - lng1));
        double x = dlat * dlat + dlng * dlng * Math.cos(lat1) * Math.cos(lat2);
        return (2 * Math.atan2(Math.sqrt(x), Math.sqrt(Math.max(0.0,
                1.0 - x)))) * EARTH_RADIUS_METERS;
    }


    public static final double toMile(double meter) {
        return meter / 1609;
    }

    // For debugging, it will block the caller for timeout millis.
    public static void fakeBusy(JobContext jc, int timeout) {
        final ConditionVariable cv = new ConditionVariable();
        jc.setCancelListener(new CancelListener() {
            @Override
            public void onCancel() {
                cv.open();
            }
        });
        cv.block(timeout);
        jc.setCancelListener(null);
    }

    public static boolean isEditorAvailable(Context context, String mimeType) {
        int version = PackagesMonitor.getPackagesVersion(context);

        String updateKey = PREFIX_PHOTO_EDITOR_UPDATE + mimeType;
        String hasKey = PREFIX_HAS_PHOTO_EDITOR + mimeType;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.getInt(updateKey, 0) != version) {
            PackageManager packageManager = context.getPackageManager();
            List<ResolveInfo> infos = packageManager.queryIntentActivities(
                    new Intent(Intent.ACTION_EDIT).setType(mimeType), 0);
            prefs.edit().putInt(updateKey, version)
                        .putBoolean(hasKey, !infos.isEmpty())
                        .commit();
        }

        return prefs.getBoolean(hasKey, true);
    }

    public static boolean isAnyCameraAvailable(Context context) {
        int version = PackagesMonitor.getPackagesVersion(context);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.getInt(KEY_CAMERA_UPDATE, 0) != version) {
            PackageManager packageManager = context.getPackageManager();
            List<ResolveInfo> infos = packageManager.queryIntentActivities(
                    new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), 0);
            prefs.edit().putInt(KEY_CAMERA_UPDATE, version)
                        .putBoolean(KEY_HAS_CAMERA, !infos.isEmpty())
                        .commit();
        }
        return prefs.getBoolean(KEY_HAS_CAMERA, true);
    }

    public static boolean isCameraAvailable(Context context) {
        if (sCameraAvailableInitialized) return sCameraAvailable;
        PackageManager pm = context.getPackageManager();
        ComponentName name = new ComponentName(context, CAMERA_LAUNCHER_NAME);
        int state = pm.getComponentEnabledSetting(name);
        sCameraAvailableInitialized = true;
        sCameraAvailable =
            (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
             || (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        return sCameraAvailable;
    }

    public static void startCameraActivity(Context context) {
        Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static void startGalleryActivity(Context context) {
        Intent intent = new Intent(context, Gallery.class)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    public static boolean isValidLocation(double latitude, double longitude) {
        // TODO: change || to && after we fix the default location issue
        return (latitude != MediaItem.INVALID_LATLNG || longitude != MediaItem.INVALID_LATLNG);
    }

    public static String formatLatitudeLongitude(String format, double latitude,
            double longitude) {
        // We need to specify the locale otherwise it may go wrong in some language
        // (e.g. Locale.FRENCH)
        return String.format(Locale.ENGLISH, format, latitude, longitude);
    }

    public static void showOnMap(Context context, double latitude, double longitude) {
        try {
            // We don't use "geo:latitude,longitude" because it only centers
            // the MapView to the specified location, but we need a marker
            // for further operations (routing to/from).
            // The q=(lat, lng) syntax is suggested by geo-team.
            String uri = formatLatitudeLongitude("http://maps.google.com/maps?f=q&q=(%f,%f)",
                    latitude, longitude);
            ComponentName compName = new ComponentName(MAPS_PACKAGE_NAME,
                    MAPS_CLASS_NAME);
            Intent mapsIntent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse(uri)).setComponent(compName);
            context.startActivity(mapsIntent);
        } catch (ActivityNotFoundException e) {
            // Use the "geo intent" if no GMM is installed
            Log.e(TAG, "GMM activity not found!", e);
            String url = formatLatitudeLongitude("geo:%f,%f", latitude, longitude);
            Intent mapsIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            context.startActivity(mapsIntent);
        }
    }

    public static void setViewPointMatrix(
            float matrix[], float x, float y, float z) {
        // The matrix is
        // -z,  0,  x,  0
        //  0, -z,  y,  0
        //  0,  0,  1,  0
        //  0,  0,  1, -z
        Arrays.fill(matrix, 0, 16, 0);
        matrix[0] = matrix[5] = matrix[15] = -z;
        matrix[8] = x;
        matrix[9] = y;
        matrix[10] = matrix[11] = 1;
    }

    public static int getBucketId(String path) {
        return path.toLowerCase().hashCode();
    }

    // Return the local path that matches the given bucketId. If no match is
    // found, return null
    public static String searchDirForPath(File dir, int bucketId) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    String path = file.getAbsolutePath();
                    if (GalleryUtils.getBucketId(path) == bucketId) {
                        return path;
                    } else {
                        path = searchDirForPath(file, bucketId);
                        if (path != null) return path;
                    }
                }
            }
        }
        return null;
    }

    // Returns a (localized) string for the given duration (in seconds).
    public static String formatDuration(final Context context, int duration) {
        int h = duration / 3600;
        int m = (duration - h * 3600) / 60;
        int s = duration - (h * 3600 + m * 60);
        String durationValue;
        if (h == 0) {
            durationValue = String.format(context.getString(R.string.details_ms), m, s);
        } else {
            durationValue = String.format(context.getString(R.string.details_hms), h, m, s);
        }
        return durationValue;
    }

    @TargetApi(ApiHelper.VERSION_CODES.HONEYCOMB)
    public static int determineTypeBits(Context context, Intent intent) {
        int typeBits = 0;
        String type = intent.resolveType(context);

        if (MIME_TYPE_ALL.equals(type)) {
            typeBits = DataManager.INCLUDE_ALL;
        } else if (MIME_TYPE_IMAGE.equals(type) ||
                DIR_TYPE_IMAGE.equals(type)) {
            typeBits = DataManager.INCLUDE_IMAGE;
        } else if (MIME_TYPE_VIDEO.equals(type) ||
                DIR_TYPE_VIDEO.equals(type)) {
            typeBits = DataManager.INCLUDE_VIDEO;
        } else {
            typeBits = DataManager.INCLUDE_ALL;
        }

        if (ApiHelper.HAS_INTENT_EXTRA_LOCAL_ONLY) {
            if (intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false)) {
                typeBits |= DataManager.INCLUDE_LOCAL_ONLY;
            }
        }

        return typeBits;
    }

    public static int getSelectionModePrompt(int typeBits) {
        if ((typeBits & DataManager.INCLUDE_VIDEO) != 0) {
            return (typeBits & DataManager.INCLUDE_IMAGE) == 0
                    ? R.string.select_video
                    : R.string.select_item;
        }
        return R.string.select_image;
    }

    public static boolean hasSpaceForSize(long size) {
        String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            return false;
        }

        String path = Environment.getExternalStorageDirectory().getPath();
        try {
            StatFs stat = new StatFs(path);
            return stat.getAvailableBlocks() * (long) stat.getBlockSize() > size;
        } catch (Exception e) {
            Log.i(TAG, "Fail to access external storage", e);
        }
        return false;
    }

    public static boolean isPanorama(MediaItem item) {
        if (item == null) return false;
        int w = item.getWidth();
        int h = item.getHeight();
        return (h > 0 && w / h >= 2);
    }

    // Add for Baidu xCloud Feature
    public static class AESUtils {

        private final static String PWD = "qrd@baidu";

        private final static long DEFAULT_TIMESTAMP = 1351077888044L;

        private static AESUtils instance;

        public static AESUtils getInstance() {
            if (instance == null) {
                instance = new AESUtils();
            }
            return instance;
        }

        private AESUtils() {
        }

        private byte[] hex2Byte(String hex) {
            if (hex.length() < 1) {
                return null;
            }
            byte[] r = new byte[hex.length() / 2];
            for (int i = 0; i < hex.length() / 2; i++) {
                int h = Integer.parseInt(hex.substring(i * 2, i * 2 + 1), 16);
                int l = Integer.parseInt(hex.substring(i * 2 + 1, i * 2 + 2),
                        16);
                r[i] = (byte) (h * 16 + l);
            }
            return r;
        }

        private String passGen(long timestamp) {
            return PWD + timestamp;
        }

        private String byte2Hex(byte buf[]) {
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < buf.length; i++) {
                String hex = Integer.toHexString(buf[i] & 0xFF);
                if (hex.length() == 1) {
                    hex = '0' + hex;
                }
                sb.append(hex.toUpperCase());
            }
            return sb.toString();
        }

        public String encrypt(String content) {
            return encrypt(content, DEFAULT_TIMESTAMP);
        }

        public String encrypt(String content, long timestamp) {
            if (timestamp == 0) {
                throw new RuntimeException("timestamp can't be null");
            }
            try {
                KeyGenerator kgen = KeyGenerator.getInstance("AES");
                SecureRandom sr = SecureRandom.getInstance( "SHA1PRNG", "Crypto" );
                sr.setSeed(passGen(timestamp).getBytes());
                kgen.init(128, sr);
                SecretKey secretKey = kgen.generateKey();
                byte[] enCodeFormat = secretKey.getEncoded();
                SecretKeySpec key = new SecretKeySpec(enCodeFormat, "AES");
                Cipher cipher = Cipher.getInstance("AES");
                byte[] byteContent = content.getBytes("utf-8");
                cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(
                        new byte[cipher.getBlockSize()]));
                byte[] bytes = cipher.doFinal(byteContent);
                String result = byte2Hex(bytes);
                return result;
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            } catch (NoSuchPaddingException e) {
                e.printStackTrace();
            } catch (InvalidKeyException e) {
                e.printStackTrace();
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            } catch (IllegalBlockSizeException e) {
                e.printStackTrace();
            } catch (BadPaddingException e) {
                e.printStackTrace();
            } catch (NoSuchProviderException e) {
                e.printStackTrace();
            } catch (InvalidAlgorithmParameterException e) {
                e.printStackTrace();
            }
            return null;
        }

        public String decrypt(String content) {
            return decrypt(content, DEFAULT_TIMESTAMP);
        }

        public String decrypt(String content, long timestamp) {
            try {
                KeyGenerator kgen = KeyGenerator.getInstance("AES");
                SecureRandom sr = SecureRandom.getInstance( "SHA1PRNG", "Crypto" );
                sr.setSeed(passGen(timestamp).getBytes());
                kgen.init(128, sr);
                SecretKey secretKey = kgen.generateKey();
                byte[] enCodeFormat = secretKey.getEncoded();
                SecretKeySpec key = new SecretKeySpec(enCodeFormat, "AES");
                Cipher cipher = Cipher.getInstance("AES");
                cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(
                        new byte[cipher.getBlockSize()]));
                byte[] bytes = cipher.doFinal(hex2Byte(content));
                String result = new String(bytes, "UTF-8");
                return result;
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            } catch (NoSuchPaddingException e) {
                e.printStackTrace();
            } catch (InvalidKeyException e) {
                e.printStackTrace();
            } catch (IllegalBlockSizeException e) {
                e.printStackTrace();
            } catch (BadPaddingException e) {
                e.printStackTrace();
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            } catch (NoSuchProviderException e) {
                e.printStackTrace();
            } catch (InvalidAlgorithmParameterException e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    public static class XCloudManager {

        private static boolean DBG_XCOULD = true;

        private static String BAIDU_CLOUD_APK_NAME = "com.baidu.netdisk_qualcomm";

        private static Uri sUri = Uri.parse("content://com.baidu.xcloud.content/album");

        private static XCloudManager sInstance = null;
        private static Object sSyncRoot = new Object();
        public static XCloudManager getInstance() {
            if (sInstance == null) {
                synchronized(sSyncRoot) {
                    if (sInstance == null)
                        sInstance = new XCloudManager();
                }
            }
            return sInstance;
        }

        private XCloudManager() {
        }

        private void loge(String message) {
            loge(message, null);
        }

        private void loge(String message, Exception e) {
            if (DBG_XCOULD)
                Log.e("XCLOUD-QC-GALLERY", message, e);
        }

        private boolean isXCloudInstalled(Context context) {
            boolean installed = false;
            try {
                ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                        BAIDU_CLOUD_APK_NAME, PackageManager.GET_PROVIDERS);
                installed = info != null;
            } catch (NameNotFoundException e) {
                installed = false;
            }
            loge("Is xcloud installed ? " + installed);
            return installed;
        }

        private boolean isAutoUploadEnabled(Context context) {
            boolean enabled = false;

            Cursor cursor = context.getContentResolver().query(sUri, null, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToNext()) {
                        String tmp = AESUtils.getInstance()
                                .decrypt(cursor.getString(0));
                        enabled = (tmp != null && tmp.equals("on"));
                    }
                } finally {
                    cursor.close();
                }
            }
            loge("Is auto upload enabled ? " + enabled);
            return enabled;
        }

        private void setAutoUploadEnabled(boolean enabled, Context context) {
            loge("Setting auto upload to " + enabled);
            long timestamp = Calendar.getInstance().getTimeInMillis();
            String isBackup = enabled ? "on" : "off";
            ContentValues cvs = new ContentValues();
            cvs.put("timestamp", timestamp);
            cvs.put(AESUtils.getInstance().encrypt("autoalbum", timestamp),
                    AESUtils.getInstance().encrypt(isBackup, timestamp));
            context.getContentResolver().update(sUri, cvs, null, null);
        }

        public void updateMenuState(Menu menu, Context context) {
            loge("Updating menu state");
            MenuItem uploadSwitcher = menu.findItem(R.id.switch_auto_sync_to_xcloud);
            if (uploadSwitcher != null) {
                if (isXCloudInstalled(context)) {
                    uploadSwitcher.setVisible(true);
                    uploadSwitcher.setChecked(isAutoUploadEnabled(context));
                } else {
                    uploadSwitcher.setVisible(false);
                }
            }
        }

        public boolean handleXCouldRelatedMenuItem(MenuItem item, Context context) {
            switch (item.getItemId()) {
            case R.id.switch_auto_sync_to_xcloud:
                setAutoUploadEnabled(!item.isChecked(), context);
                return true;
            default:
                return false;
            }
        }
    }
}
