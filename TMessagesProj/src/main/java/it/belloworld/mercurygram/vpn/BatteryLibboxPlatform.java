package it.belloworld.mercurygram.vpn;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.ProxyInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.LaunchActivity;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import io.nekohasekai.libbox.ConnectionOwner;
import io.nekohasekai.libbox.InterfaceUpdateListener;
import io.nekohasekai.libbox.Libbox;
import io.nekohasekai.libbox.LocalDNSTransport;
import io.nekohasekai.libbox.NetworkInterface;
import io.nekohasekai.libbox.NetworkInterfaceIterator;
import io.nekohasekai.libbox.PlatformInterface;
import io.nekohasekai.libbox.RoutePrefix;
import io.nekohasekai.libbox.RoutePrefixIterator;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.TunOptions;
import io.nekohasekai.libbox.WIFIState;

public final class BatteryLibboxPlatform implements PlatformInterface {
    private static final String EVENT_CHANNEL_ID = "battery-vpn-events";
    private static final int IFF_UP = 0x1;
    private static final int IFF_BROADCAST = 0x2;
    private static final int IFF_LOOPBACK = 0x8;
    private static final int IFF_POINTOPOINT = 0x10;
    private static final int IFF_RUNNING = 0x40;
    private static final int IFF_MULTICAST = 0x1000;

    private final Context context;
    private final VpnService vpnService;
    private final BatteryVpnProfile profile;
    private final ConnectivityManager connectivityManager;
    private final NotificationManager notificationManager;
    private final ConcurrentHashMap<InterfaceUpdateListener, ConnectivityManager.NetworkCallback> callbacks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<InterfaceUpdateListener, String> callbackKeys = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Integer> detachedTunFds = new ConcurrentLinkedQueue<>();

    public BatteryLibboxPlatform(BatteryVpnService service, BatteryVpnProfile profile) {
        this((Context) service, service, profile);
    }

    public BatteryLibboxPlatform(Service service, BatteryVpnProfile profile) {
        this((Context) service, null, profile);
    }

    public BatteryLibboxPlatform(Context context, BatteryVpnProfile profile) {
        this(context, null, profile);
    }

    private BatteryLibboxPlatform(Context context, VpnService vpnService, BatteryVpnProfile profile) {
        this.context = context.getApplicationContext();
        this.vpnService = vpnService;
        this.profile = profile;
        connectivityManager = (ConnectivityManager) this.context.getSystemService(ConnectivityManager.class);
        notificationManager = (NotificationManager) this.context.getSystemService(NotificationManager.class);
    }

    @Override
    public void autoDetectInterfaceControl(int fd) throws Exception {
        if (vpnService == null) {
            return;
        }
        if (!vpnService.protect(fd)) {
            throw new IllegalStateException("VpnService.protect failed");
        }
    }

    @Override
    public void clearDNSCache() {
    }

    @Override
    public void closeDefaultInterfaceMonitor(InterfaceUpdateListener listener) {
        ConnectivityManager.NetworkCallback callback = callbacks.remove(listener);
        if (callback != null && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(callback);
            } catch (Throwable ignored) {
            }
        }
        callbackKeys.remove(listener);
    }

    @Override
    public ConnectionOwner findConnectionOwner(int ipProtocol, String sourceAddress, int sourcePort, String destinationAddress, int destinationPort) {
        ConnectionOwner owner = new ConnectionOwner();
        owner.setUserId(-1);
        owner.setAndroidPackageNames(new LibboxIterators.StringListIterator(Collections.<String>emptyList()));
        return owner;
    }

    @Override
    public NetworkInterfaceIterator getInterfaces() throws Exception {
        Map<String, ArrayList<String>> dnsByInterface = dnsServersByInterface();
        Map<String, Boolean> meteredByInterface = meteredByInterface();
        ArrayList<NetworkInterface> result = new ArrayList<>();
        java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) {
            return new LibboxIterators.BoxNetworkInterfaceIterator(result);
        }
        for (java.net.NetworkInterface netIf : Collections.list(interfaces)) {
            ArrayList<String> addresses = new ArrayList<>();
            for (InterfaceAddress address : netIf.getInterfaceAddresses()) {
                InetAddress inet = address.getAddress();
                if (inet == null) {
                    continue;
                }
                int prefix = address.getNetworkPrefixLength();
                if (prefix < 0) {
                    prefix = inet instanceof Inet6Address ? 128 : 32;
                }
                addresses.add(stripIpv6Zone(inet.getHostAddress()) + "/" + prefix);
            }
            if (addresses.isEmpty()) {
                continue;
            }
            NetworkInterface box = new NetworkInterface();
            box.setIndex(netIf.getIndex());
            try {
                box.setMTU(netIf.getMTU());
            } catch (Throwable ignored) {
                box.setMTU(1500);
            }
            box.setName(netIf.getName());
            box.setAddresses(new LibboxIterators.StringListIterator(addresses));
            box.setFlags(rawFlags(netIf));
            box.setType(interfaceType(netIf.getName()));
            box.setDNSServer(new LibboxIterators.StringListIterator(dnsByInterface.getOrDefault(netIf.getName(), new ArrayList<String>())));
            Boolean metered = meteredByInterface.get(netIf.getName());
            box.setMetered(metered != null && metered);
            result.add(box);
        }
        return new LibboxIterators.BoxNetworkInterfaceIterator(result);
    }

    @Override
    public boolean includeAllNetworks() {
        return false;
    }

    @Override
    public LocalDNSTransport localDNSTransport() {
        return null;
    }

    @Override
    public int openTun(TunOptions options) throws Exception {
        if (vpnService == null) {
            throw new UnsupportedOperationException("TUN is not available in local proxy mode");
        }
        VpnService.Builder builder = vpnService.new Builder()
                .setSession(profile.name)
                .setMtu(options.getMTU() > 0 ? options.getMTU() : 9000);

        addAddresses(builder, options.getInet4Address());
        addAddresses(builder, options.getInet6Address());
        addRoutes(builder, options.getInet4RouteAddress());
        addRoutes(builder, options.getInet6RouteAddress());
        addRoutes(builder, options.getInet4RouteRange());
        addRoutes(builder, options.getInet6RouteRange());
        addExcludedRoutes(builder, options.getInet4RouteExcludeAddress());
        addExcludedRoutes(builder, options.getInet6RouteExcludeAddress());

        try {
            String dns = options.getDNSServerAddress() != null ? options.getDNSServerAddress().getValue() : null;
            if (dns != null && !dns.isEmpty()) {
                builder.addDnsServer(dns);
            }
        } catch (Throwable ignored) {
        }

        addApplications(builder, options.getIncludePackage(), true);
        addApplications(builder, options.getExcludePackage(), false);

        if (Build.VERSION.SDK_INT >= 29 && options.isHTTPProxyEnabled()) {
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(options.getHTTPProxyServer(), options.getHTTPProxyServerPort()));
        }

        ParcelFileDescriptor descriptor = builder.establish();
        if (descriptor == null) {
            throw new IllegalStateException("VpnService.Builder.establish returned null");
        }
        int fd = descriptor.detachFd();
        detachedTunFds.add(fd);
        return fd;
    }

    public void closeDetachedTunFds() {
        Integer fd;
        while ((fd = detachedTunFds.poll()) != null) {
            try {
                ParcelFileDescriptor.adoptFd(fd).close();
            } catch (Throwable e) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e(e);
                }
            }
        }
    }

    @Override
    public WIFIState readWIFIState() {
        return null;
    }

    @Override
    public void sendNotification(io.nekohasekai.libbox.Notification notification) {
        if (notificationManager == null) {
            return;
        }
        ensureNotificationChannel(EVENT_CHANNEL_ID, "VPN events", NotificationManager.IMPORTANCE_LOW);
        Intent open = new Intent(context, LaunchActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(context, EVENT_CHANNEL_ID)
                : new android.app.Notification.Builder(context);
        android.app.Notification androidNotification = builder
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(safeText(notification.getTitle(), profile.name))
                .setContentText(safeText(notification.getBody(), notification.getSubtitle()))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build();
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            try {
                notificationManager.notify(notification.getIdentifier().hashCode(), androidNotification);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void startDefaultInterfaceMonitor(final InterfaceUpdateListener listener) throws Exception {
        if (connectivityManager == null) {
            listener.updateDefaultInterface("", -1, false, false);
            return;
        }
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                publishBestNetwork(listener);
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                publishBestNetwork(listener);
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                publishBestNetwork(listener);
            }

            @Override
            public void onLost(Network network) {
                publishBestNetwork(listener);
            }
        };
        callbacks.put(listener, callback);
        connectivityManager.registerNetworkCallback(request, callback);
        publishBestNetwork(listener);
    }

    @Override
    public StringIterator systemCertificates() {
        return new LibboxIterators.StringListIterator(Collections.<String>emptyList());
    }

    @Override
    public boolean underNetworkExtension() {
        return false;
    }

    @Override
    public boolean usePlatformAutoDetectInterfaceControl() {
        return true;
    }

    @Override
    public boolean useProcFS() {
        return true;
    }

    private void publishBestNetwork(InterfaceUpdateListener listener) {
        Network best = null;
        if (connectivityManager != null) {
            for (Network candidate : connectivityManager.getAllNetworks()) {
                NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(candidate);
                if (caps != null
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                    best = candidate;
                    break;
                }
            }
        }
        if (best == null) {
            listener.updateDefaultInterface("", -1, false, false);
            callbackKeys.remove(listener);
            return;
        }
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(best);
        LinkProperties linkProperties = connectivityManager.getLinkProperties(best);
        String name = linkProperties != null && linkProperties.getInterfaceName() != null ? linkProperties.getInterfaceName() : "";
        int index = 0;
        try {
            java.net.NetworkInterface netIf = java.net.NetworkInterface.getByName(name);
            index = netIf != null ? netIf.getIndex() : 0;
        } catch (Throwable ignored) {
        }
        boolean expensive = caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        boolean constrained = caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED);
        String key = name + "#" + index + ":" + expensive + ":" + constrained;
        if (index != 0 && key.equals(callbackKeys.get(listener))) {
            return;
        }
        if (index != 0) {
            callbackKeys.put(listener, key);
        } else {
            callbackKeys.remove(listener);
        }
        listener.updateDefaultInterface(name, index, expensive, constrained);
    }

    private void addAddresses(VpnService.Builder builder, RoutePrefixIterator prefixes) throws Exception {
        if (prefixes == null) {
            return;
        }
        while (prefixes.hasNext()) {
            RoutePrefix prefix = prefixes.next();
            if (prefix != null) {
                builder.addAddress(prefix.address(), prefix.prefix());
            }
        }
    }

    private void addRoutes(VpnService.Builder builder, RoutePrefixIterator prefixes) throws Exception {
        if (prefixes == null) {
            return;
        }
        while (prefixes.hasNext()) {
            RoutePrefix prefix = prefixes.next();
            if (prefix != null) {
                builder.addRoute(prefix.address(), prefix.prefix());
            }
        }
    }

    private void addExcludedRoutes(VpnService.Builder builder, RoutePrefixIterator prefixes) throws Exception {
        if (Build.VERSION.SDK_INT < 33 || prefixes == null) {
            return;
        }
        while (prefixes.hasNext()) {
            RoutePrefix prefix = prefixes.next();
            if (prefix != null) {
                builder.excludeRoute(new IpPrefix(InetAddress.getByName(prefix.address()), prefix.prefix()));
            }
        }
    }

    private void addApplications(VpnService.Builder builder, StringIterator iterator, boolean include) {
        ArrayList<String> packages = toStringList(iterator);
        for (String packageName : packages) {
            try {
                if (include) {
                    builder.addAllowedApplication(packageName);
                } else {
                    builder.addDisallowedApplication(packageName);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private ArrayList<String> toStringList(StringIterator iterator) {
        ArrayList<String> values = new ArrayList<>();
        if (iterator == null) {
            return values;
        }
        while (iterator.hasNext()) {
            String value = iterator.next();
            if (value != null && !value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private Map<String, ArrayList<String>> dnsServersByInterface() {
        HashMap<String, ArrayList<String>> map = new HashMap<>();
        if (connectivityManager == null) {
            return map;
        }
        for (Network network : connectivityManager.getAllNetworks()) {
            LinkProperties properties = connectivityManager.getLinkProperties(network);
            if (properties == null || properties.getInterfaceName() == null) {
                continue;
            }
            ArrayList<String> servers = new ArrayList<>();
            for (InetAddress address : properties.getDnsServers()) {
                servers.add(stripIpv6Zone(address.getHostAddress()));
            }
            map.put(properties.getInterfaceName(), servers);
        }
        return map;
    }

    private Map<String, Boolean> meteredByInterface() {
        HashMap<String, Boolean> map = new HashMap<>();
        if (connectivityManager == null) {
            return map;
        }
        for (Network network : connectivityManager.getAllNetworks()) {
            LinkProperties properties = connectivityManager.getLinkProperties(network);
            if (properties == null || properties.getInterfaceName() == null) {
                continue;
            }
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
            map.put(properties.getInterfaceName(), caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));
        }
        return map;
    }

    private int rawFlags(java.net.NetworkInterface netIf) {
        int flags = 0;
        try {
            if (netIf.isUp()) {
                flags |= IFF_UP | IFF_RUNNING;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (netIf.isLoopback()) {
                flags |= IFF_LOOPBACK;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (netIf.isPointToPoint()) {
                flags |= IFF_POINTOPOINT;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (netIf.supportsMulticast()) {
                flags |= IFF_MULTICAST;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (!netIf.isPointToPoint() && !netIf.isLoopback()) {
                flags |= IFF_BROADCAST;
            }
        } catch (Throwable ignored) {
        }
        return flags;
    }

    private int interfaceType(String name) {
        if (name == null) {
            return Libbox.InterfaceTypeOther;
        }
        if (name.startsWith("wlan") || name.startsWith("wifi")) {
            return Libbox.InterfaceTypeWIFI;
        }
        if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")) {
            return Libbox.InterfaceTypeCellular;
        }
        if (name.startsWith("eth")) {
            return Libbox.InterfaceTypeEthernet;
        }
        return Libbox.InterfaceTypeOther;
    }

    private String stripIpv6Zone(String address) {
        if (address == null) {
            return "";
        }
        int percent = address.indexOf('%');
        return percent >= 0 ? address.substring(0, percent) : address;
    }

    private String safeText(String value, String fallback) {
        return value != null && !value.isEmpty() ? value : fallback;
    }

    private void ensureNotificationChannel(String id, String name, int importance) {
        if (Build.VERSION.SDK_INT >= 26 && notificationManager != null && notificationManager.getNotificationChannel(id) == null) {
            notificationManager.createNotificationChannel(new NotificationChannel(id, name, importance));
        }
    }
}
