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

package com.android.camera;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Images.ImageColumns;
import android.provider.MediaStore.MediaColumns;
import android.util.Log;

import com.android.gallery3d.common.ApiHelper;
import com.android.gallery3d.exif.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;

public class Storage {
    private static final String TAG = "CameraStorage";

    public static final String DCIM =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).toString();

    public static final String DIRECTORY = DCIM + "/Camera";
    public static final String RAW_DIRECTORY = DCIM + "/Camera/raw";

    // Match the code in MediaProvider.computeBucketValues().
    public static final String BUCKET_ID =
            String.valueOf(DIRECTORY.toLowerCase().hashCode());
    public static final long UNAVAILABLE = -1L;
    public static final long PREPARING = -2L;
    public static final long UNKNOWN_SIZE = -3L;
    public static final long LOW_STORAGE_THRESHOLD = 50000000;

    private static boolean sSaveSDCard = false;

    public static boolean isSaveSDCard() {
        return sSaveSDCard;
    }

    public static void setSaveSDCard(boolean saveSDCard) {
        sSaveSDCard = saveSDCard;
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private static void setImageSize(ContentValues values, int width, int height) {
        // The two fields are available since ICS but got published in JB
        if (ApiHelper.HAS_MEDIA_COLUMNS_WIDTH_AND_HEIGHT) {
            values.put(MediaColumns.WIDTH, width);
            values.put(MediaColumns.HEIGHT, height);
        }
    }

    public static void writeFile(String path, byte[] data) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(path);
            out.write(data);
        } catch (Exception e) {
            Log.e(TAG, "Failed to write data", e);
        } finally {
            try {
                out.close();
            } catch (Exception e) {
            }
        }
    }

    // Save the image and add it to media store.
    public static Uri addImage(ContentResolver resolver, String title,
            long date, Location location, int orientation, ExifInterface exif,
            byte[] jpeg, int width, int height, String pictureFormat) {
        int jpegLength = 0;

        if ( jpeg != null ) {
            jpegLength = jpeg.length;
        }

        // Save the image.
        String path = generateFilepath(title, pictureFormat);
        if (exif != null && (pictureFormat == null ||
            pictureFormat.equalsIgnoreCase("jpeg"))) {
            try {
                exif.writeExif(jpeg, path);
            } catch (Exception e) {
                Log.e(TAG, "Failed to write data", e);
            }
        } else if (jpeg != null) {
            if (!(pictureFormat.equalsIgnoreCase("jpeg") || pictureFormat == null)) {
                 String folder = null;
                 if (isSaveSDCard() && SDCard.instance().isWriteable()) {
                     folder = SDCard.instance().getRawDirectory();
                 } else {
                     folder = RAW_DIRECTORY;
                 }
                 File dir = new File(folder);
                 dir.mkdirs();
            }
            writeFile(path, jpeg);
        }
        return addImage(resolver, title, date, location, orientation,
                jpegLength, path, width, height, pictureFormat);
    }

    // Add the image to media store.
    public static Uri addImage(ContentResolver resolver, String title,
            long date, Location location, int orientation, int jpegLength,
            String path, int width, int height, String pictureFormat) {
        // Insert into MediaStore.
        ContentValues values = new ContentValues(9);
        values.put(ImageColumns.TITLE, title);
        if (pictureFormat.equalsIgnoreCase("jpeg") || pictureFormat == null) {
            values.put(ImageColumns.DISPLAY_NAME, title + ".jpg");
        } else {
            values.put(ImageColumns.DISPLAY_NAME, title + ".raw");
        }
        values.put(ImageColumns.DATE_TAKEN, date);
        values.put(ImageColumns.MIME_TYPE, "image/jpeg");
        // Clockwise rotation in degrees. 0, 90, 180, or 270.
        values.put(ImageColumns.ORIENTATION, orientation);
        values.put(ImageColumns.DATA, path);
        values.put(ImageColumns.SIZE, jpegLength);

        setImageSize(values, width, height);

        if (location != null) {
            values.put(ImageColumns.LATITUDE, location.getLatitude());
            values.put(ImageColumns.LONGITUDE, location.getLongitude());
        }

        Uri uri = null;
        try {
            uri = resolver.insert(Images.Media.EXTERNAL_CONTENT_URI, values);
        } catch (Throwable th)  {
            // This can happen when the external volume is already mounted, but
            // MediaScanner has not notify MediaProvider to add that volume.
            // The picture is still safe and MediaScanner will find it and
            // insert it into MediaProvider. The only problem is that the user
            // cannot click the thumbnail to review the picture.
            Log.e(TAG, "Failed to write MediaStore" + th);
        }
        return uri;
    }

    public static void deleteImage(ContentResolver resolver, Uri uri) {
        try {
            resolver.delete(uri, null, null);
        } catch (Throwable th) {
            Log.e(TAG, "Failed to delete image: " + uri);
        }
    }

    public static String generateFilepath(String title, String pictureFormat) {
        boolean isJpeg = false;
        String extension = null;
        String folder = null;
        if (pictureFormat == null || pictureFormat.equalsIgnoreCase("jpeg")) {
            isJpeg = true;
            extension = ".jpg";
            folder = DIRECTORY;
        } else {
            extension = ".raw";
            folder = RAW_DIRECTORY;
        }
        if (isSaveSDCard() && SDCard.instance().isWriteable()) {
            if (isJpeg) {
                return SDCard.instance().getDirectory() +
                    '/' + title + extension;
            } else {
                return SDCard.instance().getRawDirectory() +
                    '/' + title + extension;
            }
        } else {
            return folder + '/' + title + extension;
        }
    }

    public static long getAvailableSpace() {
        if (isSaveSDCard() && SDCard.instance().isWriteable()) {
            File dir = new File(SDCard.instance().getDirectory());
            dir.mkdirs();
            try {
                StatFs stat = new StatFs(SDCard.instance().getDirectory());
                long ret = stat.getAvailableBlocks() * (long) stat.getBlockSize();
                return ret;
            } catch (Exception e) {
            }
            return UNKNOWN_SIZE;
        } else if (isSaveSDCard() && !SDCard.instance().isWriteable()) {
            return UNKNOWN_SIZE;
        }
        else {
            String state = Environment.getExternalStorageState();
            Log.d(TAG, "External storage state=" + state);
            if (Environment.MEDIA_CHECKING.equals(state)) {
                return PREPARING;
            }
            if (!Environment.MEDIA_MOUNTED.equals(state)) {
                return UNAVAILABLE;
            }

            File dir = new File(DIRECTORY);
            dir.mkdirs();
            if (!dir.isDirectory() || !dir.canWrite()) {
                return UNAVAILABLE;
            }

            try {
                StatFs stat = new StatFs(DIRECTORY);
                return stat.getAvailableBlocks() * (long) stat.getBlockSize();
            } catch (Exception e) {
                Log.i(TAG, "Fail to access external storage", e);
            }
            return UNKNOWN_SIZE;
        }
    }

    /**
     * OSX requires plugged-in USB storage to have path /DCIM/NNNAAAAA to be
     * imported. This is a temporary fix for bug#1655552.
     */
    public static void ensureOSXCompatible() {
        File nnnAAAAA = new File(DCIM, "100ANDRO");
        if (!(nnnAAAAA.exists() || nnnAAAAA.mkdirs())) {
            Log.e(TAG, "Failed to create " + nnnAAAAA.getPath());
        }
    }
}
