package com.qcom.gallery3d.ext;
/**
 * Small features option for video playback
 *
 */
public interface IMovieStrategy {

    /**
     * Enable checking server timeout or not.
     * @return
     */
    boolean shouldEnableServerTimeout();
    /**
     * Enable checking long sleep(>=180s) or not.
     * @return
     */
    boolean shouldEnableCheckLongSleep();
    /**
     * Enable rewind, forward, step option settings.
     * @return
     */
    boolean shouldEnableRewindAndForward();
}