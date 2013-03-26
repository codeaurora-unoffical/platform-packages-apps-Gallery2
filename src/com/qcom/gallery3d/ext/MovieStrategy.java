package com.qcom.gallery3d.ext;

public class MovieStrategy implements IMovieStrategy {


    @Override
    public boolean shouldEnableCheckLongSleep() {
        return true;
    }

    @Override
    public boolean shouldEnableServerTimeout() {
        return true;
    }
    
    @Override
    public boolean shouldEnableRewindAndForward() {
        return true;
    }
}
