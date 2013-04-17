package com.qcom.gallery3d.ext;

import java.util.ArrayList;
import java.util.List;

public class MovieExtension implements IMovieExtension {
	
	public static final int CMCC_EXTENSION_FUNCTIONS = 1;
	public static final int UNICOM_EXTENSION_FUNCTIONS = 2;
	
	private List<Integer> featureList = null;
	private int mFunctionType;
	
	public MovieExtension(int functionType)
	{
		mFunctionType = functionType;
		featureList = new ArrayList<Integer>();
		if(functionType == CMCC_EXTENSION_FUNCTIONS)
		{
			featureList.add(IMovieExtension.FEATURE_ENABLE_BOOKMARK);
			featureList.add(IMovieExtension.FEATURE_ENABLE_SETTINGS);
			featureList.add(IMovieExtension.FEATURE_ENABLE_STEREO_AUDIO);
			featureList.add(IMovieExtension.FEATURE_ENABLE_STREAMING);
			featureList.add(IMovieExtension.FEATURE_ENABLE_VIDEO_LIST);
		}
		else if (functionType == UNICOM_EXTENSION_FUNCTIONS)
		{
			// TODO we will coding for unicom later
		}
	}

    @Override
    public List<Integer> getFeatureList() {
        return featureList;
    }

    @Override
    public IMovieStrategy getMovieStrategy() {
        return new MovieStrategy();
    }
    
    @Override
    public IActivityHooker getHooker() {
        return null;
    }
}
