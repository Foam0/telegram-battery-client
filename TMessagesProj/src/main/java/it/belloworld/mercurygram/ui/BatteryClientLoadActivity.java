package it.belloworld.mercurygram.ui;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.List;

import it.belloworld.mercurygram.vpn.BatteryAppVlessProxy;
import it.belloworld.mercurygram.vpn.BatteryProxyService;
import it.belloworld.mercurygram.vpn.BatteryVpnService;

public class BatteryClientLoadActivity extends UniversalFragment {
    private static final int ID_USAGE_STATS = 1;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.BatteryClientLoadScreen);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.BatteryClientLoadScreen)));
        items.add(UItem.asButton(0, LocaleController.getString(R.string.BatteryClientProcessCpu), processCpuLabel()));
        items.add(UItem.asButton(0, LocaleController.getString(R.string.BatteryClientBattery), batteryLabel()));
        items.add(UItem.asButton(ID_USAGE_STATS,
                LocaleController.getString(R.string.BatteryClientForegroundApp),
                foregroundAppLabel()));
        items.add(UItem.asButton(0, LocaleController.getString(R.string.BatteryClientActiveAccounts),
                Integer.toString(UserConfig.getActivatedAccountsCount())));
        items.add(UItem.asButton(0, LocaleController.getString(R.string.BatteryClientClientInstances),
                Integer.toString(activeClientInstances())));
        items.add(UItem.asButton(0, LocaleController.getString(R.string.BatteryClientVpnStatus),
                connectionModeLabel()));
        items.add(UItem.asShadow(LocaleController.getString(R.string.BatteryClientSectionAbout)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_USAGE_STATS && !hasUsageStatsPermission()) {
            Context context = getParentActivity();
            if (context != null) {
                context.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private String processCpuLabel() {
        long ms = android.os.Process.getElapsedCpuTime();
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        seconds %= 60;
        return minutes + "m " + seconds + "s";
    }

    private String batteryLabel() {
        Context context = getParentActivity();
        if (context == null) {
            return LocaleController.getString(R.string.BatteryClientNoData);
        }
        BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (manager == null) {
            return LocaleController.getString(R.string.BatteryClientNoData);
        }
        int level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        int current = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if (current != Integer.MIN_VALUE && current != 0) {
            return level + "%, " + current + " uA";
        }
        return level >= 0 ? level + "%" : LocaleController.getString(R.string.BatteryClientNoData);
    }

    private String foregroundAppLabel() {
        Context context = getParentActivity();
        if (context == null) {
            return LocaleController.getString(R.string.BatteryClientNoData);
        }
        if (!hasUsageStatsPermission()) {
            return LocaleController.getString(R.string.BatteryClientUsageStatsMissing);
        }
        UsageStatsManager manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) {
            return LocaleController.getString(R.string.BatteryClientNoData);
        }
        long now = System.currentTimeMillis();
        List<UsageStats> stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000, now);
        UsageStats latest = null;
        if (stats != null) {
            for (UsageStats stat : stats) {
                if (latest == null || stat.getLastTimeUsed() > latest.getLastTimeUsed()) {
                    latest = stat;
                }
            }
        }
        return latest != null ? latest.getPackageName() : LocaleController.getString(R.string.BatteryClientNoData);
    }

    private boolean hasUsageStatsPermission() {
        Context context = getParentActivity();
        if (context == null) {
            return false;
        }
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        if (appOps == null) {
            return false;
        }
        int mode;
        if (Build.VERSION.SDK_INT >= 29) {
            mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.getPackageName());
        } else {
            mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.getPackageName());
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private int activeClientInstances() {
        int count = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                count++;
            }
        }
        return count;
    }

    private String connectionModeLabel() {
        if (BatteryAppVlessProxy.isCoreRunning()) {
            int port = BatteryAppVlessProxy.getLocalPort();
            return LocaleController.getString(R.string.BatteryClientProxyConnected)
                    + (port > 0 ? " 127.0.0.1:" + port : "");
        }
        if (BatteryProxyService.isCoreRunning()) {
            int port = BatteryProxyService.getLocalPort();
            return LocaleController.getString(R.string.BatteryClientProxyConnected)
                    + (port > 0 ? " 127.0.0.1:" + port : "");
        }
        if (BatteryVpnService.isCoreRunning()) {
            return LocaleController.getString(R.string.BatteryClientVpnConnected);
        }
        return LocaleController.getString(R.string.BatteryClientVpnDisconnected);
    }
}
