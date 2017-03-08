package com.polarxiong.videotoimages;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.ImageWriter;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import net.openhft.affinity.AffinityLock;
import net.openhft.affinity.AffinityStrategy;
import net.openhft.affinity.AffinitySupport;
import net.openhft.affinity.AffinityThreadFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;

import static android.os.Build.VERSION.SDK;

/**
 * Created by zhantong on 16/5/12.
 */
public class VideoToFrames implements Runnable {
    private static final String TAG = "VideoToFrames";
    private static final boolean VERBOSE = false;
    private static final long DEFAULT_TIMEOUT_US = 10000;

    private static final int COLOR_FormatI420 = 1;
    private static final int COLOR_FormatNV21 = 2;


    private final int decodeColorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;

    private LinkedBlockingQueue<byte[]> mQueue;
    private OutputImageFormat outputImageFormat;
    private String OUTPUT_DIR;
    private boolean stopDecode = false;

    private String videoFilePath;
    private Throwable throwable;
    private Thread childThread;

    private Callback callback;

    boolean sawOutputEOS = false;

    public interface Callback {
        void onFinishDecode();

        void onDecodeFrame(int index);

        void onFrameReady(ByteBuffer buffer, int width, int height);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void stopDecode() {
        stopDecode = true;
    }

    public void decode(String videoFilePath) throws Throwable {
        this.videoFilePath = videoFilePath;
        if (childThread == null) {
            childThread = new Thread(this, "decode");
            childThread.start();
            if (throwable != null) {
                throw throwable;
            }
        }
    }

    public void run() {
        try {
            videoDecode(videoFilePath);
        } catch (Throwable t) {
            throwable = t;
        }
    }

    public void videoDecode(String videoFilePath) throws IOException {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        try {
            File videoFile = new File(videoFilePath);
            extractor = new MediaExtractor();
            extractor.setDataSource(videoFile.toString());
            int trackIndex = selectTrack(extractor);
            if (trackIndex < 0) {
                throw new RuntimeException("No video track found in " + videoFilePath);
            }
            extractor.selectTrack(trackIndex);
            MediaFormat mediaFormat = extractor.getTrackFormat(trackIndex);
            String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime);
            showSupportedColorFormat(decoder.getCodecInfo().getCapabilitiesForType(mime));
            if (isColorFormatSupported(decodeColorFormat, decoder.getCodecInfo().getCapabilitiesForType(mime))) {
                mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, decodeColorFormat);
                Log.i(TAG, "set decode color format to type " + decodeColorFormat);
            } else {
                Log.i(TAG, "unable to set decode color format, color format type " + decodeColorFormat + " not supported");
            }

            width = mediaFormat.getInteger(MediaFormat.KEY_WIDTH);
            height = mediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
            decodeFrames(decoder, extractor, mediaFormat);

            decoder.stop();
        } finally {
            Log.d(TAG, "finally");
            if (decoder != null) {
                decoder.stop();
                decoder.release();
                decoder = null;
            }
            if (extractor != null) {
                extractor.release();
                extractor = null;
            }
        }
    }

    private void showSupportedColorFormat(MediaCodecInfo.CodecCapabilities caps) {
        System.out.print("supported color format: ");
        int i =0;
        for (int c : caps.colorFormats) {
            System.out.print(c + "\t");
        }
        System.out.println();
    }

    private boolean isColorFormatSupported(int colorFormat, MediaCodecInfo.CodecCapabilities caps) {
        for (int c : caps.colorFormats) {
            if (c == colorFormat) {
                return true;
            }
        }
        return false;
    }
    int outputFrameCount = 0;
    int width = 0;//mediaFormat.getInteger(MediaFormat.KEY_WIDTH);
    int height = 0;//mediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
    ByteBuffer[] inputByteBuffers = null;
    ByteBuffer[] outputByteBuffers = null;
    boolean bDisplaying = false;

    private void decodeFrames(final MediaCodec decoder, MediaExtractor extractor, MediaFormat mediaFormat) {
        boolean sawInputEOS = false;
        sawOutputEOS = false;
        decoder.configure(mediaFormat, null, null, 0);
        decoder.start();

        if(!checkAPI21()) {
            inputByteBuffers = decoder.getInputBuffers();
            outputByteBuffers = decoder.getOutputBuffers();
        }

        while (!sawOutputEOS && !stopDecode) {
            if (!sawInputEOS) {
                int inputBufferId = decoder.dequeueInputBuffer(DEFAULT_TIMEOUT_US);
                if (inputBufferId >= 0) {
                    ByteBuffer inputBuffer = null;
                    if (checkAPI21()) {
                        inputBuffer = decoder.getInputBuffer(inputBufferId);
                    } else {
                        inputBuffer = inputByteBuffers[inputBufferId];
                    }

                    int sampleSize = extractor.readSampleData(inputBuffer, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputBufferId, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sawInputEOS = true;
                    } else {
                        long presentationTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inputBufferId, 0, sampleSize, presentationTimeUs, 0);
                        extractor.advance();
                    }
                }

            }

            if(displayThread==null) {
                displayThread = new Thread(new Runnable() {
                    @Override
                    public void run() {

                        while (!sawOutputEOS && !stopDecode) {
                            long start = System.currentTimeMillis();
                            frameDisplay(decoder);
                            long diff = System.currentTimeMillis()-start;
                            if(diff<33) {
                                try {
                                    Thread.sleep(33-diff);
                                } catch (Exception e) {

                                }
                            }
                        }
                    }
                });
                displayThread.start();
            }
        }

        if (callback != null) {
            callback.onFinishDecode();
        }
    }

    Thread displayThread = null;

    private void frameDisplay(MediaCodec decoder) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();


        int outputBufferId = decoder.dequeueOutputBuffer(info, DEFAULT_TIMEOUT_US);
        if (outputBufferId >= 0) {
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                sawOutputEOS = true;
            }
            boolean doRender = (info.size != 0);
            if (doRender) {
                outputFrameCount++;
                if (callback != null) {
                    callback.onDecodeFrame(outputFrameCount);
                }
                ByteBuffer b = null;
                if(checkAPI21()) {
                    b = decoder.getOutputBuffer(outputBufferId);
                } else {
                    b = outputByteBuffers[outputBufferId];
                }

                if(callback!=null) {
                    callback.onFrameReady(b, width, height);
                }

                decoder.releaseOutputBuffer(outputBufferId, false);
            }
        }
    }

    private boolean checkAPI21() {
        return android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    private static int selectTrack(MediaExtractor extractor) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                if (VERBOSE) {
                    Log.d(TAG, "Extractor selected track " + i + " (" + mime + "): " + format);
                }
                return i;
            }
        }
        return -1;
    }

    private static boolean isImageFormatSupported(Image image) {
        int format = image.getFormat();
        switch (format) {
            case ImageFormat.YUV_420_888:
            case ImageFormat.NV21:
            case ImageFormat.YV12:
                return true;
        }
        return false;
    }

    private static void dumpFile(String fileName, byte[] data) {
        FileOutputStream outStream;
        try {
            outStream = new FileOutputStream(fileName);
        } catch (IOException ioe) {
            throw new RuntimeException("Unable to create output file " + fileName, ioe);
        }
        try {
            outStream.write(data);
            outStream.close();
        } catch (IOException ioe) {
            throw new RuntimeException("failed writing data to file " + fileName, ioe);
        }
    }

}
