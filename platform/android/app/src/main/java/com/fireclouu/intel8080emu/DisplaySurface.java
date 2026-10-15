package com.fireclouu.intel8080emu;

import android.content.Context;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.concurrent.atomic.AtomicBoolean;

public class DisplaySurface extends SurfaceView implements SurfaceHolder.Callback {
    static {
        System.loadLibrary("ImGui");
    }

    public static final int DIMENSION_WIDTH = 0;
    public static final int DIMENSION_HEIGHT = 1;

    // this is intended as space invaders original scan is 90deg rotated to right
    public static final int GUEST_WIDTH = 224;
    public static final int GUEST_HEIGHT = 256;
    private static final int VRAM_SIZE = GUEST_WIDTH * GUEST_HEIGHT / 8;

    private int orientationWidth, orientationHeight;
    //    private SurfaceHolder holder;
    // written on the ui thread, read on the emulator thread
    private volatile Surface mSurface;

    // latest vram written by the emulator thread, guarded by itself
    private final byte[] frameVram = new byte[VRAM_SIZE];
    // copy handed to native on the ui thread
    private final byte[] renderVram = new byte[VRAM_SIZE];
    // at most one render queued, so a slow ui thread drops frames instead of piling them up
    private final AtomicBoolean isRenderQueued = new AtomicBoolean(false);
    private final Runnable renderRunnable = this::render;

    private Handler handler;

    public DisplaySurface(Context context) {
        super(context);
        init();
    }

    public DisplaySurface(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mSurface = holder.getSurface();
        Runnable runnable = () -> {
            nativeInit(mSurface);
        };
        handler.post(runnable);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mSurface = holder.getSurface();
        Runnable runnable = () -> {
            nativeInit(mSurface);
        };
        handler.post(runnable);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mSurface = null;
        Runnable runnable = this::nativeShutdown;
        handler.post(runnable);
    }

    private void init() {
        handler = new Handler();
        getHolder().addCallback(this);

        orientationWidth = GUEST_WIDTH;
        orientationHeight = GUEST_HEIGHT;
    }

    public int getHostMaxDimension() {
        return getWidth() > getHeight() ? DIMENSION_WIDTH : DIMENSION_HEIGHT;
    }

    public float getHostScalingValue(int orientation) {
        return orientation == DIMENSION_WIDTH ? ((float) getWidth() / (float) orientationWidth) : ((float) getHeight() / (float) orientationHeight);
    }

    public float getScaleValueLogical() {
        int maxDimension = getHostMaxDimension() == DIMENSION_WIDTH ? DIMENSION_HEIGHT : DIMENSION_WIDTH;
        return getHostScalingValue(maxDimension);
    }

    // same ui thread that renders, so it is safe to hand straight to imgui
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        nativeTouchEvent(action, event.getX(), event.getY());
        if (action == MotionEvent.ACTION_UP) performClick();
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    public void draw(short[] memoryVideoRam) {
        // emulation starts on activity create, the surface only exists once the view is laid out
        final Surface surface = mSurface;
        if (surface == null || !surface.isValid()) return;

        synchronized (frameVram) {
            for (int i = 0; i < VRAM_SIZE; i++) {
                frameVram[i] = (byte) memoryVideoRam[i];
            }
        }

        if (isRenderQueued.compareAndSet(false, true)) {
            handler.post(renderRunnable);
        }
    }

    private void render() {
        isRenderQueued.set(false);
        if (mSurface == null || !mSurface.isValid()) return;

        synchronized (frameVram) {
            System.arraycopy(frameVram, 0, renderVram, 0, VRAM_SIZE);
        }
        nativeMainLoopStep(renderVram, getScaleValueLogical());
    }

    public native int nativeInit(Surface surface);
    public native void nativeMainLoopStep(byte[] memoryVideoRam, float scale);
    public native void nativeShutdown();
    public native void nativeTouchEvent(int action, float x, float y);
}
