/*
 * Copyright (C) 2012 The Android Open Source Project
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
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences.Editor;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.Size;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.media.CameraProfile;
import android.net.Uri;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.camera.CameraManager.CameraProxy;
import com.android.camera.ui.CountDownView.OnCountDownFinishedListener;
import com.android.camera.ui.PopupManager;
import com.android.camera.ui.RotateTextToast;
import com.android.gallery3d.R;
import com.android.gallery3d.common.ApiHelper;
import com.android.gallery3d.exif.ExifInterface;
import com.android.gallery3d.exif.ExifTag;
import com.android.gallery3d.exif.Rational;
import com.android.gallery3d.filtershow.FilterShowActivity;
import com.android.gallery3d.filtershow.crop.CropExtras;
import com.android.gallery3d.util.UsageStatistics;
import com.android.internal.util.MemInfoReader;
import android.app.ActivityManager;
import android.media.SoundPool;
import android.media.AudioManager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Formatter;
import java.util.List;
import java.util.HashMap;
import android.util.AttributeSet;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemProperties;

public class PhotoModule
    implements CameraModule,
    PhotoController,
    FocusOverlayManager.Listener,
    CameraPreference.OnPreferenceChangedListener,
    ShutterButton.OnShutterButtonListener,
    MediaSaveService.Listener,
    OnCountDownFinishedListener,
    SensorEventListener {

    private static final String TAG = "CAM_PhotoModule";

   //QCom data members
    public static boolean mBrightnessVisible = true;
    private static final int MAX_SHARPNESS_LEVEL = 6;
    private boolean mRestartPreview = false;
    private int mSnapshotMode;
    private int mBurstSnapNum = 1;
    private int mReceivedSnapNum = 0;
    public boolean mFaceDetectionEnabled = false;

   /*Histogram variables*/
    private GraphView mGraphView;
    private static final int STATS_DATA = 257;
    public static int statsdata[] = new int[STATS_DATA];
    public boolean mHiston = false;
    // We number the request code from 1000 to avoid collision with Gallery.
    private static final int REQUEST_CROP = 1000;

    private static final int SETUP_PREVIEW = 1;
    private static final int FIRST_TIME_INIT = 2;
    private static final int CLEAR_SCREEN_DELAY = 3;
    private static final int SET_CAMERA_PARAMETERS_WHEN_IDLE = 4;
    private static final int CHECK_DISPLAY_ROTATION = 5;
    private static final int SHOW_TAP_TO_FOCUS_TOAST = 6;
    private static final int SWITCH_CAMERA = 7;
    private static final int SWITCH_CAMERA_START_ANIMATION = 8;
    private static final int CAMERA_OPEN_DONE = 9;
    private static final int START_PREVIEW_DONE = 10;
    private static final int OPEN_CAMERA_FAIL = 11;
    private static final int CAMERA_DISABLED = 12;
    private static final int CAPTURE_ANIMATION_DONE = 13;
    private static final int SET_SKIN_TONE_FACTOR = 14;
    private static final int SET_PHOTO_UI_PARAMS = 15;
    private static final int CONTINUE_LONGSHOT = 17;

    // The subset of parameters we need to update in setCameraParameters().
    private static final int UPDATE_PARAM_INITIALIZE = 1;
    private static final int UPDATE_PARAM_ZOOM = 2;
    private static final int UPDATE_PARAM_PREFERENCE = 4;
    private static final int UPDATE_PARAM_ALL = -1;

    // This is the timeout to keep the camera in onPause for the first time
    // after screen on if the activity is started from secure lock screen.
    private static final int KEEP_CAMERA_TIMEOUT = 1000; // ms

    // Used for check memory status for longshot mode
    private static final int LONGSHOT_SLOWDOWN_THRESHOLD = 40;
    private static final int LONGSHOT_CANCEL_THRESHOLD = 20;
    private static final int LONGSHOT_SLOWDOWN_DELAY = 500; //ms
    private MemInfoReader mMemInfoReader = new MemInfoReader();
    private ActivityManager mAm;
    private long SECONDARY_SERVER_MEM;
    private long mMB = 1024 * 1024;

    //shutter sound
    private SoundPool mSoundPool;
    HashMap<Integer, Integer> mSoundHashMap;
    private static final int LONGSHOT_SOUND_STATUS_OFF = 0;
    private static final int LONGSHOT_SOUND_STATUS_ON  = 1;
    private int mLongshotSoundStatus = LONGSHOT_SOUND_STATUS_OFF;
    private int mSoundStreamID = -1;

    // copied from Camera hierarchy
    private CameraActivity mActivity;
    private CameraProxy mCameraDevice;
    private int mCameraId;
    private Parameters mParameters;
    private boolean mPaused;
    private View mRootView;

    private PhotoUI mUI;

    // these are only used by Camera

    // The activity is going to switch to the specified camera id. This is
    // needed because texture copy is done in GL thread. -1 means camera is not
    // switching.
    protected int mPendingSwitchCameraId = -1;
    private boolean mOpenCameraFail;
    private boolean mCameraDisabled;

    // When setCameraParametersWhenIdle() is called, we accumulate the subsets
    // needed to be updated in mUpdateSet.
    private int mUpdateSet;

    private static final int SCREEN_DELAY = 2 * 60 * 1000;

    private int mZoomValue;  // The current zoom value.

    private Parameters mInitialParams;
    private boolean mFocusAreaSupported;
    private boolean mMeteringAreaSupported;
    private boolean mAeLockSupported;
    private boolean mAwbLockSupported;
    private boolean mContinousFocusSupported;
    private boolean mTouchAfAecFlag;
    private boolean mLongshotSave = false;
    private int mLongshotSetNum = CameraSettings.DEFAULT_LONGSHOT_NUM;
    private int mLongshotReceiveCount = 0;
    private boolean mLongshotActive = false;
    private boolean mLongshotAlwaysOn = false;

    // The degrees of the device rotated clockwise from its natural orientation.
    private int mOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;
    private ComboPreferences mPreferences;
    private boolean mSaveToSDCard = false;

    private static final String sTempCropFilename = "crop-temp";

    private ContentProviderClient mMediaProviderClient;
    private ShutterButton mShutterButton;
    private boolean mFaceDetectionStarted = false;

    private static final String PERSIST_LONG_ENABLE = "persist.camera.longshot.enable";
    private static final String PERSIST_LONG_SAVE = "persist.camera.longshot.save";

    private static final int MINIMUM_BRIGHTNESS = 0;
    private static final int MAXIMUM_BRIGHTNESS = 6;
    private int mbrightness = 3;
    private int mbrightness_step = 1;
    private ProgressBar brightnessProgressBar;
    // Constant from android.hardware.Camera.Parameters
    private static final String KEY_PICTURE_FORMAT = "picture-format";
    private static final String KEY_QC_RAW_PICUTRE_SIZE = "raw-size";
    private static final String PIXEL_FORMAT_JPEG = "jpeg";

    private static final int MIN_SCE_FACTOR = -10;
    private static final int MAX_SCE_FACTOR = +10;
    private int SCE_FACTOR_STEP = 10;
    private int mskinToneValue = 0;
    private boolean mSkinToneSeekBar= false;
    private boolean mSeekBarInitialized = false;
    private SeekBar skinToneSeekBar;
    private TextView LeftValue;
    private TextView RightValue;
    private TextView Title;

    // mCropValue and mSaveUri are used only if isImageCaptureIntent() is true.
    private String mCropValue;
    private Uri mSaveUri;

    // We use a queue to generated names of the images to be used later
    // when the image is ready to be saved.
    private NamedImages mNamedImages;

    private Runnable mDoSnapRunnable = new Runnable() {
        @Override
        public void run() {
            onShutterButtonClick();
        }
    };

    private Runnable mFlashRunnable = new Runnable() {
        @Override
        public void run() {
            animateFlash();
        }
    };

    private final StringBuilder mBuilder = new StringBuilder();
    private final Formatter mFormatter = new Formatter(mBuilder);
    private final Object[] mFormatterArgs = new Object[1];

    /**
     * An unpublished intent flag requesting to return as soon as capturing
     * is completed.
     *
     * TODO: consider publishing by moving into MediaStore.
     */
    private static final String EXTRA_QUICK_CAPTURE =
            "android.intent.extra.quickCapture";

    // The display rotation in degrees. This is only valid when mCameraState is
    // not PREVIEW_STOPPED.
    private int mDisplayRotation;
    // The value for android.hardware.Camera.setDisplayOrientation.
    private int mCameraDisplayOrientation;
    // The value for UI components like indicators.
    private int mDisplayOrientation;
    // The value for android.hardware.Camera.Parameters.setRotation.
    private int mJpegRotation;
    private boolean mFirstTimeInitialized;
    private boolean mIsImageCaptureIntent;

    private int mCameraState = PREVIEW_STOPPED;
    private boolean mSnapshotOnIdle = false;

    private ContentResolver mContentResolver;

    private LocationManager mLocationManager;

    private final PostViewPictureCallback mPostViewPictureCallback =
            new PostViewPictureCallback();
    private final RawPictureCallback mRawPictureCallback =
            new RawPictureCallback();
    private final AutoFocusCallback mAutoFocusCallback =
            new AutoFocusCallback();
    private final Object mAutoFocusMoveCallback =
            ApiHelper.HAS_AUTO_FOCUS_MOVE_CALLBACK
            ? new AutoFocusMoveCallback()
            : null;

    private final CameraErrorCallback mErrorCallback = new CameraErrorCallback();
    private final StatsCallback mStatsCallback = new StatsCallback();

    private long mFocusStartTime;
    private long mShutterCallbackTime;
    private long mPostViewPictureCallbackTime;
    private long mRawPictureCallbackTime;
    private long mJpegPictureCallbackTime;
    private long mOnResumeTime;
    private byte[] mJpegImageData;

    // These latency time are for the CameraLatency test.
    public long mAutoFocusTime;
    public long mShutterLag;
    public long mShutterToPictureDisplayedTime;
    public long mPictureDisplayedToJpegCallbackTime;
    public long mJpegCallbackFinishTime;
    public long mCaptureStartTime;

    // This handles everything about focus.
    private FocusOverlayManager mFocusManager;

    private String mSceneMode;
    private String mCurrTouchAfAec = Parameters.TOUCH_AF_AEC_ON;
    private String mCurrFlashMode = Parameters.FLASH_MODE_AUTO;

    private final Handler mHandler = new MainHandler();
    private PreferenceGroup mPreferenceGroup;

    private boolean mQuickCapture;

    CameraStartUpThread mCameraStartUpThread;
    ConditionVariable mStartPreviewPrerequisiteReady = new ConditionVariable();

    private SensorManager mSensorManager;
    private float[] mGData = new float[3];
    private float[] mMData = new float[3];
    private float[] mR = new float[16];
    private int mHeading = -1;

    private MediaSaveService.OnMediaSavedListener mOnMediaSavedListener =
            new MediaSaveService.OnMediaSavedListener() {
                @Override
                public void onMediaSaved(Uri uri) {
                    if (uri != null) {
                        mActivity.addSecureAlbumItemIfNeeded(false, uri);
                        Util.broadcastNewPicture(mActivity, uri);
                    }
                }
            };

    // The purpose is not to block the main thread in onCreate and onResume.
    private class CameraStartUpThread extends Thread {
        private volatile boolean mCancelled;

        public void cancel() {
            mCancelled = true;
            interrupt();
        }

        public boolean isCanceled() {
            return mCancelled;
        }

        @Override
        public void run() {
            try {
                // We need to check whether the activity is paused before long
                // operations to ensure that onPause() can be done ASAP.
                if (mCancelled) return;
                mCameraDevice = Util.openCamera(mActivity, mCameraId);
                mParameters = mCameraDevice.getParameters();
                // Wait until all the initialization needed by startPreview are
                // done.
                mStartPreviewPrerequisiteReady.block();

                initializeCapabilities();
                if (mFocusManager == null) initializeFocusManager();
                if (mCancelled) return;
                setCameraParameters(UPDATE_PARAM_ALL);
                mHandler.sendEmptyMessage(CAMERA_OPEN_DONE);
                if (mCancelled) return;
                startPreview();
                mHandler.sendEmptyMessage(START_PREVIEW_DONE);
                mOnResumeTime = SystemClock.uptimeMillis();
                mHandler.sendEmptyMessage(CHECK_DISPLAY_ROTATION);
            } catch (CameraHardwareException e) {
                mHandler.sendEmptyMessage(OPEN_CAMERA_FAIL);
            } catch (CameraDisabledException e) {
                mHandler.sendEmptyMessage(CAMERA_DISABLED);
            }
        }
    }

    /**
     * This Handler is used to post message back onto the main thread of the
     * application
     */
    private class MainHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SETUP_PREVIEW: {
                    setupPreview();
                    break;
                }

                case CLEAR_SCREEN_DELAY: {
                    mActivity.getWindow().clearFlags(
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    break;
                }

                case FIRST_TIME_INIT: {
                    initializeFirstTime();
                    break;
                }

                case SET_CAMERA_PARAMETERS_WHEN_IDLE: {
                    setCameraParametersWhenIdle(0);
                    break;
                }

                case CHECK_DISPLAY_ROTATION: {
                    // Set the display orientation if display rotation has changed.
                    // Sometimes this happens when the device is held upside
                    // down and camera app is opened. Rotation animation will
                    // take some time and the rotation value we have got may be
                    // wrong. Framework does not have a callback for this now.
                    if (Util.getDisplayRotation(mActivity) != mDisplayRotation) {
                        setDisplayOrientation();
                    }
                    if (SystemClock.uptimeMillis() - mOnResumeTime < 5000) {
                        mHandler.sendEmptyMessageDelayed(CHECK_DISPLAY_ROTATION, 100);
                    }
                    break;
                }

                case SHOW_TAP_TO_FOCUS_TOAST: {
                    showTapToFocusToast();
                    break;
                }

                case SWITCH_CAMERA: {
                    switchCamera();
                    break;
                }

                case SWITCH_CAMERA_START_ANIMATION: {
                    ((CameraScreenNail) mActivity.mCameraScreenNail).animateSwitchCamera();
                    break;
                }

                case CAMERA_OPEN_DONE: {
                    onCameraOpened();
                    break;
                }

                case START_PREVIEW_DONE: {
                    onPreviewStarted();
                    break;
                }

                case OPEN_CAMERA_FAIL: {
                    mCameraStartUpThread = null;
                    mOpenCameraFail = true;
                    Util.showErrorAndFinish(mActivity,
                            R.string.cannot_connect_camera);
                    break;
                }

                case CAMERA_DISABLED: {
                    mCameraStartUpThread = null;
                    mCameraDisabled = true;
                    Util.showErrorAndFinish(mActivity,
                            R.string.camera_disabled);
                    break;
                }
                case CAPTURE_ANIMATION_DONE: {
                    mUI.enablePreviewThumb(false);
                    break;
                }
               case SET_SKIN_TONE_FACTOR: {
                    Log.v(TAG, "set tone bar: mSceneMode = " + mSceneMode);
                    setSkinToneFactor();
                    mSeekBarInitialized = true;
                    // skin tone ie enabled only for party and portrait BSM
                    // when color effects are not enabled
                    String colorEffect = mPreferences.getString(
                        CameraSettings.KEY_COLOR_EFFECT,
                        mActivity.getString(R.string.pref_camera_coloreffect_default));
                    if((Parameters.SCENE_MODE_PARTY.equals(mSceneMode) ||
                        Parameters.SCENE_MODE_PORTRAIT.equals(mSceneMode))&&
                        (Parameters.EFFECT_NONE.equals(colorEffect))) {
                        ;
                    }
                    else{
                        Log.v(TAG, "Skin tone bar: disable");
                        disableSkinToneSeekBar();
                    }
                    break;
               }
               case SET_PHOTO_UI_PARAMS: {
                    setCameraParametersWhenIdle(UPDATE_PARAM_PREFERENCE);
                    resizeForPreviewAspectRatio();
                    mUI.updateOnScreenIndicators(mParameters, mPreferenceGroup,
                        mPreferences);
                    break;
               }
               case CONTINUE_LONGSHOT: {
                    if(mCameraState == LONGSHOT &&
                        mLongshotActive ){
                        continueLongshot();
                    }
               }
            }
        }
    }

    @Override
    public void init(CameraActivity activity, View parent, boolean reuseNail) {
        mActivity = activity;
        mRootView = parent;
        mUI = new PhotoUI(activity, this, parent);
        mPreferences = new ComboPreferences(mActivity);
        CameraSettings.upgradeGlobalPreferences(mPreferences.getGlobal());
        mCameraId = getPreferredCameraId(mPreferences);

        mContentResolver = mActivity.getContentResolver();

        mAm = (ActivityManager)(mActivity.getSystemService(Context.ACTIVITY_SERVICE));

        // To reduce startup time, open the camera and start the preview in
        // another thread.
        mCameraStartUpThread = new CameraStartUpThread();
        mCameraStartUpThread.start();


        // Surface texture is from camera screen nail and startPreview needs it.
        // This must be done before startPreview.
        mIsImageCaptureIntent = isImageCaptureIntent();
        if (reuseNail) {
            mActivity.reuseCameraScreenNail(!mIsImageCaptureIntent);
        } else {
            mActivity.createCameraScreenNail(!mIsImageCaptureIntent);
        }

        mPreferences.setLocalId(mActivity, mCameraId);
        CameraSettings.upgradeLocalPreferences(mPreferences.getLocal());
        // we need to reset exposure for the preview
        resetExposureCompensation();
        // Starting the preview needs preferences, camera screen nail, and
        // focus area indicator.
        mStartPreviewPrerequisiteReady.open();

        initializeControlByIntent();
        mQuickCapture = mActivity.getIntent().getBooleanExtra(EXTRA_QUICK_CAPTURE, false);
        mLocationManager = new LocationManager(mActivity, mUI);
        mSensorManager = (SensorManager)(mActivity.getSystemService(Context.SENSOR_SERVICE));

        brightnessProgressBar = (ProgressBar)mRootView.findViewById(R.id.progress);
        if (brightnessProgressBar instanceof SeekBar) {
            SeekBar seeker = (SeekBar) brightnessProgressBar;
            seeker.setOnSeekBarChangeListener(mSeekListener);
        }
        brightnessProgressBar.setMax(MAXIMUM_BRIGHTNESS);
        brightnessProgressBar.setProgress(mbrightness);
        skinToneSeekBar = (SeekBar) mRootView.findViewById(R.id.skintoneseek);
        skinToneSeekBar.setOnSeekBarChangeListener(mskinToneSeekListener);
        skinToneSeekBar.setVisibility(View.INVISIBLE);
        Title = (TextView)mRootView.findViewById(R.id.skintonetitle);
        RightValue = (TextView)mRootView.findViewById(R.id.skintoneright);
        LeftValue = (TextView)mRootView.findViewById(R.id.skintoneleft);

        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        mAm.getMemoryInfo(memInfo);
        SECONDARY_SERVER_MEM = memInfo.secondaryServerThreshold;

        initSoundPool();

        Storage.setSaveSDCard(
            mPreferences.getString(CameraSettings.KEY_CAMERA_SAVEPATH, "0").equals("1"));
        mSaveToSDCard = Storage.isSaveSDCard();
    }

    private void initializeControlByIntent() {
        mUI.initializeControlByIntent();
        if (mIsImageCaptureIntent) {
            setupCaptureParams();
        }
    }

    private void onPreviewStarted() {
        mCameraStartUpThread = null;
        setCameraState(IDLE);
        if (!ApiHelper.HAS_SURFACE_TEXTURE) {
            // This may happen if surfaceCreated has arrived.
            mCameraDevice.setPreviewDisplayAsync(mUI.getSurfaceHolder());
        }
        startFaceDetection();
        locationFirstRun();
    }

    // Prompt the user to pick to record location for the very first run of
    // camera only
    private void locationFirstRun() {
        if (RecordLocationPreference.isSet(mPreferences)) {
            return;
        }
        if (mActivity.isSecureCamera()) return;
        // Check if the back camera exists
        int backCameraId = CameraHolder.instance().getBackCameraId();
        if (backCameraId == -1) {
            // If there is no back camera, do not show the prompt.
            return;
        }

        new AlertDialog.Builder(mActivity)
            .setTitle(R.string.remember_location_title)
            .setMessage(R.string.remember_location_prompt)
            .setPositiveButton(R.string.remember_location_yes, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int arg1) {
                    setLocationPreference(RecordLocationPreference.VALUE_ON);
                }
            })
            .setNegativeButton(R.string.remember_location_no, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int arg1) {
                    dialog.cancel();
                }
            })
            .setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    setLocationPreference(RecordLocationPreference.VALUE_OFF);
                }
            })
            .show();
    }

    private void setLocationPreference(String value) {
        mPreferences.edit()
            .putString(CameraSettings.KEY_RECORD_LOCATION, value)
            .apply();
        // TODO: Fix this to use the actual onSharedPreferencesChanged listener
        // instead of invoking manually
        onSharedPreferenceChanged();
    }

    private void onCameraOpened() {
        View root = mUI.getRootView();
        // These depend on camera parameters.

        int width = mUI.mPreviewFrameLayout.getWidth();
        int height = mUI.mPreviewFrameLayout.getHeight();
        openCameraCommon();
        resizeForPreviewAspectRatio();
        mFocusManager.setPreviewSize(width, height);
        // Full-screen screennail
        if (Util.getDisplayRotation(mActivity) % 180 == 0) {
            ((CameraScreenNail) mActivity.mCameraScreenNail).setPreviewFrameLayoutSize(width, height);
        } else {
            ((CameraScreenNail) mActivity.mCameraScreenNail).setPreviewFrameLayoutSize(height, width);
        }
        // Set touch focus listener.
        mActivity.setSingleTapUpListener(root);
        onFullScreenChanged(mActivity.isInCameraApp());
    }

    private void switchCamera() {
        if (mPaused) return;

        Log.v(TAG, "Start to switch camera. id=" + mPendingSwitchCameraId);
        mCameraId = mPendingSwitchCameraId;
        mPendingSwitchCameraId = -1;
        setCameraId(mCameraId);

        // from onPause
        closeCamera();
        mUI.collapseCameraControls();
        mUI.clearFaces();
        if (mFocusManager != null) mFocusManager.removeMessages();

        // Restart the camera and initialize the UI. From onCreate.
        mPreferences.setLocalId(mActivity, mCameraId);
        CameraSettings.upgradeLocalPreferences(mPreferences.getLocal());
        try {
            mCameraDevice = Util.openCamera(mActivity, mCameraId);
            mParameters = mCameraDevice.getParameters();
        } catch (CameraHardwareException e) {
            Util.showErrorAndFinish(mActivity, R.string.cannot_connect_camera);
            return;
        } catch (CameraDisabledException e) {
            Util.showErrorAndFinish(mActivity, R.string.camera_disabled);
            return;
        }
        initializeCapabilities();
        CameraInfo info = CameraHolder.instance().getCameraInfo()[mCameraId];
        boolean mirror = (info.facing == CameraInfo.CAMERA_FACING_FRONT);
        mFocusManager.setMirror(mirror);
        mFocusManager.setParameters(mInitialParams);
        setupPreview();
        resizeForPreviewAspectRatio();

        openCameraCommon();

        if (ApiHelper.HAS_SURFACE_TEXTURE) {
            // Start switch camera animation. Post a message because
            // onFrameAvailable from the old camera may already exist.
            mHandler.sendEmptyMessage(SWITCH_CAMERA_START_ANIMATION);
        }
    }

    protected void setCameraId(int cameraId) {
        ListPreference pref = mPreferenceGroup.findPreference(CameraSettings.KEY_CAMERA_ID);
        pref.setValue("" + cameraId);
    }

    // either open a new camera or switch cameras
    private void openCameraCommon() {
        loadCameraPreferences();

        mUI.onCameraOpened(mPreferenceGroup, mPreferences, mParameters, this);
        updateSceneMode();
        updateHdrMode();
        showTapToFocusToastIfNeeded();


    }

    public void onScreenSizeChanged(int width, int height, int previewWidth, int previewHeight) {
        Log.d(TAG, "Preview size changed.");
        if (mFocusManager != null) mFocusManager.setPreviewSize(width, height);
    }

    private void resetExposureCompensation() {
        String value = mPreferences.getString(CameraSettings.KEY_EXPOSURE,
                CameraSettings.EXPOSURE_DEFAULT_VALUE);
        if (!CameraSettings.EXPOSURE_DEFAULT_VALUE.equals(value)) {
            Editor editor = mPreferences.edit();
            editor.putString(CameraSettings.KEY_EXPOSURE, "0");
            editor.apply();
        }
    }

    private void keepMediaProviderInstance() {
        // We want to keep a reference to MediaProvider in camera's lifecycle.
        // TODO: Utilize mMediaProviderClient instance to replace
        // ContentResolver calls.
        if (mMediaProviderClient == null) {
            mMediaProviderClient = mContentResolver
                    .acquireContentProviderClient(MediaStore.AUTHORITY);
        }
    }

    // Snapshots can only be taken after this is called. It should be called
    // once only. We could have done these things in onCreate() but we want to
    // make preview screen appear as soon as possible.
    private void initializeFirstTime() {
        if (mFirstTimeInitialized) return;

        // Initialize location service.
        boolean recordLocation = RecordLocationPreference.get(
                mPreferences, mContentResolver);
        mLocationManager.recordLocation(recordLocation);

        keepMediaProviderInstance();

        mShutterButton = mActivity.getShutterButton();

        mUI.initializeFirstTime();
        MediaSaveService s = mActivity.getMediaSaveService();
        // We set the listener only when both service and shutterbutton
        // are initialized.
        if (s != null) {
            s.setListener(this);
        }

        mNamedImages = new NamedImages();
         mGraphView = (GraphView)mRootView.findViewById(R.id.graph_view);
        if(mGraphView == null){
            Log.e(TAG, "mGraphView is null");
        } else{
            mGraphView.setPhotoModuleObject(this);
        }

        mFirstTimeInitialized = true;
        addIdleHandler();

        mActivity.updateStorageSpaceAndHint();
    }

    // If the activity is paused and resumed, this method will be called in
    // onResume.
    private void initializeSecondTime() {
        // Start location update if needed.
        boolean recordLocation = RecordLocationPreference.get(
                mPreferences, mContentResolver);
        mLocationManager.recordLocation(recordLocation);
        MediaSaveService s = mActivity.getMediaSaveService();
        if (s != null) {
            s.setListener(this);
        }
        mNamedImages = new NamedImages();
        mUI.initializeSecondTime(mParameters);
        keepMediaProviderInstance();
    }

    @Override
    public void onSurfaceCreated(SurfaceHolder holder) {
        // Do not access the camera if camera start up thread is not finished.
        if (mCameraDevice == null || mCameraStartUpThread != null)
            return;

        mCameraDevice.setPreviewDisplayAsync(holder);
        // This happens when onConfigurationChanged arrives, surface has been
        // destroyed, and there is no onFullScreenChanged.
        if (mCameraState == PREVIEW_STOPPED) {
            setupPreview();
        }
    }

    private void showTapToFocusToastIfNeeded() {
        // Show the tap to focus toast if this is the first start.
        if (mFocusAreaSupported &&
                mPreferences.getBoolean(CameraSettings.KEY_CAMERA_FIRST_USE_HINT_SHOWN, true)) {
            // Delay the toast for one second to wait for orientation.
            mHandler.sendEmptyMessageDelayed(SHOW_TAP_TO_FOCUS_TOAST, 1000);
        }
    }

    private void addIdleHandler() {
        MessageQueue queue = Looper.myQueue();
        queue.addIdleHandler(new MessageQueue.IdleHandler() {
            @Override
            public boolean queueIdle() {
                Storage.ensureOSXCompatible();
                return false;
            }
        });
    }

    @TargetApi(ApiHelper.VERSION_CODES.ICE_CREAM_SANDWICH)
    @Override
    public void startFaceDetection() {
        if (!ApiHelper.HAS_FACE_DETECTION) return;
        if (mFaceDetectionEnabled == false
               || mFaceDetectionStarted || mCameraState != IDLE) return;
        if (mParameters.getMaxNumDetectedFaces() > 0) {
            mFaceDetectionStarted = true;
            CameraInfo info = CameraHolder.instance().getCameraInfo()[mCameraId];
            mUI.onStartFaceDetection(mDisplayOrientation,
                    (info.facing == CameraInfo.CAMERA_FACING_FRONT));
            mCameraDevice.setFaceDetectionListener(mUI);
            mCameraDevice.startFaceDetection();
        }
    }

    @TargetApi(ApiHelper.VERSION_CODES.ICE_CREAM_SANDWICH)
    @Override
    public void stopFaceDetection() {
        if (!ApiHelper.HAS_FACE_DETECTION) return;
        if (mFaceDetectionEnabled == false || !mFaceDetectionStarted) return;
        if (mParameters.getMaxNumDetectedFaces() > 0) {
            mFaceDetectionStarted = false;
            mCameraDevice.setFaceDetectionListener(null);
            mCameraDevice.stopFaceDetection();
            mUI.clearFaces();
        }
    }

    public void cancelLongShot() {
        Log.d(TAG, "cancel longshot mode");
        mCameraDevice.setLongshot(false);
        mCameraDevice.enableShutterSound(true);
        setCameraState(IDLE);
        mFocusManager.resetTouchFocus();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent m) {
        if (mCameraState == SWITCHING_CAMERA) return true;
        return mUI.dispatchTouchEvent(m);
    }

    private boolean isLongshotSlowDownNeeded(){

        long totalMemory = Runtime.getRuntime().totalMemory() / mMB;
        long freeMemory = Runtime.getRuntime().freeMemory() / mMB;
        long maxMemory = Runtime.getRuntime().maxMemory() / mMB;

        long remainMemory = maxMemory - totalMemory;

        mMemInfoReader.readMemInfo();
        long availMem = mMemInfoReader.getFreeSize() + mMemInfoReader.getCachedSize()
            - SECONDARY_SERVER_MEM;
        availMem = availMem/ mMB;

        Log.d(TAG, "Longshot memory status. remain memory is "+remainMemory+"MB, available memroy is "+availMem+"MB");

        if( availMem > 0 ){
            if( remainMemory <= LONGSHOT_SLOWDOWN_THRESHOLD &&
                remainMemory > LONGSHOT_CANCEL_THRESHOLD ){
                Log.d(TAG, "memory limited, need slow down snapshot frame rate.");
                return true;
            }else if(remainMemory <= LONGSHOT_CANCEL_THRESHOLD){
                Log.d(TAG, "memory used up, need cancel longshot.");
                mLongshotActive = false;
                Toast.makeText(mActivity,R.string.msg_cancel_longshot_for_limited_memory,
                    Toast.LENGTH_SHORT).show();
                return true;
            }
        }else{
            Log.d(TAG, "system available memory is limited, cancel longshot.");
            mLongshotActive = false;
            Toast.makeText(mActivity,R.string.msg_cancel_longshot_for_limited_memory,
                Toast.LENGTH_SHORT).show();
            return true;
        }

        return false;
    }

    private void continueLongshot(){
        Log.d(TAG, "continueLongshot");
        if ( mLongshotSave ) {
            mCameraDevice.takePicture2(new LongshotShutterCallback(),
                    mRawPictureCallback, mPostViewPictureCallback,
                    new LongshotPictureCallback(null), mCameraState,
                    mFocusManager.getFocusState());
        } else {
            mCameraDevice.takePicture2(new LongshotShutterCallback(),
                    mRawPictureCallback, mPostViewPictureCallback,
                    new JpegPictureCallback(null), mCameraState,
                    mFocusManager.getFocusState());
        }
    }

    private final class LongshotShutterCallback
            implements android.hardware.Camera.ShutterCallback {

        @Override
        public void onShutter() {
            mShutterCallbackTime = System.currentTimeMillis();
            mShutterLag = mShutterCallbackTime - mCaptureStartTime;
            Log.e(TAG, "[KPI Perf] PROFILE_SHUTTER_LAG mShutterLag = " + mShutterLag + "ms");
            synchronized(mCameraDevice) {

                if ( mCameraState != LONGSHOT ) {
                    return;
                }

                mCameraDevice.refreshParameters();
                //need check whether we get all pictures
                mParameters = mCameraDevice.getParameters();
                String str = mParameters.get("longshot-count");
                if(str != null){
                    mLongshotReceiveCount = Integer.parseInt(str);
                }else{
                    mLongshotReceiveCount++;
                }
                Log.d(TAG, "mLongshotReceiveCount="+mLongshotReceiveCount+", mLongshotSetNum="+mLongshotSetNum);

                if(!mLongshotActive){
                    Log.d(TAG, "longshot mode is canceled");
                    return;
                }
                if(!mLongshotAlwaysOn &&
                    mLongshotReceiveCount >= mLongshotSetNum){
                    //we can finish longshot mode now.
                    Log.d(TAG, "Longshot - received expected number of shutter.");
                    mLongshotActive = false;
                    stopSoundPool();
                    return;
                }

                //check storage status
                if( mActivity.getStorageSpace() <= Storage.LOW_STORAGE_THRESHOLD ){
                    Log.d(TAG, "Storage limited, need cancel longshot.");
                    mLongshotActive = false;
                    stopSoundPool();
                    return;
                }

                if( isLongshotSlowDownNeeded() ){
                    if(mLongshotActive){
                        Log.d(TAG, "slow down snapshot. Take picture later.");
                        playSoundPool();
                        mHandler.sendEmptyMessageDelayed(CONTINUE_LONGSHOT, LONGSHOT_SLOWDOWN_DELAY);
                    }else{
                        stopSoundPool();
                    }
                    return;
                }

                playSoundPool();
                continueLongshot();

            }
        }
    }

    private final class ShutterCallback
            implements android.hardware.Camera.ShutterCallback {

        private boolean mAnimateFlash;

        public ShutterCallback(boolean animateFlash) {
            mAnimateFlash = animateFlash;
        }

        @Override
        public void onShutter() {
            mShutterCallbackTime = System.currentTimeMillis();
            mShutterLag = mShutterCallbackTime - mCaptureStartTime;
            Log.e(TAG, "[KPI Perf] PROFILE_SHUTTER_LAG mShutterLag = " + mShutterLag + "ms");
            if (mAnimateFlash) {
                mActivity.runOnUiThread(mFlashRunnable);
            }
        }
    }

      private final class StatsCallback
           implements android.hardware.Camera.CameraDataCallback {
            @Override
        public void onCameraData(int [] data, android.hardware.Camera camera) {
            //if(!mPreviewing || !mHiston || !mFirstTimeInitialized){
            if(!mHiston || !mFirstTimeInitialized){
                return;
            }
            /*The first element in the array stores max hist value . Stats data begin from second value*/
            synchronized(statsdata) {
                System.arraycopy(data,0,statsdata,0,STATS_DATA);
            }
            mActivity.runOnUiThread(new Runnable() {
                public void run() {
                    if(mGraphView != null)
                        mGraphView.PreviewChanged();
                }
           });
        }
    }

    private final class PostViewPictureCallback implements PictureCallback {
        @Override
        public void onPictureTaken(
                byte [] data, android.hardware.Camera camera) {
            mPostViewPictureCallbackTime = System.currentTimeMillis();
            Log.v(TAG, "mShutterToPostViewCallbackTime = "
                    + (mPostViewPictureCallbackTime - mShutterCallbackTime)
                    + "ms");
        }
    }

    private final class RawPictureCallback implements PictureCallback {
        @Override
        public void onPictureTaken(
                byte [] rawData, android.hardware.Camera camera) {
            mRawPictureCallbackTime = System.currentTimeMillis();
            Log.v(TAG, "mShutterToRawCallbackTime = "
                    + (mRawPictureCallbackTime - mShutterCallbackTime) + "ms");
            if (ApiHelper.HAS_SURFACE_TEXTURE && !mIsImageCaptureIntent
                    && mActivity.mShowCameraAppView) {
                // Finish capture animation
                mHandler.removeMessages(CAPTURE_ANIMATION_DONE);
                ((CameraScreenNail) mActivity.mCameraScreenNail).animateSlide();
                mHandler.sendEmptyMessageDelayed(CAPTURE_ANIMATION_DONE,
                        CaptureAnimManager.getAnimationDuration());
            }
        }
    }

    private final class LongshotPictureCallback implements PictureCallback {
        Location mLocation;

        public LongshotPictureCallback(Location loc) {
            mLocation = loc;
        }

        @Override
        public void onPictureTaken(
                final byte [] jpegData, final android.hardware.Camera camera) {
            if (mPaused) {
                return;
            }

            mFocusManager.updateFocusUI(); // Ensure focus indicator is hidden.

            String jpegFilePath = new String(jpegData);
            mNamedImages.nameNewImage(mContentResolver, System.currentTimeMillis());
            String title = mNamedImages.getTitle();
            if (title == null) {
                Log.e(TAG, "Unbalanced name/data pair");
                return;
            }

            long date = mNamedImages.getDate();
            if  (date == -1 ) {
                Log.e(TAG, "Invalid filename date");
                return;
            }

            String dstPath = Storage.DIRECTORY;
            File sdCard = android.os.Environment.getExternalStorageDirectory();
            File dstFile = new File(dstPath);
            if (dstFile == null) {
                Log.e(TAG, "Destination file path invalid");
                return;
            }

            File srcFile = new File(jpegFilePath);
            if (srcFile == null) {
                Log.e(TAG, "Source file path invalid");
                return;
            }

            if ( srcFile.renameTo(dstFile) ) {
                Size s = mParameters.getPictureSize();
                String pictureFormat = mParameters.get(KEY_PICTURE_FORMAT);
                mActivity.getMediaSaveService().addImage(
                       null, title, date, mLocation, s.width, s.height,
                       0, null, mOnMediaSavedListener, mContentResolver, pictureFormat);
            } else {
                Log.e(TAG, "Failed to move jpeg file");
            }
        }
    }

    private final class JpegPictureCallback implements PictureCallback {
        Location mLocation;

        public JpegPictureCallback(Location loc) {
            mLocation = loc;
        }

        @Override
        public void onPictureTaken(
                final byte [] jpegData, final android.hardware.Camera camera) {
            if (mPaused) {
                return;
            }
            if (mSceneMode == Util.SCENE_MODE_HDR) {
                mActivity.showSwitcher();
                mActivity.setSwipingEnabled(true);
            }

            mReceivedSnapNum = mReceivedSnapNum + 1;
            mJpegPictureCallbackTime = System.currentTimeMillis();
            if(mSnapshotMode == CameraInfo.CAMERA_SUPPORT_MODE_ZSL) {
                Log.v(TAG, "JpegPictureCallback : in zslmode");
                mParameters = mCameraDevice.getParameters();
                mBurstSnapNum = mParameters.getInt("num-snaps-per-shutter");
            }
            Log.v(TAG, "JpegPictureCallback: Received = " + mReceivedSnapNum +
                      "Burst count = " + mBurstSnapNum);
            // If postview callback has arrived, the captured image is displayed
            // in postview callback. If not, the captured image is displayed in
            // raw picture callback.
            if (mPostViewPictureCallbackTime != 0) {
                mShutterToPictureDisplayedTime =
                        mPostViewPictureCallbackTime - mShutterCallbackTime;
                mPictureDisplayedToJpegCallbackTime =
                        mJpegPictureCallbackTime - mPostViewPictureCallbackTime;
            } else {
                mShutterToPictureDisplayedTime =
                        mRawPictureCallbackTime - mShutterCallbackTime;
                mPictureDisplayedToJpegCallbackTime =
                        mJpegPictureCallbackTime - mRawPictureCallbackTime;
            }
            Log.v(TAG, "mPictureDisplayedToJpegCallbackTime = "
                    + mPictureDisplayedToJpegCallbackTime + "ms");

            // Only animate when in full screen capture mode
            // i.e. If monkey/a user swipes to the gallery during picture taking,
            // don't show animation
            mFocusManager.updateFocusUI(); // Ensure focus indicator is hidden.

            boolean needRestartPreview = !mIsImageCaptureIntent
                      && (mSnapshotMode != CameraInfo.CAMERA_SUPPORT_MODE_ZSL)
                      && (mReceivedSnapNum == mBurstSnapNum);

            if (needRestartPreview) {
                if (ApiHelper.CAN_START_PREVIEW_IN_JPEG_CALLBACK) {
                    setupPreview();
                } else {
                    // Camera HAL of some devices have a bug. Starting preview
                    // immediately after taking a picture will fail. Wait some
                    // time before starting the preview.
                    mHandler.sendEmptyMessageDelayed(SETUP_PREVIEW, 300);
                }
            } else if ((mReceivedSnapNum == mBurstSnapNum)
                        && (mCameraState != LONGSHOT)){
                mFocusManager.resetTouchFocus();
                setCameraState(IDLE);
            }

            //in longshot mode, when we received all images, disable it.
            if(mCameraState == LONGSHOT &&
                !mLongshotActive &&
                mReceivedSnapNum >= mLongshotReceiveCount){
                Log.d(TAG, "All images are received in longshot.");
                cancelLongShot();
            }

            if (!mIsImageCaptureIntent) {
                // Burst snapshot. Generate new image name.
                if (mReceivedSnapNum > 1)
                    mNamedImages.nameNewImage(mContentResolver, mCaptureStartTime);

                // Calculate the width and the height of the jpeg.
                Size s = mParameters.getPictureSize();
                ExifInterface exif = Exif.getExif(jpegData);

                int orientation = Exif.getOrientation(exif);
                int width, height;
                if ((mJpegRotation + orientation) % 180 == 0) {
                    width = s.width;
                    height = s.height;
                } else {
                    width = s.height;
                    height = s.width;
                }

                String pictureFormat = mParameters.get(KEY_PICTURE_FORMAT);
                if (pictureFormat != null && !pictureFormat.equalsIgnoreCase(PIXEL_FORMAT_JPEG)) {
                    // overwrite width and height if raw picture
                    String pair = mParameters.get(KEY_QC_RAW_PICUTRE_SIZE);
                    if (pair != null) {
                        int pos = pair.indexOf('x');
                        if (pos != -1) {
                            width = Integer.parseInt(pair.substring(0, pos));
                            height = Integer.parseInt(pair.substring(pos + 1));
                        }
                    }
                }

                String title = mNamedImages.getTitle();
                long date = mNamedImages.getDate();
                if (title == null) {
                    Log.e(TAG, "Unbalanced name/data pair");
                } else {
                    if (date == -1) date = mCaptureStartTime;
                    if (mHeading >= 0) {
                        // heading direction has been updated by the sensor.
                        ExifTag directionRefTag = exif.buildTag(
                                ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
                                ExifInterface.GpsTrackRef.MAGNETIC_DIRECTION);
                        ExifTag directionTag = exif.buildTag(
                                ExifInterface.TAG_GPS_IMG_DIRECTION,
                                new Rational(mHeading, 1));
                        exif.setTag(directionRefTag);
                        exif.setTag(directionTag);
                    }
                    String mPictureFormat = mParameters.get(KEY_PICTURE_FORMAT);
                    mActivity.getMediaSaveService().addImage(
                            jpegData, title, date, mLocation, width, height,
                            orientation, exif, mOnMediaSavedListener, mContentResolver, mPictureFormat);
                }
            } else {
                mJpegImageData = jpegData;
                if (!mQuickCapture) {
                    mUI.showPostCaptureAlert();
                } else {
                    onCaptureDone();
                }
            }

            // Check this in advance of each shot so we don't add to shutter
            // latency. It's true that someone else could write to the SD card in
            // the mean time and fill it, but that could have happened between the
            // shutter press and saving the JPEG too.
            mActivity.updateStorageSpaceAndHint();

            long now = System.currentTimeMillis();
            mJpegCallbackFinishTime = now - mJpegPictureCallbackTime;
            Log.v(TAG, "mJpegCallbackFinishTime = "
                    + mJpegCallbackFinishTime + "ms");

            if (mReceivedSnapNum == mBurstSnapNum)
                mJpegPictureCallbackTime = 0;

            if (mHiston && (mSnapshotMode ==CameraInfo.CAMERA_SUPPORT_MODE_ZSL)) {
                mActivity.runOnUiThread(new Runnable() {
                    public void run() {
                        if (mGraphView != null) {
                            mGraphView.setVisibility(View.VISIBLE);
                            mGraphView.PreviewChanged();
                        }
                    }
                });
            }
        }
    }

    private OnSeekBarChangeListener mSeekListener = new OnSeekBarChangeListener() {
        public void onStartTrackingTouch(SeekBar bar) {
        // no support
        }
        public void onProgressChanged(SeekBar bar, int progress, boolean fromtouch) {
        }
        public void onStopTrackingTouch(SeekBar bar) {
        }
    };

    private OnSeekBarChangeListener mskinToneSeekListener = new OnSeekBarChangeListener() {
        public void onStartTrackingTouch(SeekBar bar) {
        // no support
        }

        public void onProgressChanged(SeekBar bar, int progress, boolean fromtouch) {
            int value = (progress + MIN_SCE_FACTOR) * SCE_FACTOR_STEP;
            if(progress > (MAX_SCE_FACTOR - MIN_SCE_FACTOR)/2){
                RightValue.setText(String.valueOf(value));
                LeftValue.setText("");
            } else if (progress < (MAX_SCE_FACTOR - MIN_SCE_FACTOR)/2){
                LeftValue.setText(String.valueOf(value));
                RightValue.setText("");
            } else {
                LeftValue.setText("");
                RightValue.setText("");
            }
            if(value != mskinToneValue && mCameraDevice != null) {
                mskinToneValue = value;
                mParameters = mCameraDevice.getParameters();
                mParameters.set("skinToneEnhancement", String.valueOf(mskinToneValue));
                mCameraDevice.setParameters(mParameters);
            }
        }

        public void onStopTrackingTouch(SeekBar bar) {
            Log.v(TAG, "Set onStopTrackingTouch mskinToneValue = " + mskinToneValue);
            Editor editor = mPreferences.edit();
            editor.putString(CameraSettings.KEY_SKIN_TONE_ENHANCEMENT_FACTOR,
                             Integer.toString(mskinToneValue));
            editor.apply();
        }
    };

    private final class AutoFocusCallback
            implements android.hardware.Camera.AutoFocusCallback {
        @Override
        public void onAutoFocus(
                boolean focused, android.hardware.Camera camera) {
            if (mPaused) return;

            mAutoFocusTime = System.currentTimeMillis() - mFocusStartTime;
            Log.v(TAG, "mAutoFocusTime = " + mAutoFocusTime + "ms");
            //don't reset the camera state while capture is in progress
            //otherwise, it might result in another takepicture
            if(SNAPSHOT_IN_PROGRESS != mCameraState)
                setCameraState(IDLE);
            mFocusManager.onAutoFocus(focused, mUI.isShutterPressed());
        }
    }

    @TargetApi(ApiHelper.VERSION_CODES.JELLY_BEAN)
    private final class AutoFocusMoveCallback
            implements android.hardware.Camera.AutoFocusMoveCallback {
        @Override
        public void onAutoFocusMoving(
            boolean moving, android.hardware.Camera camera) {
                mFocusManager.onAutoFocusMoving(moving);
        }
    }

    private static class NamedImages {
        private ArrayList<NamedEntity> mQueue;
        private boolean mStop;
        private NamedEntity mNamedEntity;

        public NamedImages() {
            mQueue = new ArrayList<NamedEntity>();
        }

        public void nameNewImage(ContentResolver resolver, long date) {
            NamedEntity r = new NamedEntity();
            r.title = Util.createJpegName(date);
            r.date = date;
            mQueue.add(r);
        }

        public String getTitle() {
            if (mQueue.isEmpty()) {
                mNamedEntity = null;
                return null;
            }
            mNamedEntity = mQueue.get(0);
            mQueue.remove(0);

            return mNamedEntity.title;
        }

        // Must be called after getTitle().
        public long getDate() {
            if (mNamedEntity == null) return -1;
            return mNamedEntity.date;
        }

        private static class NamedEntity {
            String title;
            long date;
        }
    }

    private void setCameraState(int state) {
        mCameraState = state;
        switch (state) {
        case PhotoController.PREVIEW_STOPPED:
        case PhotoController.SNAPSHOT_IN_PROGRESS:
        case PhotoController.FOCUSING:
        case PhotoController.LONGSHOT:
        case PhotoController.SWITCHING_CAMERA:
            mUI.enableGestures(false);
            break;
        case PhotoController.IDLE:
            if (mActivity.isInCameraApp()) {
                mUI.enableGestures(true);
            }
            break;
        }
    }

    private void animateFlash() {
        // Only animate when in full screen capture mode
        // i.e. If monkey/a user swipes to the gallery during picture taking,
        // don't show animation
        if (ApiHelper.HAS_SURFACE_TEXTURE && !mIsImageCaptureIntent
                && mActivity.mShowCameraAppView) {
            // Start capture animation.
            ((CameraScreenNail) mActivity.mCameraScreenNail).animateFlash(mDisplayRotation);
            mUI.enablePreviewThumb(true);
            mHandler.sendEmptyMessageDelayed(CAPTURE_ANIMATION_DONE,
                    CaptureAnimManager.getAnimationDuration());
        }
    }

    @Override
    public boolean capture() {
        // If we are already in the middle of taking a snapshot or the image save request
        // is full then ignore.
        if (mCameraDevice == null || mCameraState == SNAPSHOT_IN_PROGRESS
                || mCameraState == SWITCHING_CAMERA
                || mActivity.getMediaSaveService().isQueueFull()) {
            return false;
        }
        mCaptureStartTime = System.currentTimeMillis();
        mPostViewPictureCallbackTime = 0;
        mJpegImageData = null;

        final boolean animateBefore = (mSceneMode == Util.SCENE_MODE_HDR);
        if(mHiston) {
            if (mSnapshotMode != CameraInfo.CAMERA_SUPPORT_MODE_ZSL) {
                mHiston = false;
                mCameraDevice.setHistogramMode(null);
            }
            mActivity.runOnUiThread(new Runnable() {
                public void run() {
                    if(mGraphView != null)
                        mGraphView.setVisibility(View.INVISIBLE);
                }
            });
        }

        if (animateBefore) {
            animateFlash();
        }

        // Set rotation and gps data.
        int orientation;
        // We need to be consistent with the framework orientation (i.e. the
        // orientation of the UI.) when the auto-rotate screen setting is on.
        if (mActivity.isAutoRotateScreen()) {
            orientation = (360 - mDisplayRotation) % 360;
        } else {
            orientation = mOrientation;
        }
        mJpegRotation = Util.getJpegRotation(mCameraId, orientation);
        mParameters.setRotation(mJpegRotation);
        String pictureFormat = mParameters.get(KEY_PICTURE_FORMAT);
        Location loc = null;
        if (pictureFormat != null &&
              PIXEL_FORMAT_JPEG.equalsIgnoreCase(pictureFormat)) {
            loc = mLocationManager.getCurrentLocation();
        }
        Util.setGpsParameters(mParameters, loc);
        mCameraDevice.setParameters(mParameters);
        mParameters = mCameraDevice.getParameters();

        mBurstSnapNum = mParameters.getInt("num-snaps-per-shutter");
        mReceivedSnapNum = 0;

        if ( mCameraState == LONGSHOT && mLongshotSave ) {
            mCameraDevice.takePicture2(new LongshotShutterCallback(),
                    mRawPictureCallback, mPostViewPictureCallback,
                    new LongshotPictureCallback(loc), mCameraState,
                    mFocusManager.getFocusState());
        } else if ( mCameraState == LONGSHOT ) {
            mCameraDevice.takePicture2(new LongshotShutterCallback(),
                    mRawPictureCallback, mPostViewPictureCallback,
                    new JpegPictureCallback(loc), mCameraState,
                    mFocusManager.getFocusState());
        } else {
            mCameraDevice.takePicture2(new ShutterCallback(!animateBefore),
                    mRawPictureCallback, mPostViewPictureCallback,
                    new JpegPictureCallback(loc), mCameraState,
                    mFocusManager.getFocusState());
            setCameraState(SNAPSHOT_IN_PROGRESS);
        }

        mNamedImages.nameNewImage(mContentResolver, mCaptureStartTime);

        mFaceDetectionStarted = false;
        UsageStatistics.onEvent(UsageStatistics.COMPONENT_CAMERA,
                UsageStatistics.ACTION_CAPTURE_DONE, "Photo");
        return true;
    }

    @Override
    public void setFocusParameters() {
        setCameraParameters(UPDATE_PARAM_PREFERENCE);
    }

    private int getPreferredCameraId(ComboPreferences preferences) {
        int intentCameraId = Util.getCameraFacingIntentExtras(mActivity);
        if (intentCameraId != -1) {
            // Testing purpose. Launch a specific camera through the intent
            // extras.
            return intentCameraId;
        } else {
            return CameraSettings.readPreferredCameraId(preferences);
        }
    }

    @Override
    public void onFullScreenChanged(boolean full) {
        mUI.onFullScreenChanged(full);
        if (ApiHelper.HAS_SURFACE_TEXTURE) {
            if (mActivity.mCameraScreenNail != null) {
                ((CameraScreenNail) mActivity.mCameraScreenNail).setFullScreen(full);
            }
            return;
        }
    }

    private void updateHdrMode() {
        String zsl = mPreferences.getString(CameraSettings.KEY_ZSL,
                         mActivity.getString(R.string.pref_camera_zsl_default));
        if (zsl.equals("on")) {
            mUI.overrideSettings(CameraSettings.KEY_CAMERA_HDR,
                                      mParameters.getAEBracket());
        } else {
            mUI.overrideSettings(CameraSettings.KEY_CAMERA_HDR, null);
        }
    }

    private void updateSceneMode() {
        // If scene mode is set, we cannot set flash mode, white balance, and
        // focus mode, instead, we read it from driver
        if (!Parameters.SCENE_MODE_AUTO.equals(mSceneMode)) {
            overrideCameraSettings(mCurrFlashMode,
                    mParameters.getWhiteBalance(), mParameters.getFocusMode(),
                    Integer.toString(mParameters.getExposureCompensation()),
                    mCurrTouchAfAec, mParameters.getAutoExposure(),
                    Integer.toString(mParameters.getSaturation()),
                    Integer.toString(mParameters.getContrast()));
        } else if (mFocusManager.isZslEnabled()) {
            overrideCameraSettings(null, null, mParameters.getFocusMode(),
                                   null, null, null, null, null);
        } else {
            overrideCameraSettings(null, null, null, null, null, null, null, null);
        }
    }

    private void overrideCameraSettings(final String flashMode,
            final String whiteBalance, final String focusMode,
            final String exposureMode, final String touchMode,
            final String autoExposure, final String saturation,
            final String contrast) {
        mUI.overrideSettings(
                CameraSettings.KEY_FLASH_MODE, flashMode,
                CameraSettings.KEY_WHITE_BALANCE, whiteBalance,
                CameraSettings.KEY_FOCUS_MODE, focusMode,
                CameraSettings.KEY_EXPOSURE, exposureMode,
                CameraSettings.KEY_AUTOEXPOSURE, autoExposure,
                CameraSettings.KEY_SATURATION, saturation,
                CameraSettings.KEY_CONTRAST, contrast);
    }

    private void loadCameraPreferences() {
        CameraSettings settings = new CameraSettings(mActivity, mInitialParams,
                mCameraId, CameraHolder.instance().getCameraInfo());
        mPreferenceGroup = settings.getPreferenceGroup(R.xml.camera_preferences);
    }

    @Override
    public void onOrientationChanged(int orientation) {
        // We keep the last known orientation. So if the user first orient
        // the camera then point the camera to floor or sky, we still have
        // the correct orientation.
        if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return;

        int oldOrientation = mOrientation;
        mOrientation = Util.roundOrientation(orientation, mOrientation);

        if (oldOrientation != mOrientation) {
            Log.v(TAG, "onOrientationChanged, update parameters");
            if (mParameters != null && mCameraDevice != null) {
                onSharedPreferenceChanged();
            }
        }

        // Show the toast after getting the first orientation changed.
        if (mHandler.hasMessages(SHOW_TAP_TO_FOCUS_TOAST)) {
            mHandler.removeMessages(SHOW_TAP_TO_FOCUS_TOAST);
            showTapToFocusToast();
        }

        // need to re-initialize mGraphView to show histogram on rotate
        mGraphView = (GraphView)mRootView.findViewById(R.id.graph_view);
        if(mGraphView != null){
            mGraphView.setPhotoModuleObject(this);
            mGraphView.PreviewChanged();
        }
    }

    @Override
    public void onStop() {
        if (mMediaProviderClient != null) {
            mMediaProviderClient.release();
            mMediaProviderClient = null;
        }
    }

    @Override
    public void onCaptureCancelled() {
        mActivity.setResultEx(Activity.RESULT_CANCELED, new Intent());
        mActivity.finish();
    }

    @Override
    public void onCaptureRetake() {
        if (mPaused)
            return;
        mUI.hidePostCaptureAlert();
        setupPreview();
    }

    @Override
    public void onCaptureDone() {
        if (mPaused) {
            return;
        }

        byte[] data = mJpegImageData;

        if (mCropValue == null) {
            // First handle the no crop case -- just return the value.  If the
            // caller specifies a "save uri" then write the data to its
            // stream. Otherwise, pass back a scaled down version of the bitmap
            // directly in the extras.
            if (mSaveUri != null) {
                OutputStream outputStream = null;
                try {
                    outputStream = mContentResolver.openOutputStream(mSaveUri);
                    outputStream.write(data);
                    outputStream.close();

                    mActivity.setResultEx(Activity.RESULT_OK);
                    mActivity.finish();
                } catch (IOException ex) {
                    // ignore exception
                } finally {
                    Util.closeSilently(outputStream);
                }
            } else {
                ExifInterface exif = Exif.getExif(data);
                int orientation = Exif.getOrientation(exif);
                Bitmap bitmap = Util.makeBitmap(data, 50 * 1024);
                bitmap = Util.rotate(bitmap, orientation);
                mActivity.setResultEx(Activity.RESULT_OK,
                        new Intent("inline-data").putExtra("data", bitmap));
                mActivity.finish();
            }
        } else {
            // Save the image to a temp file and invoke the cropper
            Uri tempUri = null;
            FileOutputStream tempStream = null;
            try {
                File path = mActivity.getFileStreamPath(sTempCropFilename);
                path.delete();
                tempStream = mActivity.openFileOutput(sTempCropFilename, 0);
                tempStream.write(data);
                tempStream.close();
                tempUri = Uri.fromFile(path);
            } catch (FileNotFoundException ex) {
                mActivity.setResultEx(Activity.RESULT_CANCELED);
                mActivity.finish();
                return;
            } catch (IOException ex) {
                mActivity.setResultEx(Activity.RESULT_CANCELED);
                mActivity.finish();
                return;
            } finally {
                Util.closeSilently(tempStream);
            }

            Bundle newExtras = new Bundle();
            if (mCropValue.equals("circle")) {
                newExtras.putString("circleCrop", "true");
            }
            if (mSaveUri != null) {
                newExtras.putParcelable(MediaStore.EXTRA_OUTPUT, mSaveUri);
            } else {
                newExtras.putBoolean(CropExtras.KEY_RETURN_DATA, true);
            }
            if (mActivity.isSecureCamera()) {
                newExtras.putBoolean(CropExtras.KEY_SHOW_WHEN_LOCKED, true);
            }

            Intent cropIntent = new Intent(FilterShowActivity.CROP_ACTION);

            cropIntent.setData(tempUri);
            cropIntent.putExtras(newExtras);

            mActivity.startActivityForResult(cropIntent, REQUEST_CROP);
        }
    }

    @Override
    public void onShutterButtonFocus(boolean pressed) {
        if (mPaused || mUI.collapseCameraControls()
                || (mCameraState == SNAPSHOT_IN_PROGRESS)
                || (mCameraState == PREVIEW_STOPPED)) return;

        synchronized(mCameraDevice) {
           if (mCameraState == LONGSHOT &&
                pressed == false) {
               Log.d(TAG, "receive Button Up message.");
               //longshot should not be active any more
               //but we need wait all jpeg images arrived before
               //cancel longshot.
               mLongshotActive = false;
               stopSoundPool();
           }
        }

        // Do not do focus if there is not enough storage.
        if (pressed && !canTakePicture()) return;

        if (pressed) {
            mFocusManager.onShutterDown();
        } else {
            // for countdown mode, we need to postpone the shutter release
            // i.e. lock the focus during countdown.
            if (!mUI.isCountingDown()) {
                mFocusManager.onShutterUp();
            }
        }
    }

    @Override
    public void onShutterButtonClick() {
        if (mPaused || mUI.collapseCameraControls()
                || (mCameraState == SWITCHING_CAMERA)
                || (mCameraState == PREVIEW_STOPPED)) return;

        // Do not take the picture if there is not enough storage.
        if (mActivity.getStorageSpace() <= Storage.LOW_STORAGE_THRESHOLD) {
            Log.i(TAG, "Not enough space or storage not ready. remaining="
                    + mActivity.getStorageSpace());
            return;
        }
        Log.v(TAG, "onShutterButtonClick: mCameraState=" + mCameraState);

        if (mSceneMode == Util.SCENE_MODE_HDR) {
            mActivity.hideSwitcher();
            mActivity.setSwipingEnabled(false);
        }

         //Need to disable focus for ZSL mode
        if(mSnapshotMode == CameraInfo.CAMERA_SUPPORT_MODE_ZSL) {
            mFocusManager.setZslEnable(true);
        } else {
            mFocusManager.setZslEnable(false);
        }

        // If the user wants to do a snapshot while the previous one is still
        // in progress, remember the fact and do it after we finish the previous
        // one and re-start the preview. Snapshot in progress also includes the
        // state that autofocus is focusing and a picture will be taken when
        // focus callback arrives.
        if ((mFocusManager.isFocusingSnapOnFinish() || mCameraState == SNAPSHOT_IN_PROGRESS)
                && !mIsImageCaptureIntent) {
            mSnapshotOnIdle = true;
            return;
        }

        String timer = mPreferences.getString(
                CameraSettings.KEY_TIMER,
                mActivity.getString(R.string.pref_camera_timer_default));
        boolean playSound = mPreferences.getString(CameraSettings.KEY_TIMER_SOUND_EFFECTS,
                mActivity.getString(R.string.pref_camera_timer_sound_default))
                .equals(mActivity.getString(R.string.setting_on_value));

        int seconds = Integer.parseInt(timer);
        // When shutter button is pressed, check whether the previous countdown is
        // finished. If not, cancel the previous countdown and start a new one.
        if (mUI.isCountingDown()) {
            mUI.cancelCountDown();
        }
        if (seconds > 0) {
            mUI.startCountDown(seconds, playSound);
        } else {
           mSnapshotOnIdle = false;
           mFocusManager.doSnap();
        }
    }

    @Override
    public void onShutterButtonLongClick() {
        // Do not enable longshot if there is not enough storage.
        if (mActivity.getStorageSpace() <= Storage.LOW_STORAGE_THRESHOLD) {
            Log.i(TAG, "Not enough space or storage not ready. remaining="
                    + mActivity.getStorageSpace());
            return;
        }
        if (mFocusManager.isZslEnabled()
                && (null != mCameraDevice) && (mCameraState == IDLE)) {
            // Enable Longshot by default
            //int prop = SystemProperties.getInt(PERSIST_LONG_ENABLE, 0);
            //boolean enable = ( prop == 1 );
            int prop;
            boolean enable = true;
            if ( enable ) {
                prop = SystemProperties.getInt(PERSIST_LONG_SAVE, 0);
                mLongshotSave = ( prop == 1 );
                // Set longshot number
                String longshot_num = mPreferences.getString(
                    CameraSettings.KEY_BURST_NUM,
                    mActivity.getString(R.string.pref_camera_burst_num_default));
                if(longshot_num.equals("on")){
                    //longshot will be active until release button
                    mLongshotAlwaysOn = true;
                }else{
                    mLongshotSetNum  = Integer.parseInt(longshot_num);
                    mLongshotAlwaysOn = false;
                }
                Log.v(TAG, "Longshot mLongshotAlwaysOn=" + mLongshotAlwaysOn +
                    ", number =" + mLongshotSetNum);
                mLongshotReceiveCount = 0;
                mLongshotActive = true;
                mCameraDevice.setLongshot(true);
                setCameraState(PhotoController.LONGSHOT);
                mCameraDevice.enableShutterSound(false);
                mFocusManager.doSnap();
            }
        }
    }

    void setPreviewFrameLayoutCameraOrientation(){
       CameraInfo info = CameraHolder.instance().getCameraInfo()[mCameraId];
       //if camera mount angle is 0 or 180, we want to resize preview
       if(info.orientation % 180 == 0){
           mUI.mPreviewFrameLayout.setRenderer(mUI.mPieRenderer);
           mUI.mPreviewFrameLayout.cameraOrientationPreviewResize(true);
       } else{
           mUI.mPreviewFrameLayout.cameraOrientationPreviewResize(false);
       }
    }

    private void resizeForPreviewAspectRatio() {
        setPreviewFrameLayoutCameraOrientation();
        Size size = mParameters.getPictureSize();
        Log.e(TAG,"Width = "+ size.width+ "Height = "+size.height);
        mUI.setAspectRatio((double) size.width / size.height);
    }

    @Override
    public void installIntentFilter() {
    }

    @Override
    public boolean updateStorageHintOnResume() {
        return mFirstTimeInitialized;
    }

    @Override
    public void updateCameraAppView() {
    }

    @Override
    public void onResumeBeforeSuper() {
        mPaused = false;
    }

    @Override
    public void onResumeAfterSuper() {
        if (mOpenCameraFail || mCameraDisabled) return;

        mJpegPictureCallbackTime = 0;
        mZoomValue = 0;

        // Start the preview if it is not started.
        if (mCameraState == PREVIEW_STOPPED && mCameraStartUpThread == null) {
            resetExposureCompensation();
            mCameraStartUpThread = new CameraStartUpThread();
            mCameraStartUpThread.start();
        }

        if (mSkinToneSeekBar != true)
        {
            Log.v(TAG, "Send tone bar: mSkinToneSeekBar = " + mSkinToneSeekBar);
            mHandler.sendEmptyMessage(SET_SKIN_TONE_FACTOR);
        }
        // If first time initialization is not finished, put it in the
        // message queue.
        if (!mFirstTimeInitialized) {
            mHandler.sendEmptyMessage(FIRST_TIME_INIT);
        } else {
            initializeSecondTime();
        }
        keepScreenOnAwhile();

        // Dismiss open menu if exists.
        PopupManager.getInstance(mActivity).notifyShowPopup(null);
        UsageStatistics.onContentViewChanged(
                UsageStatistics.COMPONENT_CAMERA, "PhotoModule");

        Sensor gsensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (gsensor != null) {
            mSensorManager.registerListener(this, gsensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        Sensor msensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (msensor != null) {
            mSensorManager.registerListener(this, msensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    void waitCameraStartUpThread() {
        try {
            if (mCameraStartUpThread != null) {
                mCameraStartUpThread.cancel();
                mCameraStartUpThread.join();
                mCameraStartUpThread = null;
                setCameraState(IDLE);
            }
        } catch (InterruptedException e) {
            // ignore
        }
    }

    @Override
    public void onPauseBeforeSuper() {
        mPaused = true;
        Sensor gsensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (gsensor != null) {
            mSensorManager.unregisterListener(this, gsensor);
        }

        Sensor msensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (msensor != null) {
            mSensorManager.unregisterListener(this, msensor);
        }

        //cancel lognshot if it is in enalbed status
        if(mCameraDevice != null && mCameraState == LONGSHOT){
            mLongshotActive = false;
            stopSoundPool();
            cancelLongShot();
        }
    }

    @Override
    public void onPauseAfterSuper() {
        // Wait the camera start up thread to finish.
        waitCameraStartUpThread();

        // When camera is started from secure lock screen for the first time
        // after screen on, the activity gets onCreate->onResume->onPause->onResume.
        // To reduce the latency, keep the camera for a short time so it does
        // not need to be opened again.
        if (mCameraDevice != null && mActivity.isSecureCamera()
                && ActivityBase.isFirstStartAfterScreenOn()) {
            ActivityBase.resetFirstStartAfterScreenOn();
            CameraHolder.instance().keep(KEEP_CAMERA_TIMEOUT);
        }
        // Reset the focus first. Camera CTS does not guarantee that
        // cancelAutoFocus is allowed after preview stops.
        if (mCameraDevice != null && mCameraState != PREVIEW_STOPPED) {
            mCameraDevice.cancelAutoFocus();
        }
        stopPreview();
        // Release surface texture.
        ((CameraScreenNail) mActivity.mCameraScreenNail).releaseSurfaceTexture();

        mNamedImages = null;

        if (mLocationManager != null) mLocationManager.recordLocation(false);

        // If we are in an image capture intent and has taken
        // a picture, we just clear it in onPause.
        mJpegImageData = null;

        // Remove the messages in the event queue.
        mHandler.removeMessages(SETUP_PREVIEW);
        mHandler.removeMessages(FIRST_TIME_INIT);
        mHandler.removeMessages(CHECK_DISPLAY_ROTATION);
        mHandler.removeMessages(SWITCH_CAMERA);
        mHandler.removeMessages(SWITCH_CAMERA_START_ANIMATION);
        mHandler.removeMessages(CAMERA_OPEN_DONE);
        mHandler.removeMessages(START_PREVIEW_DONE);
        mHandler.removeMessages(OPEN_CAMERA_FAIL);
        mHandler.removeMessages(CAMERA_DISABLED);

        closeCamera();

        resetScreenOn();
        mUI.onPause();

        mPendingSwitchCameraId = -1;
        if (mFocusManager != null) mFocusManager.removeMessages();
        MediaSaveService s = mActivity.getMediaSaveService();
        if (s != null) {
            s.setListener(null);
        }
    }

    /**
     * The focus manager is the first UI related element to get initialized,
     * and it requires the RenderOverlay, so initialize it here
     */
    private void initializeFocusManager() {
        // Create FocusManager object. startPreview needs it.
        // if mFocusManager not null, reuse it
        // otherwise create a new instance
        if (mFocusManager != null) {
            mFocusManager.removeMessages();
        } else {
            CameraInfo info = CameraHolder.instance().getCameraInfo()[mCameraId];
            boolean mirror = (info.facing == CameraInfo.CAMERA_FACING_FRONT);
            String[] defaultFocusModes = mActivity.getResources().getStringArray(
                    R.array.pref_camera_focusmode_default_array);
            mFocusManager = new FocusOverlayManager(mPreferences, defaultFocusModes,
                    mInitialParams, this, mirror,
                    mActivity.getMainLooper(), mUI);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.v(TAG, "onConfigurationChanged");
        setDisplayOrientation();
        resizeForPreviewAspectRatio();
    }

    @Override
    public void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CROP: {
                Intent intent = new Intent();
                if (data != null) {
                    Bundle extras = data.getExtras();
                    if (extras != null) {
                        intent.putExtras(extras);
                    }
                }
                mActivity.setResultEx(resultCode, intent);
                mActivity.finish();

                File path = mActivity.getFileStreamPath(sTempCropFilename);
                path.delete();

                break;
            }
        }
    }

    protected CameraManager.CameraProxy getCamera() {
        return mCameraDevice;
    }

    private boolean canTakePicture() {
        return isCameraIdle() && (mActivity.getStorageSpace() > Storage.LOW_STORAGE_THRESHOLD);
    }

    @Override
    public void autoFocus() {
        mFocusStartTime = System.currentTimeMillis();
        mCameraDevice.autoFocus(mAutoFocusCallback);
        setCameraState(FOCUSING);
    }

    @Override
    public void cancelAutoFocus() {
        mCameraDevice.cancelAutoFocus();
        setCameraState(IDLE);
        setCameraParameters(UPDATE_PARAM_PREFERENCE);
    }

    // Preview area is touched. Handle touch focus.
    @Override
    public void onSingleTapUp(View view, int x, int y) {
        if (mPaused || mCameraDevice == null || !mFirstTimeInitialized
                || mCameraState == SNAPSHOT_IN_PROGRESS
                || mCameraState == SWITCHING_CAMERA
                || mCameraState == PREVIEW_STOPPED) {
            return;
        }

        // Do not trigger touch focus if popup window is opened.
        if (mUI.removeTopLevelPopup()) return;

        //If Touch AF/AEC is disabled in UI, return
        if(this.mTouchAfAecFlag == false) {
            return;
        }
        // Check if metering area or focus area is supported.
        if (!mFocusAreaSupported && !mMeteringAreaSupported) return;
        mFocusManager.onSingleTapUp(x, y);
    }

    @Override
    public boolean onBackPressed() {
        return mUI.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_VOLUME_UP:
        case KeyEvent.KEYCODE_VOLUME_DOWN:
        case KeyEvent.KEYCODE_FOCUS:
            if (mActivity.isInCameraApp() && mFirstTimeInitialized &&
                  mShutterButton.getVisibility() == View.VISIBLE) {
                if (event.getRepeatCount() == 0) {
                    onShutterButtonFocus(true);
                }
                return true;
            }
            return false;
        case KeyEvent.KEYCODE_CAMERA:
            if (mFirstTimeInitialized && event.getRepeatCount() == 0) {
                // Only capture when in full screen capture mode
                if (mActivity.isInCameraApp() && mShutterButton.getVisibility() == View.VISIBLE)
                    onShutterButtonClick();
            }
            return true;
        case KeyEvent.KEYCODE_DPAD_LEFT:
            if ( (mCameraState != PREVIEW_STOPPED) &&
                  (mFocusManager.getCurrentFocusState() != mFocusManager.STATE_FOCUSING) &&
                  (mFocusManager.getCurrentFocusState() != mFocusManager.STATE_FOCUSING_SNAP_ON_FINISH) ) {
                if (mbrightness > MINIMUM_BRIGHTNESS) {
                    mbrightness-=mbrightness_step;
                    /* Set the "luma-adaptation" parameter */
                    mParameters = mCameraDevice.getParameters();
                    mParameters.set("luma-adaptation", String.valueOf(mbrightness));
                    mCameraDevice.setParameters(mParameters);
                }
                brightnessProgressBar.setProgress(mbrightness);
                brightnessProgressBar.setVisibility(View.VISIBLE);
            }
            break;
           case KeyEvent.KEYCODE_DPAD_RIGHT:
            if ( (mCameraState != PREVIEW_STOPPED) &&
                  (mFocusManager.getCurrentFocusState() != mFocusManager.STATE_FOCUSING) &&
                  (mFocusManager.getCurrentFocusState() != mFocusManager.STATE_FOCUSING_SNAP_ON_FINISH) ) {
                if (mbrightness < MAXIMUM_BRIGHTNESS) {
                    mbrightness+=mbrightness_step;
                    /* Set the "luma-adaptation" parameter */
                    mParameters = mCameraDevice.getParameters();
                    mParameters.set("luma-adaptation", String.valueOf(mbrightness));
                    mCameraDevice.setParameters(mParameters);
                }
                brightnessProgressBar.setProgress(mbrightness);
                brightnessProgressBar.setVisibility(View.VISIBLE);
            }
            break;
        case KeyEvent.KEYCODE_DPAD_CENTER:
            // If we get a dpad center event without any focused view, move
            // the focus to the shutter button and press it.
            if (mFirstTimeInitialized && event.getRepeatCount() == 0) {
                // Start auto-focus immediately to reduce shutter lag. After
                // the shutter button gets the focus, onShutterButtonFocus()
                // will be called again but it is fine.
                if (mUI.removeTopLevelPopup()) return true;
                onShutterButtonFocus(true);
                mUI.pressShutterButton();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
        case KeyEvent.KEYCODE_VOLUME_UP:
        case KeyEvent.KEYCODE_VOLUME_DOWN:
            if (mActivity.isInCameraApp() && mFirstTimeInitialized &&
                  mShutterButton.getVisibility() == View.VISIBLE) {
                onShutterButtonClick();
                return true;
            }
            return false;
        case KeyEvent.KEYCODE_FOCUS:
            if (mFirstTimeInitialized) {
                onShutterButtonFocus(false);
            }
            return true;
        }
        return false;
    }

    @TargetApi(ApiHelper.VERSION_CODES.ICE_CREAM_SANDWICH)
    private void closeCamera() {
        if (mCameraDevice != null) {
            mCameraDevice.setZoomChangeListener(null);
            if(ApiHelper.HAS_FACE_DETECTION) {
                mCameraDevice.setFaceDetectionListener(null);
            }
            mCameraDevice.setErrorCallback(null);
            CameraHolder.instance().release();
            mFaceDetectionStarted = false;
            mCameraDevice = null;
            setCameraState(PREVIEW_STOPPED);
            mFocusManager.onCameraReleased();
        }
    }

    private void setDisplayOrientation() {
        mDisplayRotation = Util.getDisplayRotation(mActivity);
        mDisplayOrientation = Util.getDisplayOrientation(mDisplayRotation, mCameraId);
        mCameraDisplayOrientation = Util.getDisplayOrientation(0, mCameraId);
        mUI.setDisplayOrientation(mDisplayOrientation);
        if (mFocusManager != null) {
            mFocusManager.setDisplayOrientation(mDisplayOrientation);
        }
        // GLRoot also uses the DisplayRotation, and needs to be told to layout to update
        mActivity.getGLRoot().requestLayoutContentPane();
    }

    // Only called by UI thread.
    private void setupPreview() {
        mFocusManager.resetTouchFocus();
        startPreview();
        setCameraState(IDLE);
        startFaceDetection();
    }

    // This can be called by UI Thread or CameraStartUpThread. So this should
    // not modify the views.
    private void startPreview() {
        mCameraDevice.setErrorCallback(mErrorCallback);

        // ICS camera frameworks has a bug. Face detection state is not cleared
        // after taking a picture. Stop the preview to work around it. The bug
        // was fixed in JB.
        if (mCameraState != PREVIEW_STOPPED) stopPreview();

        setDisplayOrientation();

        if (!mSnapshotOnIdle) {
            // If the focus mode is continuous autofocus, call cancelAutoFocus to
            // resume it because it may have been paused by autoFocus call.
            if (Util.FOCUS_MODE_CONTINUOUS_PICTURE.equals(mFocusManager.getFocusMode())) {
                mCameraDevice.cancelAutoFocus();
            }
            mFocusManager.setAeAwbLock(false); // Unlock AE and AWB.
        }
        setCameraParameters(UPDATE_PARAM_ALL);

        if (ApiHelper.HAS_SURFACE_TEXTURE) {
            CameraScreenNail screenNail = (CameraScreenNail) mActivity.mCameraScreenNail;
            if (mUI.getSurfaceTexture() == null) {
                Size size = mParameters.getPreviewSize();
                if (mCameraDisplayOrientation % 180 == 0) {
                    screenNail.setSize(size.width, size.height);
                } else {
                    screenNail.setSize(size.height, size.width);
                }
                screenNail.enableAspectRatioClamping();
                mActivity.notifyScreenNailChanged();
                screenNail.acquireSurfaceTexture();
                CameraStartUpThread t = mCameraStartUpThread;
                if (t != null && t.isCanceled()) {
                    return; // Exiting, so no need to get the surface texture.
                }
                mUI.setSurfaceTexture(screenNail.getSurfaceTexture());
            } else {
                updatePreviewSize(screenNail);
            }
            mCameraDevice.setDisplayOrientation(mCameraDisplayOrientation);
            Object st = mUI.getSurfaceTexture();
            if (st != null) {
                mCameraDevice.setPreviewTextureAsync((SurfaceTexture) st);
            }
        } else {
            mCameraDevice.setDisplayOrientation(mDisplayOrientation);
            mCameraDevice.setPreviewDisplayAsync(mUI.getSurfaceHolder());
        }

        Log.v(TAG, "startPreview");
        mCameraDevice.startPreviewAsync();

        mFocusManager.onPreviewStarted();

        if (mSnapshotOnIdle) {
            mHandler.post(mDoSnapRunnable);
        }
    }

    private void updatePreviewSize(CameraScreenNail snail) {
        Size size = mParameters.getPreviewSize();
        int w = size.width;
        int h = size.height;
        if (mCameraDisplayOrientation % 180 != 0) {
            w = size.height;
            h = size.width;
        }
        if (snail.getTextureWidth() != w || snail.getTextureHeight() != h) {
            snail.setSize(w, h);
        }
        snail.enableAspectRatioClamping();
        mActivity.notifyScreenNailChanged();
    }

    @Override
    public void stopPreview() {
        if (mCameraDevice != null && mCameraState != PREVIEW_STOPPED) {
            Log.v(TAG, "stopPreview");
            mCameraDevice.stopPreview();
            //mFaceDetectionStarted = false;
        }
        setCameraState(PREVIEW_STOPPED);
        if (mFocusManager != null) mFocusManager.onPreviewStopped();
    }

    @SuppressWarnings("deprecation")
    private void updateCameraParametersInitialize() {
        // Reset preview frame rate to the maximum because it may be lowered by
        // video camera application.
        List<Integer> frameRates = mParameters.getSupportedPreviewFrameRates();
        if (frameRates != null) {
            Integer max = Collections.max(frameRates);
            mParameters.setPreviewFrameRate(max);
        }

        mParameters.set(Util.RECORDING_HINT, Util.FALSE);

        // Disable video stabilization. Convenience methods not available in API
        // level <= 14
        String vstabSupported = mParameters.get("video-stabilization-supported");
        if ("true".equals(vstabSupported)) {
            mParameters.set("video-stabilization", "false");
        }
    }

    private void updateCameraParametersZoom() {
        // Set zoom.
        if (mParameters.isZoomSupported()) {
            mParameters.setZoom(mZoomValue);
        }
    }
    private boolean needRestart() {
        mRestartPreview = false;
        String zsl = mPreferences.getString(CameraSettings.KEY_ZSL,
                                  mActivity.getString(R.string.pref_camera_zsl_default));
        if(zsl.equals("on") && mSnapshotMode != CameraInfo.CAMERA_SUPPORT_MODE_ZSL
           && mCameraState != PREVIEW_STOPPED) {
            //Switch on ZSL Camera mode
            Log.v(TAG, "Switching to ZSL Camera Mode. Restart Preview");
            mRestartPreview = true;
            return mRestartPreview;
        }
        if(zsl.equals("off") && mSnapshotMode != CameraInfo.CAMERA_SUPPORT_MODE_NONZSL
                 && mCameraState != PREVIEW_STOPPED) {
            //Switch on Normal Camera mode
            Log.v(TAG, "Switching to Normal Camera Mode. Restart Preview");
            mRestartPreview = true;
            return mRestartPreview;
        }
        return mRestartPreview;
    }

    private void qcomUpdateCameraParametersPreference() {
        //qcom Related Parameter update
        //Set Brightness.
        mParameters.set("luma-adaptation", String.valueOf(mbrightness));

        // Set Touch AF/AEC parameter.
        String touchAfAec = mPreferences.getString(
            CameraSettings.KEY_TOUCH_AF_AEC,
            mActivity.getString(R.string.pref_camera_touchafaec_default));
        if (Util.isSupported(touchAfAec, mParameters.getSupportedTouchAfAec())) {
            mCurrTouchAfAec = touchAfAec;
            mParameters.setTouchAfAec(touchAfAec);
        }

        try {
            if(mParameters.getTouchAfAec().equals(mParameters.TOUCH_AF_AEC_ON))
                this.mTouchAfAecFlag = true;
            else
                this.mTouchAfAecFlag = false;
        } catch(Exception e){
            Log.e(TAG, "Handled NULL pointer Exception");
        }

        // Set Picture Format
        // Picture Formats specified in UI should be consistent with
        // PIXEL_FORMAT_JPEG and PIXEL_FORMAT_RAW constants
        String pictureFormat = mPreferences.getString(
                CameraSettings.KEY_PICTURE_FORMAT,
                mActivity.getString(R.string.pref_camera_picture_format_default));
        mParameters.set(KEY_PICTURE_FORMAT, pictureFormat);

        // Set JPEG quality.
        String jpegQuality = mPreferences.getString(
                CameraSettings.KEY_JPEG_QUALITY,
                mActivity.getString(R.string.pref_camera_jpegquality_default));
        //mUnsupportedJpegQuality = false;
        Size pic_size = mParameters.getPictureSize();
        if (pic_size == null) {
            Log.e(TAG, "error getPictureSize: size is null");
        }
        else{
            if("100".equals(jpegQuality) && (pic_size.width >= 3200)){
                //mUnsupportedJpegQuality = true;
            }else {
                mParameters.setJpegQuality(JpegEncodingQualityMappings.getQualityNumber(jpegQuality));
            }
        }

        // Set Selectable Zone Af parameter.
        String selectableZoneAf = mPreferences.getString(
            CameraSettings.KEY_SELECTABLE_ZONE_AF,
            mActivity.getString(R.string.pref_camera_selectablezoneaf_default));
        List<String> str = mParameters.getSupportedSelectableZoneAf();
        if (Util.isSupported(selectableZoneAf, mParameters.getSupportedSelectableZoneAf())) {
            mParameters.setSelectableZoneAf(selectableZoneAf);
        }

        // Set wavelet denoise mode
        if (mParameters.getSupportedDenoiseModes() != null) {
            String Denoise = mPreferences.getString( CameraSettings.KEY_DENOISE,
                             mActivity.getString(R.string.pref_camera_denoise_default));
            mParameters.setDenoise(Denoise);
        }
        // Set Redeye Reduction
        String redeyeReduction = mPreferences.getString(
                CameraSettings.KEY_REDEYE_REDUCTION,
                mActivity.getString(R.string.pref_camera_redeyereduction_default));
        if (Util.isSupported(redeyeReduction,
            mParameters.getSupportedRedeyeReductionModes())) {
            mParameters.setRedeyeReductionMode(redeyeReduction);
        }
        // Set ISO parameter
        String iso = mPreferences.getString(
                CameraSettings.KEY_ISO,
                mActivity.getString(R.string.pref_camera_iso_default));
        if (Util.isSupported(iso,
                mParameters.getSupportedIsoValues())) {
                mParameters.setISOValue(iso);
        }
        // Set color effect parameter.
        String colorEffect = mPreferences.getString(
                CameraSettings.KEY_COLOR_EFFECT,
                mActivity.getString(R.string.pref_camera_coloreffect_default));
        Log.v(TAG, "Color effect value =" + colorEffect);
        if (Util.isSupported(colorEffect, mParameters.getSupportedColorEffects())) {
            mParameters.setColorEffect(colorEffect);
        }
        //Set Saturation
        String saturationStr = mPreferences.getString(
                CameraSettings.KEY_SATURATION,
                mActivity.getString(R.string.pref_camera_saturation_default));
        int saturation = Integer.parseInt(saturationStr);
        Log.v(TAG, "Saturation value =" + saturation);
        if((0 <= saturation) && (saturation <= mParameters.getMaxSaturation())){
            mParameters.setSaturation(saturation);
        }
        // Set contrast parameter.
        String contrastStr = mPreferences.getString(
                CameraSettings.KEY_CONTRAST,
                mActivity.getString(R.string.pref_camera_contrast_default));
        int contrast = Integer.parseInt(contrastStr);
        Log.v(TAG, "Contrast value =" +contrast);
        if((0 <= contrast) && (contrast <= mParameters.getMaxContrast())){
            mParameters.setContrast(contrast);
        }
        // Set sharpness parameter
        String sharpnessStr = mPreferences.getString(
                CameraSettings.KEY_SHARPNESS,
                mActivity.getString(R.string.pref_camera_sharpness_default));
        int sharpness = Integer.parseInt(sharpnessStr) *
                (mParameters.getMaxSharpness()/MAX_SHARPNESS_LEVEL);
        Log.v(TAG, "Sharpness value =" + sharpness);
        if((0 <= sharpness) && (sharpness <= mParameters.getMaxSharpness())){
            mParameters.setSharpness(sharpness);
        }
        // Set Face Recognition
        String faceRC = mPreferences.getString(
                CameraSettings.KEY_FACE_RECOGNITION,
                mActivity.getString(R.string.pref_camera_facerc_default));
        Log.v(TAG, "Face Recognition value = " + faceRC);
        if (Util.isSupported(faceRC,
                CameraSettings.getSupportedFaceRecognitionModes(mParameters))) {
            mParameters.set(CameraSettings.KEY_QC_FACE_RECOGNITION, faceRC);
        }
        // Set AE Bracketing
        String aeBracketing = mPreferences.getString(
                CameraSettings.KEY_AE_BRACKET_HDR,
                mActivity.getString(R.string.pref_camera_ae_bracket_hdr_default));
        Log.v(TAG, "AE Bracketing value =" + aeBracketing);
        if (Util.isSupported(aeBracketing,
                CameraSettings.getSupportedAEBracketingModes(mParameters))) {
            mParameters.set(CameraSettings.KEY_QC_AE_BRACKETING, aeBracketing);
        }
        // Set auto exposure parameter.
        String autoExposure = mPreferences.getString(
                CameraSettings.KEY_AUTOEXPOSURE,
                mActivity.getString(R.string.pref_camera_autoexposure_default));
        Log.v(TAG, "autoExposure value =" + autoExposure);
        if (Util.isSupported(autoExposure, mParameters.getSupportedAutoexposure())) {
            mParameters.setAutoExposure(autoExposure);
        }

        // Set anti banding parameter.
        String antiBanding = mPreferences.getString(
                 CameraSettings.KEY_ANTIBANDING,
                 mActivity.getString(R.string.pref_camera_antibanding_default));
        Log.v(TAG, "antiBanding value =" + antiBanding);
        if (Util.isSupported(antiBanding, mParameters.getSupportedAntibanding())) {
            mParameters.setAntibanding(antiBanding);
        }

        String zsl = mPreferences.getString(CameraSettings.KEY_ZSL,
                                  mActivity.getString(R.string.pref_camera_zsl_default));
        String hdr = mPreferences.getString(CameraSettings.KEY_CAMERA_HDR,
                mActivity.getString(R.string.pref_camera_hdr_default));
        if((!isImageCaptureIntent())) {
            Log.v(TAG, "setZSLMode value = " + zsl);
            mParameters.setZSLMode(zsl);
        }
        if(zsl.equals("on")) {
            //Switch on ZSL Camera mode
            mSnapshotMode = CameraInfo.CAMERA_SUPPORT_MODE_ZSL;
            mParameters.setCameraMode(1);
            mFocusManager.setZslEnable(true);

            // Currently HDR is not supported under ZSL mode
            Editor editor = mPreferences.edit();
            editor.putString(CameraSettings.KEY_AE_BRACKET_HDR, mActivity.getString(R.string.setting_off_value));

            //Raw picture format is not supported under ZSL mode
            editor.putString(CameraSettings.KEY_PICTURE_FORMAT, mActivity.getString(R.string.pref_camera_picture_format_value_jpeg));
            editor.apply();

            //Try to set CAF for ZSL
            if(Util.isSupported(Parameters.FOCUS_MODE_CONTINUOUS_PICTURE,
                    mParameters.getSupportedFocusModes()) && !mFocusManager.isTouch()) {
                mFocusManager.overrideFocusMode(Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                mParameters.setFocusMode(Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (mFocusManager.isTouch()) {
                mFocusManager.overrideFocusMode(null);
                mParameters.setFocusMode(mFocusManager.getFocusMode());
            } else {
                // If not supported use the current mode
                mFocusManager.overrideFocusMode(mFocusManager.getFocusMode());
            }

            if(!pictureFormat.equals(PIXEL_FORMAT_JPEG)) {
                     mActivity.runOnUiThread(new Runnable() {
                     public void run() {
                Toast.makeText(mActivity, R.string.error_app_unsupported_raw,
                    Toast.LENGTH_SHORT).show();
                         }
                    });
            }

            if(hdr.equals(mActivity.getString(R.string.setting_on_value))) {
                     mActivity.runOnUiThread(new Runnable() {
                     public void run() {
                Toast.makeText(mActivity, R.string.error_app_unsupported_hdr_zsl,
                    Toast.LENGTH_SHORT).show();
                         }
                    });
            }
        } else if(zsl.equals("off")) {
            mSnapshotMode = CameraInfo.CAMERA_SUPPORT_MODE_NONZSL;
            mParameters.setCameraMode(0);
            mFocusManager.setZslEnable(false);
            mFocusManager.overrideFocusMode(null);
            mParameters.setFocusMode(mFocusManager.getFocusMode());
        }
        // Set face detetction parameter.
        String faceDetection = mPreferences.getString(
            CameraSettings.KEY_FACE_DETECTION,
            mActivity.getString(R.string.pref_camera_facedetection_default));

        if (Util.isSupported(faceDetection, mParameters.getSupportedFaceDetectionModes())) {
            mParameters.setFaceDetectionMode(faceDetection);
            if(faceDetection.equals("on") && mFaceDetectionEnabled == false) {
                mFaceDetectionEnabled = true;
                startFaceDetection();
            }
            if(faceDetection.equals("off") && mFaceDetectionEnabled == true) {
                stopFaceDetection();
                mFaceDetectionEnabled = false;
            }
        }
        // skin tone ie enabled only for auto,party and portrait BSM
        // when color effects are not enabled
        if((Parameters.SCENE_MODE_PARTY.equals(mSceneMode) ||
            Parameters.SCENE_MODE_PORTRAIT.equals(mSceneMode)) &&
            (Parameters.EFFECT_NONE.equals(colorEffect))) {
             //Set Skin Tone Correction factor
             Log.v(TAG, "set tone bar: mSceneMode = " + mSceneMode);
             if(mSeekBarInitialized == true)
                 mHandler.sendEmptyMessage(SET_SKIN_TONE_FACTOR);
        }

        //Set Histogram
        String histogram = mPreferences.getString(
                CameraSettings.KEY_HISTOGRAM,
                mActivity.getString(R.string.pref_camera_histogram_default));
        if (Util.isSupported(histogram,
            mParameters.getSupportedHistogramModes()) && mCameraDevice != null) {
            // Call for histogram
            if(histogram.equals("enable")) {
                mActivity.runOnUiThread(new Runnable() {
                    public void run() {
                        if(mGraphView != null) {
                            mGraphView.setVisibility(View.VISIBLE);
                            mGraphView.PreviewChanged();
                        }
                    }
                });
                mCameraDevice.setHistogramMode(mStatsCallback);
                mHiston = true;
            } else {
                mHiston = false;
                mActivity.runOnUiThread(new Runnable() {
                    public void run() {
                         if (mGraphView != null)
                             mGraphView.setVisibility(View.INVISIBLE);
                         }
                    });
                mCameraDevice.setHistogramMode(null);
            }
        }
        // Read Flip mode from adb command
        //value: 0(default) - FLIP_MODE_OFF
        //value: 1 - FLIP_MODE_H
        //value: 2 - FLIP_MODE_V
        //value: 3 - FLIP_MODE_VH
        int preview_flip_value = SystemProperties.getInt("debug.camera.preview.flip", 0);
        int video_flip_value = SystemProperties.getInt("debug.camera.video.flip", 0);
        int picture_flip_value = SystemProperties.getInt("debug.camera.picture.flip", 0);
        int rotation = Util.getJpegRotation(mCameraId, mOrientation);
        mParameters.setRotation(rotation);
        if (rotation == 90 || rotation == 270) {
            // in case of 90 or 270 degree, V/H flip should reverse
            if (preview_flip_value == 1) {
                preview_flip_value = 2;
            } else if (preview_flip_value == 2) {
                preview_flip_value = 1;
            }
            if (video_flip_value == 1) {
                video_flip_value = 2;
            } else if (video_flip_value == 2) {
                video_flip_value = 1;
            }
            if (picture_flip_value == 1) {
                picture_flip_value = 2;
            } else if (picture_flip_value == 2) {
                picture_flip_value = 1;
            }
        }
        String preview_flip = Util.getFilpModeString(preview_flip_value);
        String video_flip = Util.getFilpModeString(video_flip_value);
        String picture_flip = Util.getFilpModeString(picture_flip_value);
        if(Util.isSupported(preview_flip, CameraSettings.getSupportedFlipMode(mParameters))){
            mParameters.set(CameraSettings.KEY_QC_PREVIEW_FLIP, preview_flip);
        }
        if(Util.isSupported(video_flip, CameraSettings.getSupportedFlipMode(mParameters))){
            mParameters.set(CameraSettings.KEY_QC_VIDEO_FLIP, video_flip);
        }
        if(Util.isSupported(picture_flip, CameraSettings.getSupportedFlipMode(mParameters))){
            mParameters.set(CameraSettings.KEY_QC_SNAPSHOT_PICTURE_FLIP, picture_flip);
        }

     }

    @TargetApi(ApiHelper.VERSION_CODES.JELLY_BEAN)
    private void setAutoExposureLockIfSupported() {
        if (mAeLockSupported) {
            mParameters.setAutoExposureLock(mFocusManager.getAeAwbLock());
        }
    }

    @TargetApi(ApiHelper.VERSION_CODES.JELLY_BEAN)
    private void setAutoWhiteBalanceLockIfSupported() {
        if (mAwbLockSupported) {
            mParameters.setAutoWhiteBalanceLock(mFocusManager.getAeAwbLock());
        }
    }

    @TargetApi(ApiHelper.VERSION_CODES.ICE_CREAM_SANDWICH)
    private void setFocusAreasIfSupported() {
        if (mFocusAreaSupported) {
            mParameters.setFocusAreas(mFocusManager.getFocusAreas());
        }
    }

    @TargetApi(ApiHelper.VERSION_CODES.ICE_CREAM_SANDWICH)
    private void setMeteringAreasIfSupported() {
        if (mMeteringAreaSupported) {
            // Use the same area for focus and metering.
            mParameters.setMeteringAreas(mFocusManager.getMeteringAreas());
        }
    }

    private void updateCameraParametersPreference() {
        setAutoExposureLockIfSupported();
        setAutoWhiteBalanceLockIfSupported();
        setFocusAreasIfSupported();
        setMeteringAreasIfSupported();

        // Set picture size.
        String pictureSize = mPreferences.getString(
                CameraSettings.KEY_PICTURE_SIZE, null);
        if (pictureSize == null) {
            CameraSettings.initialCameraPictureSize(mActivity, mParameters);
        } else {
            Size old_size = mParameters.getPictureSize();
            Log.v(TAG, "old picture_size = " + old_size.width + " x " + old_size.height);
            List<Size> supported = mParameters.getSupportedPictureSizes();
            CameraSettings.setCameraPictureSize(
                    pictureSize, supported, mParameters);
            Size size = mParameters.getPictureSize();
            Log.v(TAG, "new picture_size = " + size.width + " x " + size.height);
            if (old_size != null && size != null) {
                if(!size.equals(old_size) && mCameraState != PREVIEW_STOPPED) {
                    Log.v(TAG, "Picture Size changed. Restart Preview");
                    mRestartPreview = true;
                }
            }
        }
        Size size = mParameters.getPictureSize();

        // Set a preview size that is closest to the viewfinder height and has
        // the right aspect ratio.
        List<Size> sizes = mParameters.getSupportedPreviewSizes();
        Size optimalSize = Util.getPanelPreviewSize(mActivity, sizes);
        Size original = mParameters.getPreviewSize();
        if (!original.equals(optimalSize)) {
            mParameters.setPreviewSize(optimalSize.width, optimalSize.height);
            // Zoom related settings will be changed for different preview
            // sizes, so set and read the parameters to get latest values
            if (mHandler.getLooper() == Looper.myLooper()) {
                // On UI thread only, not when camera starts up
                setupPreview();
            } else {
                mCameraDevice.setParameters(mParameters);
            }
            mParameters = mCameraDevice.getParameters();
            Log.v(TAG, "Preview Size changed. Restart Preview");
            mRestartPreview = true;
        }
        Log.v(TAG, "Preview size is " + optimalSize.width + "x" + optimalSize.height);

        // Since changing scene mode may change supported values, set scene mode
        // first. HDR is a scene mode. To promote it in UI, it is stored in a
        // separate preference.
        String hdr = mPreferences.getString(CameraSettings.KEY_CAMERA_HDR,
                mActivity.getString(R.string.pref_camera_hdr_default));
        if (mActivity.getString(R.string.setting_on_value).equals(hdr)) {
            mSceneMode = Util.SCENE_MODE_HDR;
        } else {
            mSceneMode = mPreferences.getString(
                CameraSettings.KEY_SCENE_MODE,
                mActivity.getString(R.string.pref_camera_scenemode_default));
        }
        if (Util.isSupported(mSceneMode, mParameters.getSupportedSceneModes())) {
            if (!mParameters.getSceneMode().equals(mSceneMode)) {
                mParameters.setSceneMode(mSceneMode);

                // Setting scene mode will change the settings of flash mode,
                // white balance, and focus mode. Here we read back the
                // parameters, so we can know those settings.
                mCameraDevice.setParameters(mParameters);
                mParameters = mCameraDevice.getParameters();
            }
        } else {
            mSceneMode = mParameters.getSceneMode();
            if (mSceneMode == null) {
                mSceneMode = Parameters.SCENE_MODE_AUTO;
            }
        }

        // Set JPEG quality.
        int jpegQuality = CameraProfile.getJpegEncodingQualityParameter(mCameraId,
                CameraProfile.QUALITY_HIGH);
        mParameters.setJpegQuality(jpegQuality);

        // For the following settings, we need to check if the settings are
        // still supported by latest driver, if not, ignore the settings.

        // Set exposure compensation
        int value = CameraSettings.readExposure(mPreferences);
        int max = mParameters.getMaxExposureCompensation();
        int min = mParameters.getMinExposureCompensation();
        if (value >= min && value <= max) {
            mParameters.setExposureCompensation(value);
        } else {
            Log.w(TAG, "invalid exposure range: " + value);
        }

        if (Parameters.SCENE_MODE_AUTO.equals(mSceneMode)) {
            // Set flash mode.
            String flashMode = mPreferences.getString(
                    CameraSettings.KEY_FLASH_MODE,
                    mActivity.getString(R.string.pref_camera_flashmode_default));
            mCurrFlashMode = flashMode;
            List<String> supportedFlash = mParameters.getSupportedFlashModes();
            if (Util.isSupported(flashMode, supportedFlash)) {
                mParameters.setFlashMode(flashMode);
            } else {
                flashMode = mParameters.getFlashMode();
                if (flashMode == null) {
                    flashMode = mActivity.getString(
                            R.string.pref_camera_flashmode_no_flash);
                }
            }

            // Set white balance parameter.
            String whiteBalance = mPreferences.getString(
                    CameraSettings.KEY_WHITE_BALANCE,
                    mActivity.getString(R.string.pref_camera_whitebalance_default));
            if (Util.isSupported(whiteBalance,
                    mParameters.getSupportedWhiteBalance())) {
                mParameters.setWhiteBalance(whiteBalance);
            } else {
                whiteBalance = mParameters.getWhiteBalance();
                if (whiteBalance == null) {
                    whiteBalance = Parameters.WHITE_BALANCE_AUTO;
                }
            }

            // Set focus mode.
            mFocusManager.overrideFocusMode(null);
            mParameters.setFocusMode(mFocusManager.getFocusMode());
        } else {
            mFocusManager.overrideFocusMode(mParameters.getFocusMode());
            mParameters.setFlashMode(Parameters.FLASH_MODE_OFF);
        }

        if (mContinousFocusSupported && ApiHelper.HAS_AUTO_FOCUS_MOVE_CALLBACK) {
            updateAutoFocusMoveCallback();
        }
        //QCom related parameters updated here.
        qcomUpdateCameraParametersPreference();
    }

    @TargetApi(ApiHelper.VERSION_CODES.JELLY_BEAN)
    private void updateAutoFocusMoveCallback() {
        if (mParameters.getFocusMode().equals(Util.FOCUS_MODE_CONTINUOUS_PICTURE)) {
            mCameraDevice.setAutoFocusMoveCallback(
                (AutoFocusMoveCallback) mAutoFocusMoveCallback);
        } else {
            mCameraDevice.setAutoFocusMoveCallback(null);
        }
    }

    // We separate the parameters into several subsets, so we can update only
    // the subsets actually need updating. The PREFERENCE set needs extra
    // locking because the preference can be changed from GLThread as well.
    private void setCameraParameters(int updateSet) {
        if ((updateSet & UPDATE_PARAM_INITIALIZE) != 0) {
            updateCameraParametersInitialize();
        }

        if ((updateSet & UPDATE_PARAM_ZOOM) != 0) {
            updateCameraParametersZoom();
        }

        if ((updateSet & UPDATE_PARAM_PREFERENCE) != 0) {
            updateCameraParametersPreference();
        }

        mCameraDevice.setParameters(mParameters);
    }

    // If the Camera is idle, update the parameters immediately, otherwise
    // accumulate them in mUpdateSet and update later.
    private void setCameraParametersWhenIdle(int additionalUpdateSet) {
        mUpdateSet |= additionalUpdateSet;
        if (mCameraDevice == null) {
            // We will update all the parameters when we open the device, so
            // we don't need to do anything now.
            mUpdateSet = 0;
            return;
        } else if (isCameraIdle()) {
            setCameraParameters(mUpdateSet);
             if(mRestartPreview && mCameraState != PREVIEW_STOPPED) {
                Log.v(TAG, "Restarting Preview...");
                stopPreview();
                resizeForPreviewAspectRatio();
                startPreview();
                setCameraState(IDLE);
            }
            mRestartPreview = false;
            updateSceneMode();
            updateHdrMode();
            mUpdateSet = 0;
        } else {
            if (!mHandler.hasMessages(SET_CAMERA_PARAMETERS_WHEN_IDLE)) {
                mHandler.sendEmptyMessageDelayed(
                        SET_CAMERA_PARAMETERS_WHEN_IDLE, 1000);
            }
        }
    }

    public boolean isCameraIdle() {
        return (mCameraState == IDLE) ||
                (mCameraState == PREVIEW_STOPPED) ||
                ((mFocusManager != null) && mFocusManager.isFocusCompleted()
                        && (mCameraState != SWITCHING_CAMERA));
    }

    public boolean isImageCaptureIntent() {
        String action = mActivity.getIntent().getAction();
        return (MediaStore.ACTION_IMAGE_CAPTURE.equals(action)
                || ActivityBase.ACTION_IMAGE_CAPTURE_SECURE.equals(action));
    }

    private void setupCaptureParams() {
        Bundle myExtras = mActivity.getIntent().getExtras();
        if (myExtras != null) {
            mSaveUri = (Uri) myExtras.getParcelable(MediaStore.EXTRA_OUTPUT);
            mCropValue = myExtras.getString("crop");
        }
    }

    @Override
    public void onSharedPreferenceChanged() {
        // ignore the events after "onPause()"
        if (mPaused) return;

        boolean recordLocation = RecordLocationPreference.get(
                mPreferences, mContentResolver);
        mLocationManager.recordLocation(recordLocation);
        if(needRestart()){
            Log.v(TAG, "Restarting Preview... Camera Mode Changhed");
            stopPreview();
            startPreview();
            setCameraState(IDLE);
            mRestartPreview = false;
        }
        /* Check if the PhotoUI Menu is initialized or not. This
         * should be initialized during onCameraOpen() which should
         * have been called by now. But for some reason that is not
         * executed till now, then schedule these functionality for
         * later by posting a message to the handler */
        if (mUI.mMenuInitialized) {
            setCameraParametersWhenIdle(UPDATE_PARAM_PREFERENCE);
            resizeForPreviewAspectRatio();
            mUI.updateOnScreenIndicators(mParameters, mPreferenceGroup,
                mPreferences);
        } else {
            mHandler.sendEmptyMessage(SET_PHOTO_UI_PARAMS);
        }
        if (mSeekBarInitialized == true){
            Log.v(TAG, "onSharedPreferenceChanged Skin tone bar: change");
            // skin tone is enabled only for party and portrait BSM
            // when color effects are not enabled
            String colorEffect = mPreferences.getString(
                CameraSettings.KEY_COLOR_EFFECT,
                mActivity.getString(R.string.pref_camera_coloreffect_default));
            if((Parameters.SCENE_MODE_PARTY.equals(mSceneMode) ||
                Parameters.SCENE_MODE_PORTRAIT.equals(mSceneMode)) &&
                (Parameters.EFFECT_NONE.equals(colorEffect))) {
                Log.v(TAG, "Party/Portrait + No effect, SkinToneBar enabled");
            } else {
                disableSkinToneSeekBar();
            }
        }
        Storage.setSaveSDCard(
            mPreferences.getString(CameraSettings.KEY_CAMERA_SAVEPATH, "0").equals("1"));
        mActivity.updateStorageSpaceAndHint();
    }

    @Override
    public void onFirstLevelMenuDismiss() {
        if (mSaveToSDCard != Storage.isSaveSDCard()) {
            mSaveToSDCard = Storage.isSaveSDCard();
            mActivity.keepCameraScreenNail();
        }
    }

    @Override
    public void onCameraPickerClicked(int cameraId) {
        if (mPaused || mPendingSwitchCameraId != -1 || mCameraState != IDLE) return;

        mPendingSwitchCameraId = cameraId;
        if (ApiHelper.HAS_SURFACE_TEXTURE) {
            Log.v(TAG, "Start to copy texture. cameraId=" + cameraId);
            // We need to keep a preview frame for the animation before
            // releasing the camera. This will trigger onPreviewTextureCopied.
            ((CameraScreenNail) mActivity.mCameraScreenNail).copyTexture();
            // Disable all camera controls.
            setCameraState(SWITCHING_CAMERA);
        } else {
            switchCamera();
        }
    }

    @Override
    public void onCameraPickerSuperClicked() {
    }

    // Preview texture has been copied. Now camera can be released and the
    // animation can be started.
    @Override
    public void onPreviewTextureCopied() {
        mHandler.sendEmptyMessage(SWITCH_CAMERA);
    }

    @Override
    public void onCaptureTextureCopied() {
    }

    @Override
    public void onUserInteraction() {
        if (!mActivity.isFinishing()) keepScreenOnAwhile();
    }

    private void resetScreenOn() {
        mHandler.removeMessages(CLEAR_SCREEN_DELAY);
        mActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void keepScreenOnAwhile() {
        mHandler.removeMessages(CLEAR_SCREEN_DELAY);
        mActivity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mHandler.sendEmptyMessageDelayed(CLEAR_SCREEN_DELAY, SCREEN_DELAY);
    }

    // TODO: Delete this function after old camera code is removed
    @Override
    public void onRestorePreferencesClicked() {
    }

    @Override
    public void onOverriddenPreferencesClicked() {
        if (mPaused) return;
        mUI.showPreferencesToast();
    }

    private void showTapToFocusToast() {
        // TODO: Use a toast?
        new RotateTextToast(mActivity, R.string.tap_to_focus, 0).show();
        // Clear the preference.
        Editor editor = mPreferences.edit();
        editor.putBoolean(CameraSettings.KEY_CAMERA_FIRST_USE_HINT_SHOWN, false);
        editor.apply();
    }

    private void initializeCapabilities() {
        mInitialParams = mCameraDevice.getParameters();
        mFocusAreaSupported = Util.isFocusAreaSupported(mInitialParams);
        mMeteringAreaSupported = Util.isMeteringAreaSupported(mInitialParams);
        mAeLockSupported = Util.isAutoExposureLockSupported(mInitialParams);
        mAwbLockSupported = Util.isAutoWhiteBalanceLockSupported(mInitialParams);
        mContinousFocusSupported = mInitialParams.getSupportedFocusModes().contains(
                Util.FOCUS_MODE_CONTINUOUS_PICTURE);
    }

    @Override
    public void onCountDownFinished() {
        mSnapshotOnIdle = false;
        mFocusManager.doSnap();
        mFocusManager.onShutterUp();
    }

    @Override
    public boolean needsSwitcher() {
        return !mIsImageCaptureIntent;
    }

    @Override
    public boolean needsPieMenu() {
        return true;
    }

    @Override
    public void onShowSwitcherPopup() {
        mUI.onShowSwitcherPopup();
    }

    @Override
    public int onZoomChanged(int index) {
        // Not useful to change zoom value when the activity is paused.
        if (mPaused) return index;
        mZoomValue = index;
        if (mParameters == null || mCameraDevice == null) return index;
        // Set zoom parameters asynchronously
        mParameters.setZoom(mZoomValue);
        mCameraDevice.setParameters(mParameters);
        Parameters p = mCameraDevice.getParameters();
        if (p != null) return p.getZoom();
        return index;
    }

    @Override
    public int getCameraState() {
        return mCameraState;
    }

    @Override
    public void onQueueStatus(boolean full) {
        mUI.enableShutter(!full);
    }

    @Override
    public void onMediaSaveServiceConnected(MediaSaveService s) {
        // We set the listener only when both service and shutterbutton
        // are initialized.
        if (mFirstTimeInitialized) {
            s.setListener(this);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        float[] data;
        if (type == Sensor.TYPE_ACCELEROMETER) {
            data = mGData;
        } else if (type == Sensor.TYPE_MAGNETIC_FIELD) {
            data = mMData;
        } else {
            // we should not be here.
            return;
        }
        for (int i = 0; i < 3 ; i++) {
            data[i] = event.values[i];
        }
        float[] orientation = new float[3];
        SensorManager.getRotationMatrix(mR, null, mGData, mMData);
        SensorManager.getOrientation(mR, orientation);
        mHeading = (int) (orientation[0] * 180f / Math.PI) % 360;
        if (mHeading < 0) {
            mHeading += 360;
        }
    }

    private void initSoundPool() {
        mSoundPool = new SoundPool(1, AudioManager.STREAM_NOTIFICATION, 0);
        mSoundHashMap = new HashMap<Integer, Integer>();
        mSoundHashMap.put(1, mSoundPool.load("/system/media/audio/ui/camera_click.ogg", 1) );
        mLongshotSoundStatus = LONGSHOT_SOUND_STATUS_OFF;
        mSoundStreamID = -1;
    }

    private void playSoundPool(){
        if(mLongshotSoundStatus == LONGSHOT_SOUND_STATUS_OFF){
            //play shutter sound
            mSoundStreamID = mSoundPool.play(mSoundHashMap.get(1), 1.0f, 1.0f, 1, -1, 2.0f);
            mLongshotSoundStatus = LONGSHOT_SOUND_STATUS_ON;
        }
    }

    private void stopSoundPool() {
        if( (mLongshotSoundStatus == LONGSHOT_SOUND_STATUS_ON) &&
            (mSoundStreamID > 0) ){
            mSoundPool.stop(mSoundStreamID);
        }
        mLongshotSoundStatus = LONGSHOT_SOUND_STATUS_OFF;
        mSoundStreamID = -1;
    }

    private void setSkinToneFactor() {
        if(mCameraDevice == null || mParameters == null || skinToneSeekBar == null)
            return;

        String skinToneEnhancementPref = "enable";
        if(Util.isSupported(skinToneEnhancementPref,
               mParameters.getSupportedSkinToneEnhancementModes())) {
            if(skinToneEnhancementPref.equals("enable")) {
                int skinToneValue =0;
                int progress;
                //get the value for the first time!
                if (mskinToneValue ==0) {
                    String factor = mPreferences.getString(
                         CameraSettings.KEY_SKIN_TONE_ENHANCEMENT_FACTOR, "0");
                    skinToneValue = Integer.parseInt(factor);
                }

                Log.v(TAG, "Skin tone bar: enable = " + mskinToneValue);
                enableSkinToneSeekBar();
                //As a wrokaround set progress again to show the actually progress on screen.
                if (skinToneValue != 0) {
                    progress = (skinToneValue/SCE_FACTOR_STEP)-MIN_SCE_FACTOR;
                    skinToneSeekBar.setProgress(progress);
                }
            } else {
                Log.v(TAG, "Skin tone bar: disable");
                disableSkinToneSeekBar();
            }
        } else {
            Log.v(TAG, "Skin tone bar: Not supported");
            skinToneSeekBar.setVisibility(View.INVISIBLE);
        }
    }

    private void enableSkinToneSeekBar() {
        int progress;
        if(brightnessProgressBar != null)
            brightnessProgressBar.setVisibility(View.INVISIBLE);
        skinToneSeekBar.setMax(MAX_SCE_FACTOR-MIN_SCE_FACTOR);
        skinToneSeekBar.setVisibility(View.VISIBLE);
        skinToneSeekBar.requestFocus();
        if (mskinToneValue != 0) {
            progress = (mskinToneValue/SCE_FACTOR_STEP)-MIN_SCE_FACTOR;
            mskinToneSeekListener.onProgressChanged(skinToneSeekBar, progress, false);
        } else {
            progress = (MAX_SCE_FACTOR-MIN_SCE_FACTOR)/2;
            RightValue.setText("");
            LeftValue.setText("");
        }
        skinToneSeekBar.setProgress(progress);
        Title.setText("Skin Tone Enhancement");
        Title.setVisibility(View.VISIBLE);
        RightValue.setVisibility(View.VISIBLE);
        LeftValue.setVisibility(View.VISIBLE);
        mSkinToneSeekBar = true;
    }

    private void disableSkinToneSeekBar() {
        skinToneSeekBar.setVisibility(View.INVISIBLE);
        Title.setVisibility(View.INVISIBLE);
        RightValue.setVisibility(View.INVISIBLE);
        LeftValue.setVisibility(View.INVISIBLE);
        mskinToneValue = 0;
        mSkinToneSeekBar = false;
        Editor editor = mPreferences.edit();
        editor.putString(CameraSettings.KEY_SKIN_TONE_ENHANCEMENT_FACTOR,
            Integer.toString(mskinToneValue - MIN_SCE_FACTOR));
        editor.apply();
        if(brightnessProgressBar != null)
             brightnessProgressBar.setVisibility(View.VISIBLE);
    }
}

/*
 * Provide a mapping for Jpeg encoding quality levels
 * from String representation to numeric representation.
 */
class JpegEncodingQualityMappings {
    private static final String TAG = "JpegEncodingQualityMappings";
    private static final int DEFAULT_QUALITY = 85;
    private static HashMap<String, Integer> mHashMap =
            new HashMap<String, Integer>();

    static {
        mHashMap.put("normal",    CameraProfile.QUALITY_LOW);
        mHashMap.put("fine",      CameraProfile.QUALITY_MEDIUM);
        mHashMap.put("superfine", CameraProfile.QUALITY_HIGH);
    }

    // Retrieve and return the Jpeg encoding quality number
    // for the given quality level.
    public static int getQualityNumber(String jpegQuality) {
        try{
            int qualityPercentile = Integer.parseInt(jpegQuality);
            if(qualityPercentile >= 0 && qualityPercentile <=100)
                return qualityPercentile;
            else
                return DEFAULT_QUALITY;
        } catch(NumberFormatException nfe){
            //chosen quality is not a number, continue
        }
        Integer quality = mHashMap.get(jpegQuality);
        if (quality == null) {
            Log.w(TAG, "Unknown Jpeg quality: " + jpegQuality);
            return DEFAULT_QUALITY;
        }
        return CameraProfile.getJpegEncodingQualityParameter(quality.intValue());
    }
}

class GraphView extends View {
    private Bitmap  mBitmap;
    private Paint   mPaint = new Paint();
    private Paint   mPaintRect = new Paint();
    private Canvas  mCanvas = new Canvas();
    private float   mScale = (float)3;
    private float   mWidth;
    private float   mHeight;
    private PhotoModule mPhotoModule;
    private CameraManager.CameraProxy mGraphCameraDevice;
    private float scaled;
    private static final int STATS_SIZE = 256;
    private static final String TAG = "GraphView";


    public GraphView(Context context, AttributeSet attrs) {
        super(context,attrs);

        mPaint.setFlags(Paint.ANTI_ALIAS_FLAG);
        mPaintRect.setColor(0xFFFFFFFF);
        mPaintRect.setStyle(Paint.Style.FILL);
    }
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        mCanvas.setBitmap(mBitmap);
        mWidth = w;
        mHeight = h;
        super.onSizeChanged(w, h, oldw, oldh);
    }
    @Override
    protected void onDraw(Canvas canvas) {
        Log.v(TAG, "in Camera.java ondraw");
           //don't display histogram if user swipes to gallery during preview
        boolean inCamPreview = ActivityBase.getCameraAppViewStatus();
        if(mPhotoModule != null && !inCamPreview){
            PreviewChanged();
            return;
        }

        if(mPhotoModule == null || !mPhotoModule.mHiston ) {
            Log.e(TAG, "returning as histogram is off ");
            return;
        }

        if (mBitmap != null) {
            final Paint paint = mPaint;
            final Canvas cavas = mCanvas;
            final float border = 5;
            float graphheight = mHeight - (2 * border);
            float graphwidth = mWidth - (2 * border);
            float left,top,right,bottom;
            float bargap = 0.0f;
            float barwidth = graphwidth/STATS_SIZE;

            cavas.drawColor(0xFFAAAAAA);
            paint.setColor(Color.BLACK);

            for (int k = 0; k <= (graphheight /32) ; k++) {
                float y = (float)(32 * k)+ border;
                cavas.drawLine(border, y, graphwidth + border , y, paint);
            }
            for (int j = 0; j <= (graphwidth /32); j++) {
                float x = (float)(32 * j)+ border;
                cavas.drawLine(x, border, x, graphheight + border, paint);
            }
            synchronized(PhotoModule.statsdata) {
                 //Assumption: The first element contains
                //            the maximum value.
                int maxValue = Integer.MIN_VALUE;
                if ( 0 == PhotoModule.statsdata[0] ) {
                    for ( int i = 1 ; i <= STATS_SIZE ; i++ ) {
                         if ( maxValue < PhotoModule.statsdata[i] ) {
                             maxValue = PhotoModule.statsdata[i];
                         }
                    }
                } else {
                    maxValue = PhotoModule.statsdata[0];
                }
                mScale = ( float ) maxValue;
                for(int i=1 ; i<=STATS_SIZE ; i++)  {
                    scaled = (PhotoModule.statsdata[i]/mScale)*STATS_SIZE;
                    if(scaled >= (float)STATS_SIZE)
                        scaled = (float)STATS_SIZE;
                    left = (bargap * (i+1)) + (barwidth * i) + border;
                    top = graphheight + border;
                    right = left + barwidth;
                    bottom = top - scaled;
                    cavas.drawRect(left, top, right, bottom, mPaintRect);
                }
            }
            canvas.drawBitmap(mBitmap, 0, 0, null);
        }
        if (mPhotoModule.mHiston && mPhotoModule!= null) {
            mGraphCameraDevice = mPhotoModule.getCamera();
            if (mGraphCameraDevice != null){
                mGraphCameraDevice.sendHistogramData();
            }
        }
    }
    public void PreviewChanged() {
        invalidate();
    }
    public void setPhotoModuleObject(PhotoModule photoModule) {
        mPhotoModule = photoModule;
    }
}
