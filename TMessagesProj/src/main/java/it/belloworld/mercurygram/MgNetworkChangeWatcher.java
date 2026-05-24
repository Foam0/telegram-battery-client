package it.belloworld.mercurygram;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

// MG: triggers a PFS temp-key rehandshake whenever the default network changes
// (Wi-Fi <-> cellular, VPN flip, IP-change reconnect). Each new handshake
// yields a fresh auth_key_id, so a passive on-path observer cannot use the
// id as a stable device fingerprint across network boundaries.
//
// Active only while SharedConfig.reduceTrackingFingerprint is true.
// Self-debounces with DEBOUNCE_MS to coalesce noisy callbacks during
// transitions (Android often fires onAvailable/onCapabilitiesChanged 3-5
// times in quick succession while a network settles).
public final class MgNetworkChangeWatcher {

    private static final long DEBOUNCE_MS = 2_000L;

    private static volatile boolean registered = false;
    // Accessed from ConnectivityManager callback thread (binder) AND main
    // looper (via the rotateRunnable closure). volatile is mandatory:
    // without it the JIT can cache reads in a register and miss real
    // network changes — defeating the rotation guarantee on the exact
    // transition the feature is meant to detect.
    private static volatile Network lastNetwork = null;
    // True until the first onAvailable callback has been observed. The
    // first onAvailable fires synchronously after registerDefaultNetworkCallback
    // for the currently-active network — that's not a real "network change",
    // so we skip rotation and just seed lastNetwork. Avoids burning a
    // handshake (and an observable cold-start pattern) on every app launch.
    private static volatile boolean primed = false;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable rotateRunnable = MgNetworkChangeWatcher::rotateAllAccounts;

    private MgNetworkChangeWatcher() {}

    public static void init(Context context) {
        if (registered) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    onNetworkChanged(network);
                }
                // No onLost handler: a brief blip (airplane-mode toggle,
                // captive-portal recheck) drops the same network briefly,
                // then onAvailable restores it. Treating onLost as
                // "lastNetwork = null" would make the same physical
                // network look new and waste a rotation. Network.equals
                // compares netId, which the OS reuses across blips, so
                // the lastNetwork check in onNetworkChanged correctly
                // suppresses these.
            });
            registered = true;
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    // Called from SharedConfig.applyReduceTrackingFingerprintToNative on
    // toggle changes so a pending debounced rotation that no longer
    // makes sense (e.g. user toggled off in the debounce window) is
    // cancelled before it fires.
    public static void cancelPendingRotation() {
        handler.removeCallbacks(rotateRunnable);
    }

    private static void onNetworkChanged(Network network) {
        boolean wasPrimed = primed;
        primed = true;
        if (!wasPrimed) {
            // first callback after registerDefaultNetworkCallback — seed
            // lastNetwork and skip; no actual network change happened.
            lastNetwork = network;
            return;
        }
        if (network.equals(lastNetwork)) {
            return;
        }
        lastNetwork = network;
        if (!SharedConfig.reduceTrackingFingerprint) {
            return;
        }
        handler.removeCallbacks(rotateRunnable);
        handler.postDelayed(rotateRunnable, DEBOUNCE_MS);
    }

    private static void rotateAllAccounts() {
        if (!SharedConfig.reduceTrackingFingerprint) return;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    ConnectionsManager.getInstance(a).rotateTempAuthKeys();
                }
            } catch (Throwable t) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e(t);
                }
            }
        }
    }
}
