/*
 * Copyright (c) 2013 The Linux Foundation. All rights reserved.
 * Not a Contribution.
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

public class MovieHookIntentReceiver extends BroadcastReceiver {

    public static final String ACTION_MEDIA_BUTTON = "media_button";
    public static final String KEYEVENT = "keyevent";

    @Override
    public void onReceive(Context context, Intent intent) {
        String intentAction = intent.getAction();
        if (Intent.ACTION_MEDIA_BUTTON.equals(intentAction)) {
            KeyEvent keyEvent = (KeyEvent) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            Intent mediaButtonIntent = new Intent();
            mediaButtonIntent.putExtra(KEYEVENT, keyEvent);
            mediaButtonIntent.setAction(ACTION_MEDIA_BUTTON);
            context.sendBroadcast(mediaButtonIntent);
        }
    }

}
