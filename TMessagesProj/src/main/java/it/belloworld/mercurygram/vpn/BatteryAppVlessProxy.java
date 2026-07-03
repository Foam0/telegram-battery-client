package it.belloworld.mercurygram.vpn;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;

import java.util.Collections;

import io.nekohasekai.libbox.CommandServer;
import io.nekohasekai.libbox.CommandServerHandler;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.OverrideOptions;
import io.nekohasekai.libbox.SystemProxyStatus;

public final class BatteryAppVlessProxy implements CommandServerHandler {
    public static final String ACTION_STATUS = "it.belloworld.mercurygram.proxy.STATUS";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_MESSAGE = "message";

    private static final Object LOCK = new Object();
    private static BatteryAppVlessProxy instance;
    private static volatile boolean starting;
    private static volatile boolean coreRunning;
    private static volatile int localPort;
    private static volatile String lastState = "disconnected";
    private static volatile String lastMessage = "";

    private final Context context;
    private CommandServer commandServer;
    private boolean disconnecting;

    private BatteryAppVlessProxy(Context context) {
        this.context = context.getApplicationContext();
    }

    public static void start(Context context) {
        if (context == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        Utilities.globalQueue.postRunnable(() -> {
            synchronized (LOCK) {
                if (coreRunning || starting) {
                    return;
                }
                starting = true;
                BatteryAppVlessProxy proxy = new BatteryAppVlessProxy(appContext);
                instance = proxy;
                try {
                    proxy.connectLocked();
                } finally {
                    starting = false;
                }
            }
        });
    }

    public static void stop(Context context) {
        Utilities.globalQueue.postRunnable(() -> {
            synchronized (LOCK) {
                BatteryAppVlessProxy proxy = instance;
                if (proxy != null) {
                    proxy.shutdownCoreLocked(true);
                }
            }
        });
    }

    public static boolean isCoreRunning() {
        return coreRunning;
    }

    public static int getLocalPort() {
        return localPort;
    }

    public static String getLastState() {
        return lastState;
    }

    public static String getLastMessage() {
        return lastMessage;
    }

    @Override
    public SystemProxyStatus getSystemProxyStatus() {
        SystemProxyStatus status = new SystemProxyStatus();
        status.setAvailable(false);
        status.setEnabled(false);
        return status;
    }

    @Override
    public void serviceReload() {
    }

    @Override
    public void serviceStop() {
        stop(context);
    }

    @Override
    public void setSystemProxyEnabled(boolean enabled) {
    }

    @Override
    public void writeDebugMessage(String message) {
        if (BuildVars.LOGS_ENABLED && BuildVars.DEBUG_VERSION) {
            FileLog.d("libbox app-only VLESS debug message");
        }
    }

    private void connectLocked() {
        if (coreRunning) {
            return;
        }
        if (BatteryVpnService.isServiceActive() || BatteryVpnService.isCoreRunning()) {
            try {
                context.startService(new Intent(context, BatteryVpnService.class).setAction(BatteryVpnService.ACTION_DISCONNECT));
            } catch (Throwable ignored) {
            }
        }
        BatteryVpnStore store = new BatteryVpnStore(context);
        BatteryVpnProfile profile = store.getProfile();
        if (profile == null) {
            publishStatus("error", "profile missing");
            instance = null;
            return;
        }
        store.setMode(BatteryVpnStore.MODE_LOCAL_PROXY);
        try {
            String[] credentials = store.ensureLocalProxyCredentials();
            String username = credentials[0];
            String password = credentials[1];
            int port = Libbox.availablePort(2080);
            String config = VlessConfigBuilder.buildLocalSocks(profile, port, username, password);
            Libbox.checkConfig(config);

            BatteryLibboxPlatform platform = new BatteryLibboxPlatform(context, profile);
            CommandServer server = Libbox.newCommandServer(this, platform);
            server.start();
            OverrideOptions overrideOptions = new OverrideOptions();
            overrideOptions.setAutoRedirect(false);
            overrideOptions.setIncludePackage(new LibboxIterators.StringListIterator(Collections.<String>emptyList()));
            overrideOptions.setExcludePackage(new LibboxIterators.StringListIterator(Collections.<String>emptyList()));
            server.startOrReloadService(config, overrideOptions);

            commandServer = server;
            coreRunning = true;
            localPort = port;
            store.setConnected(true);
            store.setLocalProxyPort(port);
            publishTelegramProxy(port, username, password);
            publishStatus("proxy-connected", "127.0.0.1:" + port);
        } catch (Throwable e) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e("App-only VLESS start failed");
            }
            publishStatus("error", "start failed");
            shutdownCoreLocked(true);
        }
    }

    private void shutdownCoreLocked(boolean clearInstance) {
        if (disconnecting) {
            if (clearInstance) {
                instance = null;
            }
            return;
        }
        disconnecting = true;
        CommandServer server = commandServer;
        boolean hadLocalProxy = coreRunning || localPort != 0;
        commandServer = null;
        coreRunning = false;
        localPort = 0;
        BatteryVpnStore store = new BatteryVpnStore(context);
        store.setConnected(false);
        store.setLocalProxyPort(0);
        try {
            if (server != null) {
                server.closeService();
            }
        } catch (Throwable e) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e("App-only VLESS service close failed");
            }
        }
        try {
            if (server != null) {
                server.close();
            }
        } catch (Throwable e) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.e("App-only VLESS command server close failed");
            }
        }
        if (hadLocalProxy) {
            restoreTelegramProxy();
        }
        publishStatus("disconnected", "");
        disconnecting = false;
        if (clearInstance) {
            instance = null;
        }
    }

    private void publishTelegramProxy(int port, String username, String password) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                SharedConfig.publishMgInternalLocalProxy(port, username, password);
                ConnectionsManager.setProxySettings(true, "127.0.0.1", port, username, password, "");
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            } catch (Throwable e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("App-only VLESS Telegram proxy publish failed");
                }
            }
        });
    }

    private void restoreTelegramProxy() {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                SharedConfig.clearMgInternalTorProxy();
                SharedPreferences preferences = MessagesController.getGlobalMainSettings();
                String address = preferences.getString("proxy_ip", "");
                String username = preferences.getString("proxy_user", "");
                String password = preferences.getString("proxy_pass", "");
                String secret = preferences.getString("proxy_secret", "");
                int port = preferences.getInt("proxy_port", 1080);
                boolean enabled = preferences.getBoolean("proxy_enabled", false) && address != null && address.length() > 0;
                if (enabled) {
                    ConnectionsManager.setProxySettings(true, address, port, username, password, secret);
                    SharedConfig.currentProxy = findProxyInList(address, port, username, password, secret);
                } else {
                    ConnectionsManager.setProxySettings(false, "", 0, "", "", "");
                    SharedConfig.currentProxy = null;
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            } catch (Throwable e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("App-only VLESS Telegram proxy restore failed");
                }
            }
        });
    }

    private SharedConfig.ProxyInfo findProxyInList(String address, int port, String username, String password, String secret) {
        SharedConfig.loadProxyList();
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (info.mgInternal) {
                continue;
            }
            if (info.port == port
                    && safeEquals(info.address, address)
                    && safeEquals(info.username, username)
                    && safeEquals(info.password, password)
                    && safeEquals(info.secret, secret)) {
                return info;
            }
        }
        return null;
    }

    private boolean safeEquals(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    private void publishStatus(String state, String message) {
        lastState = state != null ? state : "";
        lastMessage = message != null ? message : "";
        new BatteryVpnStore(context).setStatus(lastState);
        Intent intent = new Intent(ACTION_STATUS)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_STATE, lastState)
                .putExtra(EXTRA_MESSAGE, lastMessage);
        context.sendBroadcast(intent);
    }
}
