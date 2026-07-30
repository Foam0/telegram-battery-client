package it.belloworld.mercurygram.vpn;

import android.net.Uri;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class VlessUriParser {
    private VlessUriParser() {
    }

    public static ParsedVless parse(String rawLink) {
        String raw = rawLink == null ? "" : rawLink.trim();
        Uri uri = Uri.parse(raw);
        if (!"vless".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Expected vless:// profile");
        }
        String userInfo = uri.getUserInfo();
        String uuid = userInfo == null ? "" : userInfo.split(":", 2)[0];
        if (uuid.length() == 0) {
            throw new IllegalArgumentException("Missing VLESS UUID");
        }
        String server = uri.getHost();
        if (server == null || server.length() == 0) {
            throw new IllegalArgumentException("Missing VLESS host");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        Map<String, String> params = new HashMap<>();
        for (String name : uri.getQueryParameterNames()) {
            params.put(name.toLowerCase(Locale.US), safe(uri.getQueryParameter(name)));
        }
        String label = uri.getFragment() != null ? Uri.decode(uri.getFragment()) : server;
        String flow = emptyToNull(params.get("flow"));
        String security = emptyToDefault(params.get("security"), "none");
        String type = emptyToDefault(params.get("type"), "tcp");
        return new ParsedVless(uuid, server, port, emptyToDefault(label, server), flow, security, type, params);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToDefault(String value, String fallback) {
        return value == null || value.length() == 0 ? fallback : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.length() == 0 ? null : value;
    }
}
