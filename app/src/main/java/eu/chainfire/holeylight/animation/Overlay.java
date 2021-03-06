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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;

import eu.chainfire.holeylight.BuildConfig;
import eu.chainfire.holeylight.misc.AODControl;
import eu.chainfire.holeylight.misc.Battery;
import eu.chainfire.holeylight.misc.Display;
import eu.chainfire.holeylight.misc.Settings;
import eu.chainfire.holeylight.service.AccessibilityService;
import eu.chainfire.holeylight.ui.DetectCutoutActivity;

import static android.content.Context.KEYGUARD_SERVICE;
import static android.content.Context.POWER_SERVICE;

@SuppressWarnings({"WeakerAccess", "unused", "FieldCanBeLocal"})
public class Overlay {
    private static Overlay instance;
    public static Overlay getInstance(Context context) {
        return getInstance(context, null);
    }
    public static Overlay getInstance(Context context, IBinder windowToken) {
        synchronized (Overlay.class) {
            if (instance == null) {
                instance = new Overlay(context);
            }
            if (context instanceof AccessibilityService) {
                instance.initActualOverlay(context, windowToken);
            }
            return instance;
        }
    }

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() == null) return;

            switch (intent.getAction()) {
                case Intent.ACTION_CONFIGURATION_CHANGED:
                    Point resolutionNow = getResolution();
                    if (
                            ((resolutionNow.x != resolution.x) || (resolutionNow.y != resolution.y)) &&
                            ((resolutionNow.x != resolution.y) || (resolutionNow.y != resolution.x))
                    ) {
                        // Resolution changed
                        // This is an extremely ugly hack, don't try this at home
                        // There are some internal states that are hard to figure out, including
                        // oddities with Lottie's renderer. We just hard exit and let Android
                        // restart us.
                        resolution = resolutionNow;
                        Intent start = new Intent(context, DetectCutoutActivity.class);
                        start.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(start);
                        handler.postDelayed(() -> {
                            Context context1 = spritePlayer.getContext();
                            AlarmManager alarmManager = (AlarmManager) context1.getSystemService(Service.ALARM_SERVICE);
                            alarmManager.setExactAndAllowWhileIdle(
                                    AlarmManager.ELAPSED_REALTIME,
                                    SystemClock.elapsedRealtime() + 1000,
                                    PendingIntent.getActivity(
                                            context1,
                                            0,
                                            new Intent(context1, DetectCutoutActivity.class),
                                            0
                                    )
                            );
                            System.exit(0);
                        }, 1000);
                    } else {
                        updateParams();
                    }
                    break;
                case Intent.ACTION_SCREEN_ON:
                case Intent.ACTION_USER_PRESENT:
                case Intent.ACTION_POWER_CONNECTED:
                case Intent.ACTION_POWER_DISCONNECTED:
                    evaluate();
                    break;
                case Intent.ACTION_SCREEN_OFF:
                    if (settings.isHideAOD()) {
                        cpuWakeLock.acquire(10000);
                    }
                    evaluate();
                    break;
            }
        }
    };

    private final WindowManager windowManager;
    private final KeyguardManager keyguardManager;
    private final Handler handler;
    private final Settings settings;

    private SpritePlayer spritePlayer;

    private NotificationAnimation animation;

    private int[] colors = new int[0];
    private boolean wanted = false;
    private boolean kill = false;
    private boolean lastState = false;
    private int[] lastColors = new int[0];
    private SpritePlayer.Mode lastMode = SpritePlayer.Mode.SWIRL;
    private int lastDpAdd = 0;
    private boolean added = false;
    private Point resolution;
    private IBinder windowToken;
    private PowerManager.WakeLock cpuWakeLock;
    private ContentResolver resolver;

    private Overlay(Context context) {
        windowManager = (WindowManager)context.getSystemService(Activity.WINDOW_SERVICE);
        keyguardManager = (KeyguardManager)context.getSystemService(KEYGUARD_SERVICE);
        handler = new Handler();
        settings = Settings.getInstance(context);
        resolution = getResolution();
        cpuWakeLock = ((PowerManager)context.getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, BuildConfig.APPLICATION_ID + ":aod");
        resolver = context.getContentResolver();
    }

    private Point getResolution() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return new Point(metrics.widthPixels, metrics.heightPixels);
    }

    private void initActualOverlay(Context context, IBinder windowToken) {
        synchronized (this) {
            this.windowToken = windowToken;
            if (spritePlayer != null) return;

            spritePlayer = new SpritePlayer(context);

            initParams();
            animation = new NotificationAnimation(context, spritePlayer, new NotificationAnimation.OnNotificationAnimationListener() {
                private PowerManager.WakeLock wakeLock = null;
                private int skips = 0;

                @Override
                public void onDimensionsApplied(SpritePlayer view) {
                    if (added) {
                        try {
                            windowManager.updateViewLayout(view, view.getLayoutParams());
                        } catch (IllegalArgumentException e) {
                            //TODO figure out why this happens
                            e.printStackTrace();
                        }
                    }
                }

                @Override
                public boolean onAnimationFrameStart(SpritePlayer view, boolean draw) {
                    if (draw) skips = 0;
                    if (Display.isDoze(spritePlayer.getContext())) {
                        if (!draw) {
                            // If we were to do slow drawing, we would have to poke
                            // WindowManager, by adjusting the x/y/width/height of the root view
                            // and using WindowManager::updateViewLayout.
                            // Otherwise, when we're in *doze* and on *battery* power, our overlay
                            // would disappear (as it's not actually part of the screen maintained
                            // content) unless the screen is being touched (aod-on-tap).
                            // It would be better to poke Android's internal draw wakelock
                            // instead, but there doesn't appear to be a way to reach this code
                            // from userspace. pokeDrawLock() calls that should exist according
                            // to AOSP do not appear to be present on Samsung.
                            // Using updateViewLayout often enough that it would keep our
                            // overlay alive however, triggers about 50% (single-core) CPU usage
                            // in system_server. As such it is cheaper to waste some cycles and
                            // redraw our overlay regularly. From experimentation, 6 works here.
                            skips++;
                            if (skips == 6) {
                                skips = 0;
                                return true;
                            }

                            return false;
                        }
                    }
                    return true;
                }

                @Override
                public void onAnimationFrameEnd(SpritePlayer view, boolean draw) {
                    if (Display.isDoze(spritePlayer.getContext())) {
                        if (draw) {
                            // This allows us to update the screen while in doze mode. Both
                            // according to the docs and what I've read from AOSP code say this
                            // isn't possible because we don't have the right permissions,
                            // nevertheless, it seems to work on the S10.
                            if (wakeLock == null) {
                                wakeLock = ((PowerManager)spritePlayer.getContext().getSystemService(Context.POWER_SERVICE)).newWakeLock(0x00000080 | 0x40000000, BuildConfig.APPLICATION_ID + ":draw"); /* DRAW_WAKE_LOCK | UNIMPORTANT_FOR_LOGGING */
                                wakeLock.setReferenceCounted(false);
                            }
                            try {
                                wakeLock.acquire(250);
                            } catch (Throwable t) {
                                t.printStackTrace();
                            }
                        }
                    }
                }

                @Override
                public boolean onAnimationComplete(SpritePlayer view) {
                    removeOverlay();
                    return false;
                }
            });

            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
            intentFilter.addAction(Intent.ACTION_SCREEN_ON);
            intentFilter.addAction(Intent.ACTION_USER_PRESENT);
            intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
            intentFilter.addAction(Intent.ACTION_POWER_CONNECTED);
            intentFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);

            spritePlayer.getContext().getApplicationContext().registerReceiver(broadcastReceiver, intentFilter);
        }
        evaluate();
    }

    @Override
    protected void finalize() throws Throwable {
        if (spritePlayer != null) {
            spritePlayer.getContext().getApplicationContext().unregisterReceiver(broadcastReceiver);
        }
        super.finalize();
    }

    @SuppressLint("RtlHardcoded")
    private void initParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                0,
                0,
                0,
                0,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                      WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
                    | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN
                , PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.LEFT | Gravity.TOP;
        params.setTitle("HoleyLight");
        params.token = windowToken;
        try { // disable animation when we move/resize
            int currentFlags = (Integer)params.getClass().getField("privateFlags").get(params);
            params.getClass().getField("privateFlags").set(params, currentFlags | 0x00000040); /* PRIVATE_FLAG_NO_MOVE_ANIMATION */
        } catch (Exception e) {
            //do nothing. Probably using other version of android
        }
        spritePlayer.setLayoutParams(params);
    }

    private void updateParams() {
        animation.applyDimensions();
    }

    private void createOverlay() {
        if (added) return;
        try {
            updateParams();
            added = true; // had a case of a weird exception that caused this to run in a loop if placed after addView
            windowManager.addView(spritePlayer, spritePlayer.getLayoutParams());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateOverlay() {
        if (!added) return;
        try {
            updateParams();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void removeOverlay() {
        if (!added) return;
        try {
            windowManager.removeView(spritePlayer);
            added = false;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean colorsChanged() {
        if ((lastColors == null) != (colors == null)) return true;
        if (lastColors == null) return false;
        if (lastColors.length != colors.length) return true;
        if (lastColors.length == 0) return false;
        for (int i = 0; i < lastColors.length; i++) {
            if (lastColors[i] != colors[i]) return true;
        }
        return false;
    }

    private Runnable evaluateLoop = new Runnable() {
        @Override
        public void run() {
            evaluate();
            if (wanted) handler.postDelayed(this, 1000);
        }
    };

    private void evaluate() {
        if (spritePlayer == null) return;

        Context context = spritePlayer.getContext();

        boolean on = Display.isOn(context, false);
        boolean doze = Display.isDoze(context);
        boolean visible = on || doze;
        if (!visible && settings.isHideAOD()) {
            // we will be visible soon
            visible = true;
            doze = true;
        }
        boolean lockscreen = on && keyguardManager.isKeyguardLocked();
        boolean charging = Battery.isCharging(context);
        boolean wantedEffective = wanted && (
                (on && !lockscreen && settings.isEnabledWhileScreenOn()) ||
                (on && lockscreen && settings.isEnabledOnLockscreen()) ||
                (!on && charging && settings.isEnabledWhileScreenOffCharging()) ||
                (!on && !charging && settings.isEnabledWhileScreenOffBattery())
        );
        if (wantedEffective && visible && (colors.length > 0)) {
            int dpAdd = (doze ? 1 : 0);
            SpritePlayer.Mode mode = settings.getAnimationMode(context, settings.getMode(charging, !doze));
            if (!lastState || colorsChanged() || mode != lastMode || (dpAdd != lastDpAdd)) {
                spritePlayer.setMode(mode);
                createOverlay();
                if (settings.isHideAOD() && doze) {
                    animation.setHideAOD(true);
                    AODControl.setAOD(spritePlayer.getContext(), true);
                } else {
                    animation.setHideAOD(false);
                }
                animation.setDpAdd(dpAdd); //TODO this causes SpriteSheet recreating; with hideAOD this means a delay that cause AOD to show for a second or so; without this it is a fraction of a second or not at all
                animation.play(colors, false, (mode != lastMode));
                lastColors = colors;
                lastState = true;
                lastMode = mode;
                lastDpAdd = dpAdd;
            }
        } else {
            if (lastState) {
                if (settings.isHideAOD()) {
                    animation.setHideAOD(false);
                    AODControl.setAOD(spritePlayer.getContext(), false);
                }
                if (animation.isPlaying()) {
                    boolean immediately = !visible || kill;
                    animation.stop(immediately);
                    if (immediately) removeOverlay();
                }
                lastState = false;
            }
        }
    }

    public void show(int[] colors) {
        handler.removeCallbacks(evaluateLoop);
        this.colors = colors;
        if ((colors == null) || (colors.length == 0)) {
            wanted = false;
        } else {
            wanted = true;
            handler.postDelayed(evaluateLoop, 500);
        }
        evaluate();
    }

    public void hide(boolean immediately) {
        handler.removeCallbacks(evaluateLoop);
        wanted = false;
        kill = immediately;
        evaluate();
    }
}
