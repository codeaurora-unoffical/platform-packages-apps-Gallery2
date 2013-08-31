package org.codeaurora.gallery3d.video;

import android.content.Context;
import android.os.SystemProperties;
import android.util.Log;

import com.android.gallery3d.app.MovieActivity;
import org.codeaurora.gallery3d.ext.ActivityHookerGroup;
import org.codeaurora.gallery3d.ext.IActivityHooker;

import java.util.ArrayList;
import java.util.List;

public class ExtensionHelper {

    public static IActivityHooker getHooker(final Context context) {

        final ActivityHookerGroup group = new ActivityHookerGroup();

        if (SystemProperties.getBoolean("persist.env.video.loop", false)) {
            group.addHooker(new LoopVideoHooker()); // add it for common feature.
        }
        if (SystemProperties.getBoolean("persist.env.video.stereo", false)) {
            group.addHooker(new StereoAudioHooker()); // add it for common feature.
        }
        if (SystemProperties.getBoolean("persist.env.video.streaming", false)) {
            group.addHooker(new StreamingHooker());
            group.addHooker(new BookmarkHooker());
        }
        if (SystemProperties.getBoolean("persist.env.video.playlist", false)) {
            group.addHooker(new MovieListHooker()); // add it for common feature.
            group.addHooker(new StepOptionSettingsHooker());
        }
        return group;
    }
}
