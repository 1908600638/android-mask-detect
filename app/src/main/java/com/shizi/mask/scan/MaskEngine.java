package com.shizi.mask.scan;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 口罩识别：YOLOv5 模型输出两类，「戴上了口罩」和「并未正确佩戴口罩」。
 */
public final class MaskEngine implements Scanner {

    private final AssetManager assets;
    private final YoloDetector detector = new YoloDetector();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean closed;
    private boolean ready;

    public MaskEngine(AssetManager assets) {
        this.assets = assets;
    }

    @Override
    public void scan(final Bitmap bitmap, final Callback callback) {
        if (closed) {
            return;
        }
        worker.execute(() -> {
            try {
                if (!ready) {
                    if (!detector.initMask(assets)) {
                        fail(callback, new IllegalStateException("model load failed"));
                        return;
                    }
                    ready = true;
                }
                YoloDetector.Obj[] objects = detector.detectMask(bitmap, false);

                List<Detect> detects = new ArrayList<>();
                for (YoloDetector.Obj obj : objects) {
                    detects.add(new Detect(obj.label, null, obj.prob, toRect(obj)));
                }

                final ScanOutcome outcome = new ScanOutcome(detects, describe(detects));
                final Bitmap overlay = BoxPainter.paint(bitmap, detects);
                main.post(() -> {
                    if (!closed) {
                        callback.onResult(outcome, overlay);
                    }
                });
            } catch (RuntimeException | OutOfMemoryError error) {
                fail(callback, error);
            }
        });
    }

    private void fail(final Callback callback, Throwable error) {
        main.post(() -> {
            if (!closed) {
                callback.onError(error);
            }
        });
    }

    private static Rect toRect(YoloDetector.Obj obj) {
        return new Rect((int) obj.x, (int) obj.y,
                (int) (obj.x + obj.w), (int) (obj.y + obj.h));
    }

    private static String describe(List<Detect> detects) {
        if (detects.isEmpty()) {
            return "";
        }
        int withMask = 0;
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < detects.size(); i++) {
            Detect detect = detects.get(i);
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(i + 1).append(". ").append(detect.label);
            if (detect.score > 0f) {
                builder.append("   置信度 ")
                        .append(String.format(Locale.US, "%.1f%%", detect.score * 100f));
            }
            if ("戴上了口罩".equals(detect.label)) {
                withMask++;
            }
        }
        builder.append('\n').append('\n')
                .append("共检测到 ").append(detects.size()).append(" 张人脸，其中 ")
                .append(withMask).append(" 张正确佩戴口罩");
        return builder.toString();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        worker.shutdownNow();
    }
}
