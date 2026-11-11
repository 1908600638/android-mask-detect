package com.shizi.mask.scan;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

public final class YoloDetector {

    public class Obj {
        public float x;
        public float y;
        public float w;
        public float h;
        public String label;
        public float prob;
    }

    public native boolean initMask(AssetManager assets);

    public native Obj[] detectMask(Bitmap bitmap, boolean useGpu);

    static {
        System.loadLibrary("yolov5ncnn");
    }
}
