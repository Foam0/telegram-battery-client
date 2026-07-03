package it.belloworld.mercurygram;

import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;

public final class BatteryClientDiagnostics {
    private static final long[] lastUpdateMs = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] lastConnectionStateMs = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static final int[] lastConnectionState = new int[UserConfig.MAX_ACCOUNT_COUNT];

    private BatteryClientDiagnostics() {
    }

    public static void markUpdate(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return;
        }
        lastUpdateMs[account] = System.currentTimeMillis();
    }

    public static void markConnectionState(int account, int state) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return;
        }
        lastConnectionState[account] = state;
        lastConnectionStateMs[account] = System.currentTimeMillis();
    }

    public static long getLastUpdateMs(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return 0;
        }
        return lastUpdateMs[account];
    }

    public static long getLastConnectionStateMs(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return 0;
        }
        return lastConnectionStateMs[account];
    }

    public static int getLastConnectionState(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return 0;
        }
        int state = lastConnectionState[account];
        if (state == 0) {
            state = ConnectionsManager.getInstance(account).getConnectionState();
        }
        return state;
    }

    public static String connectionStateLabel(int state) {
        switch (state) {
            case ConnectionsManager.ConnectionStateConnecting:
                return "connecting";
            case ConnectionsManager.ConnectionStateWaitingForNetwork:
                return "waiting for network";
            case ConnectionsManager.ConnectionStateConnected:
                return "connected";
            case ConnectionsManager.ConnectionStateConnectingToProxy:
                return "connecting to proxy";
            case ConnectionsManager.ConnectionStateUpdating:
                return "updating";
            default:
                return "unknown";
        }
    }
}
