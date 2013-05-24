package com.android.gallery3d.filtershow.tools;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;

import com.android.gallery3d.R;

/**
 * Alert dialog builder that builds a simple Yes/Cancel dialog.
 */
public class YesCancelDialogBuilder extends AlertDialog.Builder {

    public YesCancelDialogBuilder(Context context, final Runnable yes, int messageId) {
        super(context);
        setPositiveButton(R.string.confirm, new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                yes.run();
            }
        })
        .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                // no-op
            }
        }).setMessage(messageId);
    }
}
