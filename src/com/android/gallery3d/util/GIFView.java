package com.android.gallery3d.util;

import com.android.gallery3d.R;

import android.content.Context;
import android.content.ContentResolver;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;

public class GIFView extends ImageView implements GifAction {

    private static final String TAG = "GIFView";

    // We try to scale up the image to fill the screen. But in order not to
    // scale too much for small icons, we limit the max up-scaling factor here.
    private static final float SCALE_LIMIT = 4;

    private static boolean isRun = false;
    private static boolean pause = true;

    private GifDecoder gifDecoder = null;
    private Bitmap currentImage = null;

    private int W;
    private int H;

    private DrawThread drawThread = null;

    Uri mUri;
    private Context mContext;

    public GIFView(Context context) {
        super(context);
        mContext = context;
    }

    public boolean setDrawable(Uri uri) {
        if (null == uri) {
            return false;
        }
        isRun = true;
        pause = false;
        mUri = uri;
        int mSize = 0;
        ContentResolver cr = mContext.getContentResolver();
        InputStream input = null;
        try {
            input = cr.openInputStream(uri);
            if (input instanceof FileInputStream) {
                FileInputStream f = (FileInputStream) input;
                mSize = (int) f.getChannel().size();
            } else {
                while (-1 != input.read()) {
                    mSize++;
                }
            }

        } catch (IOException e) {
            Log.e(TAG, "catch exception:" + e);
        }
        if (mSize == 0) {
            return false;
        }

        setGifDecoderImage(input);
        return true;
    }

    private void setGifDecoderImage(InputStream is) {
        if (gifDecoder != null) {
            gifDecoder.free();
            gifDecoder = null;
        }
        gifDecoder = new GifDecoder(is, this);
        gifDecoder.start();
    }

    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        W = ViewGifImage.dm.widthPixels;
        H = ViewGifImage.dm.heightPixels;
        if (gifDecoder == null) {
            return;
        }

        if (currentImage == null) {
            currentImage = gifDecoder.getImage();
        }
        if (currentImage == null) {
            // if this gif can not be displayed, just try to show it as jpg by parsing mUri
            setImageURI(mUri);
            return;
        }

        setImageURI(null);
        int saveCount = canvas.getSaveCount();
        canvas.save();
        canvas.translate(getPaddingLeft(), getPaddingTop());
        Rect sRect = null;
        Rect dRect = null;
        int imageHeight = currentImage.getHeight();
        int imageWidth = currentImage.getWidth();
        int width, height;
        if (imageWidth >= W || imageHeight >= H) {
            // scale-down the image
            if (imageWidth * H > W * imageHeight) {
                width = W;
                height = (imageHeight * width) / imageWidth;
            } else {
                height = H;
                width = (imageWidth * height) / imageHeight;
            }
        } else {
            // scale-up the image
            float scale = Math.min(SCALE_LIMIT, Math.min(W / (float) imageWidth, H / (float) imageHeight));
            width = (int) (imageWidth * scale);
            height = (int) (imageHeight * scale);
        }
        dRect = new Rect((W - width) / 2, (H - height) / 2, (W + width) / 2, (H + height) / 2);
        canvas.drawBitmap(currentImage, sRect, dRect, null);
        canvas.restoreToCount(saveCount);
    }

    public void parseOk(boolean parseStatus, int frameIndex) {
        if (parseStatus) {
            if (gifDecoder != null && frameIndex == -1
                    && gifDecoder.getFrameCount() > 1) {
                if (drawThread == null) {
                    drawThread = new DrawThread();
                } else {
                    drawThread = null;
                    drawThread = new DrawThread();
                }
                drawThread.start();
            }
        } else {
            Log.e(TAG, "parse error");
        }
    }

    private Handler redrawHandler = new Handler() {
        public void handleMessage(Message msg) {
            invalidate();
        }
    };

    private class DrawThread extends Thread {
        public void run() {
            if (gifDecoder == null) {
                return;
            }

            while (isRun) {
                if (pause == false) {
                    if (!isShown()) {
                        isRun = false;
                        pause = true;
                        break;
                    }
                    GifFrame frame = gifDecoder.next();
                    currentImage = frame.image;
                    long sp = frame.delay;
                    if (sp == 0) {
                        sp = 200;
                    }
                    if (redrawHandler != null) {
                        Message msg = redrawHandler.obtainMessage();
                        redrawHandler.sendMessage(msg);
                        try {
                            Thread.sleep(sp);
                        } catch (InterruptedException e) {
                            Log.e(TAG, "catch exception:" + e);
                        }
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
            isRun = true;
            pause = false;
        }
    }

    public void freeMemory() {
        isRun = false;
        pause = true;
        if (drawThread != null) {
            drawThread = null;
        }
        if (gifDecoder != null) {
            gifDecoder.free();
            gifDecoder = null;
        }
    }
}
