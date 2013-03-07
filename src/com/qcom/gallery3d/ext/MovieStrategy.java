package com.qcom.gallery3d.ext;

public class MovieStrategy implements IMovieStrategy {


    @Override
    public boolean shouldEnableCheckLongSleep() {
        return true;
    }

    @Override
    public boolean shouldEnableServerTimeout() {
        return false;
    }
    
    @Override
    public boolean shouldEnableRewindAndForward() {
        return true;
    }
}
