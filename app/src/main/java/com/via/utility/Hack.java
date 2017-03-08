package com.via.utility;

import android.util.Log;
import android.view.Surface;

/**
 * Created by hankwu on 3/8/17.
 */

public class Hack {
    public Hack() {
        Log.d("HACK", "Load library!!");
        System.loadLibrary("hack");
        Log.d("HACK", "Load library Done!!");

    }
    public static native void setSurfaceBufferCount(Surface s, int count);
}
