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
package com.qcom.gallery3d.video;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.android.gallery3d.R;
import com.qcom.gallery3d.ext.QcomLog;
/**
 * Copied from com.android.setting.wifi.WifiProxyDialog.java
 * 
 */
public class DetailDialog extends AlertDialog implements DialogInterface.OnClickListener {
    private static final String TAG = "DetailDialog";
    private static final boolean LOG = true;
    
    private static final int BTN_OK = DialogInterface.BUTTON_POSITIVE;
    private final Context mContext;
    
    private View mView;
    private TextView mAuthorView;
    private TextView mTitleView;
    private TextView mCopyrightView;
    
    private final String mTitle;
    private final String mAuthor;
    private final String mCopyright;
    
    public DetailDialog(final Context context, final String title, final String author, final String copyright) {
        super(context);
        mContext = context;
        mTitle = (title == null ? "" : title);
        mAuthor = (author == null ? "" : author);
        mCopyright = (copyright == null ? "" : copyright);
        if (LOG) {
            QcomLog.v(TAG, "LimitDialog() mTitle=" + mTitle + ", mAuthor=" + mAuthor + ", mCopyRight=" + mCopyright);
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        setTitle(R.string.media_detail);
        mView = getLayoutInflater().inflate(R.layout.detail_dialog, null);
        if (mView != null) {
            setView(mView);
        }
        mTitleView = (TextView)mView.findViewById(R.id.title);
        mAuthorView = (TextView)mView.findViewById(R.id.author);
        mCopyrightView = (TextView)mView.findViewById(R.id.copyright);

        mTitleView.setText(mContext.getString(R.string.detail_title, mTitle));
        mAuthorView.setText(mContext.getString(R.string.detail_session, mAuthor));
        mCopyrightView.setText(mContext.getString(R.string.detail_copyright, mCopyright));
        setButton(BTN_OK, mContext.getString(android.R.string.ok), this);
        super.onCreate(savedInstanceState);

    }
    
    public void onClick(final DialogInterface dialogInterface, final int button) {
        
    }
}
