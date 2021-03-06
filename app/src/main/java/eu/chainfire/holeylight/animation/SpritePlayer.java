/*
 * Copyright (C) 2019 Jorrit "Chainfire" Jongma
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package eu.chainfire.holeylight.animation;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Choreographer;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;

@SuppressWarnings({ "deprecation", "FieldCanBeLocal", "unused", "UnusedReturnValue" })
public class SpritePlayer extends RelativeLayout {
    public enum Mode { SWIRL, BLINK, SINGLE }

    public interface OnSpriteSheetNeededListener {
        SpriteSheet onSpriteSheetNeeded(int width, int height, Mode mode);
    }

    public interface OnAnimationListener {
        boolean onAnimationFrameStart(boolean draw);
        void onAnimationFrameEnd(boolean draw);
        boolean onAnimationComplete();
    }

    private final Object sync = new Object();

    private final HandlerThread handlerThreadRender;
    private final HandlerThread handlerThreadLoader;
    private final Handler handlerRender;
    private final Handler handlerLoader;
    private final Handler handlerMain;
    private Choreographer choreographer;

    private final SurfaceView surfaceView;

    private OnSpriteSheetNeededListener onSpriteSheetNeededListener = null;
    private OnAnimationListener onAnimationListener = null;

    private int frame = -1;
    private SpriteSheet spriteSheetSwirl = null;
    private SpriteSheet spriteSheetBlink = null;
    private SpriteSheet spriteSheetSingle = null;
    private int spriteSheetLoading = 0;
    private volatile Point lastSpriteSheetRequest = new Point(0, 0);
    private Rect dest = new Rect();
    private Rect destDouble = new Rect();
    private Paint paint = new Paint();
    private boolean surfaceInvalidated = true;
    private boolean draw = false;
    private boolean wanted = false;
    private int width = -1;
    private int height = -1;
    private int[] colors = null;
    private float speed = 1.0f;
    private Mode drawMode = Mode.SWIRL;
    private boolean drawBackground = false;

    public SpritePlayer(Context context) {
        super(context);

        handlerThreadRender = new HandlerThread("SpritePlayer#Render");
        handlerThreadRender.start();
        handlerRender = new Handler(handlerThreadRender.getLooper());
        handlerThreadLoader = new HandlerThread("SpritePlayer#Loader");
        handlerThreadLoader.start();
        handlerLoader = new Handler(handlerThreadLoader.getLooper());
        handlerMain = new Handler();

        paint.setAntiAlias(false);
        paint.setDither(false);
        paint.setFilterBitmap(false);

        handlerRender.post(() -> {
            Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
            choreographer = Choreographer.getInstance();
        });
        
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        surfaceView = new SurfaceView(context);
        surfaceView.getHolder().setFormat(PixelFormat.RGBA_8888);
        surfaceView.getHolder().addCallback(surfaceCallback);
        surfaceView.setVisibility(View.VISIBLE);
        surfaceView.setLayoutParams(new RelativeLayout.LayoutParams(params));
        addView(surfaceView);

        while (choreographer == null) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                // no action
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        handlerThreadRender.quitSafely();
        handlerThreadLoader.quitSafely();
        super.finalize();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        evaluate();
    }

    private SurfaceHolder.Callback2 surfaceCallback = new SurfaceHolder.Callback2() {
        @Override
        public void surfaceRedrawNeeded(SurfaceHolder holder) {
            surfaceInvalidated = true;
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            surfaceInvalidated = true;
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            synchronized (sync) {
                SpritePlayer.this.width = width;
                SpritePlayer.this.height = height;
                callOnSpriteSheetNeeded(width, height);
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            cancelNextFrame();
        }
    };

    private boolean colorsChanged(int[] lastColors) {
        if ((lastColors == null) != (colors == null)) return true;
        if (lastColors == null) return false;
        if (lastColors.length != colors.length) return true;
        if (lastColors.length == 0) return false;
        for (int i = 0; i < lastColors.length; i++) {
            if (lastColors[i] != colors[i]) return true;
        }
        return false;
    }

    private void renderFrame(Canvas canvas, SpriteSheet spriteSheet, int frame) {
        if (drawBackground) {
            canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC);
        } else if (!canvas.isHardwareAccelerated()) {
            // on hardware accelerated canvas the content is already cleared
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        }
        if (spriteSheet != null) {
            paint.setXfermode(null);
            paint.setColor(Color.WHITE);
            SpriteSheet.Sprite sprite = spriteSheet.getFrame(frame);
            Bitmap bitmap = sprite.getBitmap();
            if ((colors != null) && (colors.length == 1)) {
                // fast single-color mode
                paint.setColorFilter(new PorterDuffColorFilter(colors[0], PorterDuff.Mode.SRC_ATOP));
                if (!bitmap.isRecycled()) {
                    canvas.drawBitmap(sprite.getBitmap(), sprite.getArea(), dest, paint);
                }
            } else {
                // slower multi-colored mode
                paint.setColorFilter(null);
                if (!bitmap.isRecycled()) {
                    canvas.drawBitmap(sprite.getBitmap(), sprite.getArea(), dest, paint);
                }

                paint.setXfermode(new PorterDuffXfermode(drawBackground ? PorterDuff.Mode.MULTIPLY : PorterDuff.Mode.SRC_ATOP));

                float startAngle = 0;
                float anglePerColor = 360f / colors.length;
                for (int i = 0; i < colors.length; i++) {
                    // we use double size here because the arc may cut off the larger S10+ animation otherwise
                    paint.setColor(colors[i]);
                    canvas.drawArc(destDouble.left, destDouble.top, destDouble.right, destDouble.bottom, startAngle + 270 + (anglePerColor * i), anglePerColor, true, paint);
                }
            }
        }
    }

    private Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        private long startTimeNanos = 0;
        private int lastFrameDrawn = -1;
        private int[] lastColors = null;

        @Override
        public void doFrame(long frameTimeNanos) {
            synchronized (sync) {
                SpriteSheet spriteSheet = getSpriteSheet();
                if (draw) {
                    if (spriteSheet == null) {
                        // Software canvas 2x quicker than hardware during tests
                        Canvas canvas = surfaceView.getHolder().lockCanvas();
                        if (canvas != null) {
                            try {
                                renderFrame(canvas, null, 0);
                            } finally {
                                try {
                                    surfaceView.getHolder().unlockCanvasAndPost(canvas);
                                } catch (IllegalStateException e) {
                                    // no action
                                }
                            }
                        }
                    } else {
                        if (frame == -1) {
                            startTimeNanos = frameTimeNanos;
                            frame = 0;
                        } else {
                            double frameTime = (double)1000000000 / ((double)spriteSheet.getFrameRate() * (double)speed);
                            frame = (int)Math.floor((double)(frameTimeNanos - startTimeNanos)/frameTime);
                        }

                        int drawFrame = Math.max(Math.min(frame, spriteSheet.getFrames() - 1), 0);
                        boolean doDraw = ((drawFrame != lastFrameDrawn) || colorsChanged(lastColors) || surfaceInvalidated);
                        if (onAnimationListener != null) {
                            doDraw = onAnimationListener.onAnimationFrameStart(doDraw);
                        }
                        if (doDraw) {
                            surfaceInvalidated = false;
                            lastFrameDrawn = drawFrame;
                            lastColors = colors;

                            // Software canvas 2x quicker than hardware during tests
                            Canvas canvas = surfaceView.getHolder().lockCanvas();
                            if (canvas != null) {
                                try {
                                    renderFrame(canvas, spriteSheet, drawFrame);
                                } finally {
                                    try {
                                        surfaceView.getHolder().unlockCanvasAndPost(canvas);
                                    } catch (IllegalStateException e) {
                                        // no action
                                    }
                                }
                            }
                        }
                        if (onAnimationListener != null) {
                            onAnimationListener.onAnimationFrameEnd(doDraw);
                        }
                        if (frame >= spriteSheet.getFrames()) {
                            frame = -1;
                            if ((onAnimationListener == null) || !onAnimationListener.onAnimationComplete()) {
                                draw = false;
                            }
                        }
                    }
                }
                if (draw) callNextFrame();
            }
        }
    };

    private void cancelNextFrame() {
        choreographer.removeFrameCallback(frameCallback);
    }

    private void callNextFrame() {
        cancelNextFrame();
        choreographer.postFrameCallback(frameCallback);
    }

    private void callOnSpriteSheetNeeded(int width, int height) {
        synchronized (sync) {
            if (onSpriteSheetNeededListener == null) return;
            if (
                (spriteSheetSwirl != null) && (spriteSheetSwirl.getWidth() == width) && (spriteSheetSwirl.getHeight() == height) &&
                (spriteSheetBlink != null) && (spriteSheetBlink.getWidth() == width) && (spriteSheetBlink.getHeight() == height) &&
                (spriteSheetSingle != null) && (spriteSheetSingle.getWidth() == width) && (spriteSheetSingle.getHeight() == height)
            ) return;
            if ((lastSpriteSheetRequest.x == width) && (lastSpriteSheetRequest.y == height)) return;
            lastSpriteSheetRequest.set(width, height);
            resetSpriteSheet(null);
            dest.set(0, 0, width, height);
            destDouble.set(dest.centerX() - width, dest.centerY() - height, dest.centerX() + width, dest.centerY() + height);
            spriteSheetLoading++;
            handlerLoader.post(() -> {
                OnSpriteSheetNeededListener listener;
                synchronized (sync) {
                    listener = onSpriteSheetNeededListener;
                }
                if (listener != null) {
                    SpriteSheet spriteSheetSwirl = listener.onSpriteSheetNeeded(width, height, Mode.SWIRL);
                    SpriteSheet spriteSheetBlink = listener.onSpriteSheetNeeded(width, height, Mode.BLINK);
                    SpriteSheet spriteSheetSingle = listener.onSpriteSheetNeeded(width, height, Mode.SINGLE);
                    synchronized (sync) {
                        setSpriteSheet(spriteSheetSwirl, Mode.SWIRL);
                        setSpriteSheet(spriteSheetBlink, Mode.BLINK);
                        setSpriteSheet(spriteSheetSingle, Mode.SINGLE);
                        spriteSheetLoading--;
                        surfaceInvalidated = true;
                        evaluate();
                    }
                }
            });
        }
    }

    public void setOnSpriteSheetNeededListener(OnSpriteSheetNeededListener onSpriteSheetNeededListener) {
        synchronized (sync) {
            if (this.onSpriteSheetNeededListener == onSpriteSheetNeededListener) return;

            this.onSpriteSheetNeededListener = onSpriteSheetNeededListener;
            if (
                    (width != -1) && (height != -1) &&
                    !((spriteSheetSwirl != null) && (spriteSheetSwirl.getWidth() == width) && spriteSheetSwirl.getHeight() == height) &&
                    !((spriteSheetBlink != null) && (spriteSheetBlink.getWidth() == width) && spriteSheetBlink.getHeight() == height)
            ) {
                callOnSpriteSheetNeeded(width, height);
            }
        }
    }

    public void setOnAnimationListener(OnAnimationListener onAnimationListener) {
        this.onAnimationListener = onAnimationListener;
    }

    private void resetSpriteSheet(Mode mode) {
        synchronized (sync) {
            if ((mode == null) || (drawMode == mode)) {
                frame = -1;
            }
            if ((mode == null) || (mode == Mode.SWIRL)) {
                SpriteSheet old = spriteSheetSwirl;
                spriteSheetSwirl = null;
                if (old != null) old.recycle();
            }
            if ((mode == null) || (mode == Mode.BLINK)) {
                SpriteSheet old = spriteSheetBlink;
                spriteSheetBlink = null;
                if (old != null) old.recycle();
            }
            if ((mode == null) || (mode == Mode.SINGLE)) {
                SpriteSheet old = spriteSheetSingle;
                spriteSheetSingle = null;
                if (old != null) old.recycle();
            }
            if ((mode == null) || (drawMode == mode)) {
                surfaceInvalidated = true;
                try {
                    Canvas canvas = surfaceView.getHolder().lockCanvas();
                    try {
                        if (!canvas.isHardwareAccelerated()) {
                            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                        }
                    } finally {
                        surfaceView.getHolder().unlockCanvasAndPost(canvas);
                    }
                } catch (Throwable t) {
                    // ...
                }
            }
        }
    }

    public void setSpriteSheet(SpriteSheet spriteSheet, Mode mode) {
        synchronized (sync) {
            if (
                    ((mode == Mode.SWIRL) && (spriteSheet == this.spriteSheetSwirl)) ||
                    ((mode == Mode.BLINK) && (spriteSheet == this.spriteSheetBlink)) ||
                    ((mode == Mode.SINGLE) && (spriteSheet == this.spriteSheetSingle))
            ) return;
            resetSpriteSheet(mode);
            switch (mode) {
                case SWIRL:
                    this.spriteSheetSwirl = spriteSheet;
                    break;
                case BLINK:
                    this.spriteSheetBlink = spriteSheet;
                    break;
                case SINGLE:
                    this.spriteSheetSingle = spriteSheet;
                    break;
            }
            evaluate();
        }
    }

    public void setColors(int[] colors) {
        synchronized (sync) {
            this.colors = colors;
            surfaceInvalidated = true;
        }
    }

    private void startUpdating() {
        synchronized (sync) {
            draw = true;
            callNextFrame();
        }
    }

    private void stopUpdating() {
        synchronized (sync) {
            draw = false;
            cancelNextFrame();
        }
    }

    private void evaluate() {
        synchronized (sync) {
            if (wanted && ((getSpriteSheet() != null) || (spriteSheetLoading > 0)) && (getWindowVisibility() == View.VISIBLE) && (getVisibility() == View.VISIBLE)) {
                startUpdating();
            } else {
                stopUpdating();
            }
        }
    }

    public void playAnimation() {
        synchronized (sync) {
            wanted = true;
            frame = -1;
            evaluate();
        }
    }

    public void cancelAnimation() {
        synchronized (sync) {
            wanted = false;
            evaluate();
        }
    }

    public boolean isAnimating() {
        return draw;
    }

    public void setSpeed(float speed) {
        synchronized (sync) {
            frame = -1;
            this.speed = speed;
        }
    }

    public Mode getMode() {
        return drawMode;
    }

    public void setMode(Mode mode) {
        synchronized (sync) {
            if (mode != drawMode) {
                frame = -1;
                drawMode = mode;
                surfaceInvalidated = true;
                evaluate();
            }
        }
    }

    private SpriteSheet getSpriteSheet() {
        synchronized (sync) {
            switch (drawMode) {
                case SWIRL: return spriteSheetSwirl;
                case BLINK: return spriteSheetBlink;
                case SINGLE: return spriteSheetSingle;
            }
            return null;
        }
    }

    public void updateDisplayArea(int x, int y, int width, int height) {
        synchronized (sync) {
            RelativeLayout.LayoutParams params;

            params = (RelativeLayout.LayoutParams)surfaceView.getLayoutParams();
            params.leftMargin = x;
            params.topMargin = y;
            params.width = width;
            params.height = height;
            surfaceView.setLayoutParams(params);

            this.width = width;
            this.height = height;

            callOnSpriteSheetNeeded(width, height);
        }
    }

    public void setDrawBackground(boolean drawBackground) {
        synchronized (sync) {
            if (this.drawBackground != drawBackground) {
                this.drawBackground = drawBackground;
                surfaceInvalidated = true;
            }
        }
    }

    public Object getSynchronizer() {
        return sync;
    }
}
