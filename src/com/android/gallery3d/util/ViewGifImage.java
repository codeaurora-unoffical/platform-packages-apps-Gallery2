package com.android.gallery3d.util;

import com.android.gallery3d.R;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.drm.OmaDrmHelper;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.DisplayMetrics;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageView;
import android.widget.LinearLayout;

public class ViewGifImage extends Activity {
    private static final String TAG       = "ViewGifImage";
    public  static final String VIEW_GIF_ACTION = "com.android.gallery3d.VIEW_GIF";

    public  static DisplayMetrics mDM;

    private ImageView mGifView;

    private DialogInterface.OnClickListener mDrmErrorDialogListner =
            new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            finish();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.view_gif_image);
        mDM = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(mDM);
        if (getIntent().getAction() != null
                && getIntent().getAction().equals(VIEW_GIF_ACTION)) {
            Uri gifUri = getIntent().getData();
            showGifPicture(gifUri);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        finish();
    }

    @Override
    protected void onDestroy() {
        if (mGifView != null && mGifView instanceof GIFView) {
            ((GIFView) mGifView).freeMemory();
            mGifView = null;
        }
        super.onDestroy();
    }

    private void showGifPicture(Uri uri) {
        mGifView = new GIFView(this);
        ((LinearLayout) findViewById(R.id.image_absoluteLayout)).addView(mGifView,
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        if (!OmaDrmHelper.validateLicense(this,
                OmaDrmHelper.getFilePath(this, uri), uri, "image/gif",
                mDrmErrorDialogListner, mDrmErrorDialogListner)) {
            return;
        }

        if (((GIFView) mGifView).setDrawable(uri)) return;
        
        finish();

    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        getWindowManager().getDefaultDisplay().getMetrics(mDM);
        super.onConfigurationChanged(newConfig);
    }
}
