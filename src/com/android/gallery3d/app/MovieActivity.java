/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.gallery3d.app;

import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.AsyncQueryHandler;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ShareActionProvider;
import android.content.res.Configuration;
import android.widget.Toast;



import com.android.gallery3d.R;
import com.android.gallery3d.common.ApiHelper;
import com.android.gallery3d.common.Utils;
import com.qcom.gallery3d.ext.IActivityHooker;
import com.qcom.gallery3d.ext.IMovieItem;
import com.qcom.gallery3d.ext.MovieItem;
import com.qcom.gallery3d.ext.MovieUtils;
import com.qcom.gallery3d.ext.QcomLog;
import com.qcom.gallery3d.video.ExtensionHelper;
import com.qcom.gallery3d.video.MovieTitleHelper;

/**
 * This activity plays a video from a specified URI.
 *
 * The client of this activity can pass a logo bitmap in the intent (KEY_LOGO_BITMAP)
 * to set the action bar logo so the playback process looks more seamlessly integrated with
 * the original activity.
 */
public class MovieActivity extends Activity {
    @SuppressWarnings("unused")
    private static final String TAG = "MovieActivity";
    public static final String KEY_LOGO_BITMAP = "logo-bitmap";
    public static final String KEY_TREAT_UP_AS_BACK = "treat-up-as-back";

    private MoviePlayer mPlayer;
    private boolean mFinishOnCompletion;
    //private Uri mUri;
    private boolean mTreatUpAsBack;

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void setSystemUiVisibility(View rootView) {
        if (ApiHelper.HAS_VIEW_SYSTEM_UI_FLAG_LAYOUT_STABLE) {
            rootView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        QcomLog.v(TAG, "onCreate()");
        requestWindowFeature(Window.FEATURE_ACTION_BAR);
        requestWindowFeature(Window.FEATURE_ACTION_BAR_OVERLAY);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        setContentView(R.layout.movie_view);
        View rootView = findViewById(R.id.movie_view_root);

        setSystemUiVisibility(rootView);

        Intent intent = getIntent();
        mMovieHooker = ExtensionHelper.getHooker(this);
        initMovieInfo(intent);
        initializeActionBar(intent);
        mFinishOnCompletion = intent.getBooleanExtra(
                MediaStore.EXTRA_FINISH_ON_COMPLETION, true);
        mTreatUpAsBack = intent.getBooleanExtra(KEY_TREAT_UP_AS_BACK, false);
        mPlayer = new MoviePlayer(rootView, this, mMovieItem, savedInstanceState,
                !mFinishOnCompletion) {
            @Override
            public void onCompletion() {
                if (LOG) {
                	QcomLog.v(TAG, "onCompletion() mFinishOnCompletion=" + mFinishOnCompletion);
                }
                if (mFinishOnCompletion) {
                    finish();
                }
            }
        };
        if (intent.hasExtra(MediaStore.EXTRA_SCREEN_ORIENTATION)) {
            int orientation = intent.getIntExtra(
                    MediaStore.EXTRA_SCREEN_ORIENTATION,
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            if (orientation != getRequestedOrientation()) {
                setRequestedOrientation(orientation);
            }
        }
        Window win = getWindow();
        WindowManager.LayoutParams winParams = win.getAttributes();
        winParams.buttonBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF;
        winParams.flags |= WindowManager.LayoutParams.FLAG_FULLSCREEN;
        win.setAttributes(winParams);

        // We set the background in the theme to have the launching animation.
        // But for the performance (and battery), we remove the background here.
        win.setBackgroundDrawable(null);
        mMovieHooker.init(this, intent);
        mMovieHooker.setParameter(null, mPlayer.getMoviePlayerExt());
        mMovieHooker.setParameter(null, mMovieItem);
        mMovieHooker.setParameter(null, mPlayer.getVideoSurface());
        mMovieHooker.onCreate(savedInstanceState);
    }

    private void setActionBarLogoFromIntent(Intent intent) {
       // Bitmap logo = intent.getParcelableExtra(KEY_LOGO_BITMAP);
      //  if (logo != null) {
      //      getActionBar().setLogo(
       //             new BitmapDrawable(getResources(), logo));
      //  }
      getActionBar().setLogo(R.mipmap.ic_launcher_video);
    }

    private void initializeActionBar(Intent intent) {
        //mUri = intent.getData();
        final ActionBar actionBar = getActionBar();
        setActionBarLogoFromIntent(intent);
        actionBar.setDisplayOptions(ActionBar.DISPLAY_HOME_AS_UP,
                ActionBar.DISPLAY_HOME_AS_UP);
        /// M: show title for video playback
        actionBar.setDisplayOptions(actionBar.getDisplayOptions() | ActionBar.DISPLAY_SHOW_TITLE);

        if (LOG) {
        	QcomLog.v(TAG, "initializeActionBar() mMovieInfo=" + mMovieItem);
        }
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        boolean local = MovieUtils.isLocalFile(mMovieItem.getOriginalUri(), mMovieItem.getMimeType());
   
            getMenuInflater().inflate(R.menu.movie, menu);
            mShareMenu = menu.findItem(R.id.action_share);
            ShareActionProvider provider = (ShareActionProvider) mShareMenu.getActionProvider();
            mShareProvider = provider;
            if (mShareProvider != null) {
                /// M: share provider is singleton, we should refresh our history file.
                mShareProvider.setShareHistoryFileName(SHARE_HISTORY_FILE);
            }
            refreshShareProvider(mMovieItem);
        return mMovieHooker.onCreateOptionsMenu(menu);
        /*getMenuInflater().inflate(R.menu.movie, menu);
        ShareActionProvider provider = GalleryActionBar.initializeShareActionProvider(menu);

        // Document says EXTRA_STREAM should be a content: Uri
        // So, we only share the video if it's "content:".
        if (provider != null && ContentResolver.SCHEME_CONTENT
                .equals(mUri.getScheme())) {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("video/*");
            intent.putExtra(Intent.EXTRA_STREAM, mUri);
            provider.setShareIntent(intent);
        }

        return true;*/
    }
    
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        return mMovieHooker.onPrepareOptionsMenu(menu);
    }
    
    private Intent createShareIntent() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_STREAM, mMovieItem.getUri());
        return intent;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            if (mTreatUpAsBack) {
                finish();
            } else {
                startActivity(new Intent(this, Gallery.class));
                finish();
            }
            return true;
        } else if (id == R.id.action_share) {
            startActivity(Intent.createChooser(createShareIntent(),
                    getString(R.string.share)));
            return true;
        }
	if(mPlayer.isStateLoadingVideo()) {
	     Toast.makeText(getApplicationContext(),
                    getString(R.string.movie_could_not_show_menu_function),
                    Toast.LENGTH_LONG)
                    .show();
	    return false;
	}else {
	    return mMovieHooker.onOptionsItemSelected(item);
	}
       
    }

    @Override
    public void onStart() {
        super.onStart();
        mMovieHooker.onStart();
        registerScreenOff();
	registerShutDown();
        if (LOG) {
        	QcomLog.v(TAG, "onStart()");
        }
    }

    @Override
    protected void onStop() {
        ((AudioManager) getSystemService(AUDIO_SERVICE))
                .abandonAudioFocus(null);
        super.onStop();
        if (mControlResumed && mPlayer != null) {
            mPlayer.onStop();
            mControlResumed = false;
        }
        mMovieHooker.onStop();
        unregisterScreenOff();
	unregisterShutDown();
        if (LOG) {
        	QcomLog.v(TAG, "onStop() isKeyguardLocked=" + isKeyguardLocked()
                + ", mResumed=" + mResumed + ", mControlResumed=" + mControlResumed);
        }
    }

    @Override
    public void onPause() {
        if (LOG) {
        	QcomLog.v(TAG, "onPause() isKeyguardLocked=" + isKeyguardLocked()
                + ", mResumed=" + mResumed + ", mControlResumed=" + mControlResumed);
        }
        mResumed = false;
        if (mControlResumed && mPlayer != null) {
            mControlResumed = !mPlayer.onPause();
        }
        super.onPause();
        mMovieHooker.onPause();
    }

    @Override
    public void onResume() {
        if (LOG) {
        	QcomLog.v(TAG, "onResume() isKeyguardLocked=" + isKeyguardLocked()
                + ", mResumed=" + mResumed + ", mControlResumed=" + mControlResumed);
        }
        mResumed = true;
        if (!isKeyguardLocked() && mResumed && !mControlResumed && mPlayer != null) {
            mPlayer.onResume();
            mControlResumed = true;
        }
        enhanceActionBar();
        super.onResume();
        mMovieHooker.onResume();
    }
    
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (LOG) {
        	QcomLog.v(TAG, "onWindowFocusChanged(" + hasFocus + ") isKeyguardLocked=" + isKeyguardLocked()
                + ", mResumed=" + mResumed + ", mControlResumed=" + mControlResumed);
        }
        if (hasFocus && !isKeyguardLocked() && mResumed && !mControlResumed && mPlayer != null) {
            mPlayer.onResume();
            mControlResumed = true;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
		
        if(this.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
	    mPlayer.setDefaultScreenMode();
			
        } else if(this.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            mPlayer.setDefaultScreenMode();
        }

    }
    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mPlayer.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroy() {
    QcomLog.v(TAG, "onDestroy() ");
        mPlayer.onDestroy();
        super.onDestroy();
        mMovieHooker.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return mPlayer.onKeyDown(keyCode, event)
                || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return mPlayer.onKeyUp(keyCode, event)
                || super.onKeyUp(keyCode, event);
    }
    
    private static final boolean LOG = true;
    /// M: resume bug fix @{
    private boolean mResumed = false;
    private boolean mControlResumed = false;
    private KeyguardManager mKeyguardManager;
    private boolean isKeyguardLocked() {
        if (mKeyguardManager == null) {
            mKeyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        }
        // isKeyguardSecure excludes the slide lock case.
        boolean locked = (mKeyguardManager != null) && mKeyguardManager.inKeyguardRestrictedInputMode();
        if (LOG) {
            QcomLog.v(TAG, "isKeyguardLocked() locked=" + locked + ", mKeyguardManager=" + mKeyguardManager);
        }
        return locked;
    }
    /// @}
    
    /// M: for sdp over http @{
    private static final String VIDEO_SDP_MIME_TYPE = "application/sdp";
    private static final String VIDEO_SDP_TITLE = "rtsp://";
    private static final String VIDEO_FILE_SCHEMA = "file";
    private static final String VIDEO_MIME_TYPE = "video/*";
    private IMovieItem mMovieItem;
    
    private void initMovieInfo(Intent intent) {
        Uri original = intent.getData();
        String mimeType = intent.getType();
        if (VIDEO_SDP_MIME_TYPE.equalsIgnoreCase(mimeType)
                && VIDEO_FILE_SCHEMA.equalsIgnoreCase(original.getScheme())) {
            mMovieItem = new MovieItem(VIDEO_SDP_TITLE + original, mimeType, null);
        } else {
            mMovieItem = new MovieItem(original, mimeType, null);
        }
        mMovieItem.setOriginalUri(original);
        if (LOG) {
        	QcomLog.v(TAG, "initMovieInfo(" + original + ") mMovieInfo=" + mMovieItem);
        }
    }
    /// @}
    
    /// M:for live streaming. @{
    //we do not stop live streaming when other dialog overlays it.
    private BroadcastReceiver mScreenOffReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (LOG) {
            	QcomLog.v(TAG, "onReceive(" + intent.getAction() + ") mControlResumed=" + mControlResumed);
            }
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                //Only stop video.
                if (mControlResumed) {
                    mPlayer.onStop();
                    mControlResumed = false;
                }
            }
        }
        
    };

     /// M:for live streaming. @{
    //we do not stop live streaming when other dialog overlays it.
    private BroadcastReceiver mShutDownReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (LOG) {
            	QcomLog.v(TAG, "onReceive(" + intent.getAction() + ") mControlResumed=" + mControlResumed);
            }
            if (Intent.ACTION_SHUTDOWN.equals(intent.getAction())) {
	          MovieActivity.this.finish();
            }
        }
        
    };
    
    private void registerScreenOff() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mScreenOffReceiver, filter);
    }

    private void registerShutDown() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SHUTDOWN);
        registerReceiver(mShutDownReceiver, filter);
    }
    
    private void unregisterScreenOff() {
        unregisterReceiver(mScreenOffReceiver);
    }

    private void unregisterShutDown() {
        unregisterReceiver(mShutDownReceiver);
    }
    /// @}
    
    /// M: enhance the title feature @{
    private void enhanceActionBar() {
        final IMovieItem movieItem = mMovieItem;//remember original item
        final Uri uri = mMovieItem.getUri();
        final String scheme = mMovieItem.getUri().getScheme();
        final String authority = mMovieItem.getUri().getAuthority();
        new AsyncTask<Void, Void, String>() {
            @Override
            protected String doInBackground(Void... params) {
                String title = null;
                if (ContentResolver.SCHEME_FILE.equals(scheme)) { //from file manager
                    title = MovieTitleHelper.getTitleFromMediaData(MovieActivity.this, uri);
                } else if (ContentResolver.SCHEME_CONTENT.equals(scheme)) {
                    title = MovieTitleHelper.getTitleFromDisplayName(MovieActivity.this, uri);
                    if (title == null) {
                        title = MovieTitleHelper.getTitleFromData(MovieActivity.this, uri);
                    }
                }
                if (title == null) {
                    title = MovieTitleHelper.getTitleFromUri(uri);
                }
                if (LOG) {
                	QcomLog.v(TAG, "enhanceActionBar() task return " + title);
                }
                return title;
            }
            @Override
            protected void onPostExecute(String result) {
                if (LOG) {
                	QcomLog.v(TAG, "onPostExecute(" + result + ") movieItem=" + movieItem + ", mMovieItem=" + mMovieItem);
                }
                movieItem.setTitle(result);
                if (movieItem == mMovieItem) {
                    setActionBarTitle(result);
                }
            };
        }.execute();
        if (LOG) {
        	QcomLog.v(TAG, "enhanceActionBar() " + mMovieItem);
        }
    }
    
    public void setActionBarTitle(String title) {
        if (LOG) {
        	QcomLog.v(TAG, "setActionBarTitle(" + title + ")");
        }
        ActionBar actionBar = getActionBar();
        if (title != null) {
            actionBar.setTitle(title);
        }
    }
    /// @}

    public void refreshMovieInfo(IMovieItem info) {
        mMovieItem = info;
        setActionBarTitle(info.getTitle());
        refreshShareProvider(info);
        mMovieHooker.setParameter(null, mMovieItem);
        if (LOG) {
        	QcomLog.v(TAG, "refreshMovieInfo(" + info + ")");
        }
    }

    private ShareActionProvider mShareProvider;
    private void refreshShareProvider(IMovieItem info) {
        // Document says EXTRA_STREAM should be a content: Uri
        // So, we only share the video if it's "content:".
        /// M: the upper is JellyBean's comment, here we enhance the share action.
        if (mShareProvider != null) {
            Intent intent = new Intent(Intent.ACTION_SEND);
            if (MovieUtils.isLocalFile(info.getUri(), info.getMimeType())) {
                intent.setType("video/*");
                intent.putExtra(Intent.EXTRA_STREAM, info.getUri());
            } else {
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, String.valueOf(info.getUri()));
            }
            mShareProvider.setShareIntent(intent);
        }
        if (LOG) {
        	QcomLog.v(TAG, "refreshShareProvider() mShareProvider=" + mShareProvider);
        }
    }
    
    /* M: ActivityChooseView's popup window will not dismiss
     * when user press power key off and on quickly.
     * Here dismiss the popup window if need.
     * Note: dismissPopup() will check isShowingPopup().
     * @{
     */
    private MenuItem mShareMenu;
    
    /// M: share history file name
    private static final String SHARE_HISTORY_FILE = "video_share_history_file";
    
    private IActivityHooker mMovieHooker;
}
