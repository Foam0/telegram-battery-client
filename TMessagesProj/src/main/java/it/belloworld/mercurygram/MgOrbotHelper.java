package it.belloworld.mercurygram;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;

// MG: lightweight integration with the user-installed Orbot app
// (org.torproject.android). Routes Telegram traffic through Tor's local
// SOCKS5 (127.0.0.1:9050). No Tor binary bundled — Orbot is on F-Droid and
// stays a separate user-managed app (keeps APK small, avoids reproducibility
// fights with prebuilt native libs).
public final class MgOrbotHelper {

    private static final String ORBOT_PACKAGE = "org.torproject.android";
    private static final String ORBOT_START_ACTION = "org.torproject.android.intent.action.START";
    private static final String ORBOT_LOOPBACK_HOST = "127.0.0.1";
    private static final int ORBOT_SOCKS_PORT = 9050;
    // Guardian Project's own app page — Orbot was removed from the main
    // F-Droid repo, so the historical f-droid.org URL now 404s. Guardian
    // Project's page links to the Guardian Project F-Droid repo, the Play
    // Store, and direct APK downloads.
    private static final String ORBOT_INSTALL_URL = "https://guardianproject.info/apps/org.torproject.android/";

    private MgOrbotHelper() {}

    public static boolean isOrbotInstalled(Context context) {
        if (context == null) return false;
        try {
            context.getPackageManager().getPackageInfo(ORBOT_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static void startOrbot(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(ORBOT_START_ACTION);
            intent.setPackage(ORBOT_PACKAGE);
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    // Adds a SOCKS5 proxy entry to 127.0.0.1:9050 (Orbot's loopback) and makes
    // it the current proxy. Caller is expected to also call activateProxy()
    // to persist the proxy_enabled flag and notify upstream UI.
    public static SharedConfig.ProxyInfo configureSocksProxy() {
        SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo(
                ORBOT_LOOPBACK_HOST, ORBOT_SOCKS_PORT, "", "", "");
        SharedConfig.ProxyInfo existing = SharedConfig.addProxy(info);
        SharedConfig.currentProxy = existing;
        // addProxy short-circuits without saving when the entry already
        // exists (SharedConfig.java:1827), but currentProxy still moved
        // to it. saveProxyList sorts with a -200000 bias for currentProxy
        // (SharedConfig.java:1809), so without an explicit save the
        // persisted order drifts from the active selection.
        SharedConfig.saveProxyList();
        return existing;
    }

    public static void activateProxy(android.content.SharedPreferences globalSettings, SharedConfig.ProxyInfo info) {
        // Privacy-critical pref: commit() (synchronous) over apply() (async).
        // A process crash within the apply queue would leave proxy_enabled
        // = false on next launch, silently bypassing Tor.
        globalSettings.edit()
                .putString("proxy_ip", info.address)
                .putString("proxy_user", info.username)
                .putString("proxy_pass", info.password)
                .putString("proxy_secret", info.secret)
                .putInt("proxy_port", info.port)
                .putBoolean("proxy_enabled", true)
                .commit();
        ConnectionsManager.setProxySettings(true, info.address, info.port, info.username, info.password, info.secret);
        // Every upstream proxy-mutation path posts this so ProxyListActivity
        // and other observers refresh; without it the standard Proxy panel
        // renders stale state until the screen is rebuilt.
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    public static Intent getInstallIntent() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(ORBOT_INSTALL_URL));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
