package com.via.utility;

import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * Created by hankwu on 3/8/17.
 */

public class NativeRender {
    public NativeRender()
    {
        System.loadLibrary("viautilty");
    }

    public static native void renderingNV21ToSurface(Surface s, byte[] pixelBytes, int width, int height, int size);
    public static native void renderingNV21ToSurface2(Surface s, ByteBuffer pixelBytes, int width, int height, int size);
}
