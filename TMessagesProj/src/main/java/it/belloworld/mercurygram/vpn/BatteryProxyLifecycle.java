package it.belloworld.mercurygram.vpn;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.ForegroundDetector;

public final class BatteryProxyLifecycle implements ForegroundDetector.Listener {
    private static final long BACKGROUND_STOP_DELAY_MS = 1500L;
    private static BatteryProxyLifecycle instance;

    private final Context context;
    private final Runnable stopRunnable;

    private BatteryProxyLifecycle(Context context) {
        this.context = context.getApplicationContext();
        stopRunnable = () -> stopLocalProxyIfRunning(this.context);
    }

    public static void init(Context context, ForegroundDetector detector) {
        if (context == null || detector == null || instance != null) {
            return;
        }
        instance = new BatteryProxyLifecycle(context);
        detector.addListener(instance);
        if (detector.isForeground()) {
            startLocalProxyIfNeeded(instance.context);
        }
    }

    @Override
    public void onBecameForeground() {
        resumeLocalProxy(context);
    }

    @Override
    public void onBecameBackground() {
        pauseLocalProxy(context);
    }

    public static void resumeLocalProxy(Context context) {
        BatteryProxyLifecycle lifecycle = instance;
        if (lifecycle != null) {
            AndroidUtilities.cancelRunOnUIThread(lifecycle.stopRunnable);
        }
        startLocalProxyIfNeeded(context);
    }

    public static void pauseLocalProxy(Context context) {
        BatteryProxyLifecycle lifecycle = instance;
        if (lifecycle != null) {
            AndroidUtilities.cancelRunOnUIThread(lifecycle.stopRunnable);
            AndroidUtilities.runOnUIThread(lifecycle.stopRunnable, BACKGROUND_STOP_DELAY_MS);
        } else {
            AndroidUtilities.runOnUIThread(() -> stopLocalProxyIfRunning(context), BACKGROUND_STOP_DELAY_MS);
        }
    }

    public static void startLocalProxyIfNeeded(Context context) {
        try {
            Context appContext = context.getApplicationContext();
            BatteryVpnStore store = new BatteryVpnStore(appContext);
            if (!BatteryVpnStore.MODE_LOCAL_PROXY.equals(store.getMode())
                    || store.getProfile() == null
                    || BatteryProxyService.isServiceActive()
                    || BatteryProxyService.isCoreRunning()
                    || BatteryVpnService.isCoreRunning()) {
                return;
            }
            Intent intent = new Intent(appContext, BatteryProxyService.class)
                    .setAction(BatteryProxyService.ACTION_CONNECT);
            if (Build.VERSION.SDK_INT >= 26) {
                appContext.startForegroundService(intent);
            } else {
                appContext.startService(intent);
            }
        } catch (Throwable e) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e(e);
            }
        }
    }

    public static void stopLocalProxyIfRunning(Context context) {
        Context appContext = context.getApplicationContext();
        try {
            if (!BatteryProxyService.isServiceActive() && !BatteryProxyService.isCoreRunning()) {
                return;
            }
            Intent intent = new Intent(appContext, BatteryProxyService.class)
                    .setAction(BatteryProxyService.ACTION_DISCONNECT);
            appContext.startService(intent);
        } catch (Throwable e) {
            try {
                appContext.stopService(new Intent(appContext, BatteryProxyService.class));
            } catch (Throwable ignored) {
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e(e);
            }
        }
    }
}
