package it.belloworld.mercurygram.vpn;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;

public final class VlessConfigBuilder {
    private VlessConfigBuilder() {
    }

    public static String build(BatteryVpnProfile profile) throws Exception {
        ParsedVless parsed = VlessUriParser.parse(profile.link);
        JSONObject config = new JSONObject()
                .put("log", new JSONObject().put("level", "info").put("timestamp", true))
                .put("experimental", new JSONObject()
                        .put("cache_file", new JSONObject().put("enabled", true).put("path", "cache.db"))
                        .put("debug", new JSONObject().put("gc_percent", 200)))
                .put("dns", new JSONObject()
                        .put("servers", new JSONArray()
                                .put(new JSONObject()
                                        .put("type", "udp")
                                        .put("tag", "bootstrap-dns")
                                        .put("server", "1.1.1.1")
                                        .put("server_port", 53))
                                .put(buildDohServer("cloudflare-dns.com", "/dns-query")))
                        .put("final", "remote-dns")
                        .put("strategy", "prefer_ipv4"))
                .put("inbounds", new JSONArray().put(new JSONObject()
                        .put("type", "tun")
                        .put("tag", "tun-in")
                        .put("address", new JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
                        .put("mtu", 9000)
                        .put("auto_route", true)
                        .put("strict_route", true)
                        .put("stack", "system")))
                .put("outbounds", new JSONArray()
                        .put(buildProxyOutbound(parsed))
                        .put(new JSONObject().put("type", "direct").put("tag", "direct")))
                .put("route", new JSONObject()
                        .put("rules", new JSONArray()
                                .put(new JSONObject()
                                        .put("inbound", "tun-in")
                                        .put("network", "udp")
                                        .put("port", 53)
                                        .put("action", "sniff")
                                        .put("sniffer", new JSONArray().put("dns"))
                                        .put("timeout", "300ms"))
                                .put(new JSONObject()
                                        .put("inbound", "tun-in")
                                        .put("protocol", "dns")
                                        .put("action", "hijack-dns")))
                        .put("final", "proxy")
                        .put("auto_detect_interface", true)
                        .put("default_domain_resolver", new JSONObject()
                                .put("server", "bootstrap-dns")
                                .put("strategy", "prefer_ipv4")));
        return config.toString(2);
    }

    private static JSONObject buildDohServer(String server, String path) throws Exception {
        return new JSONObject()
                .put("type", "https")
                .put("tag", "remote-dns")
                .put("server", server)
                .put("server_port", 443)
                .put("path", path)
                .put("detour", "proxy")
                .put("domain_resolver", new JSONObject()
                        .put("server", "bootstrap-dns")
                        .put("strategy", "prefer_ipv4"));
    }

    private static JSONObject buildProxyOutbound(ParsedVless parsed) throws Exception {
        JSONObject outbound = new JSONObject()
                .put("type", "vless")
                .put("tag", "proxy")
                .put("server", parsed.server)
                .put("server_port", parsed.port)
                .put("uuid", parsed.uuid)
                .put("packet_encoding", "xudp")
                .put("domain_resolver", new JSONObject()
                        .put("server", "bootstrap-dns")
                        .put("strategy", "prefer_ipv4"));
        if (parsed.flow != null) {
            outbound.put("flow", parsed.flow);
        }
        JSONObject tls = buildTls(parsed);
        if (tls != null) {
            outbound.put("tls", tls);
        }
        JSONObject transport = buildTransport(parsed);
        if (transport != null) {
            outbound.put("transport", transport);
        }
        return outbound;
    }

    private static JSONObject buildTls(ParsedVless parsed) throws Exception {
        Map<String, String> p = parsed.params;
        String security = parsed.security.toLowerCase(Locale.US);
        if ("tls".equals(security)) {
            JSONObject tls = new JSONObject()
                    .put("enabled", true)
                    .put("utls", new JSONObject()
                            .put("enabled", true)
                            .put("fingerprint", defaultString(p.get("fp"), "chrome")));
            putOptionalString(tls, "server_name", firstNonEmpty(p.get("sni"), p.get("host")));
            putOptionalBoolean(tls, "insecure", parseBoolean(p.get("allowinsecure")));
            return tls;
        }
        if ("reality".equals(security)) {
            JSONObject reality = new JSONObject().put("enabled", true);
            putOptionalString(reality, "public_key", firstNonEmpty(p.get("pbk"), p.get("publickey")));
            putOptionalString(reality, "short_id", firstNonEmpty(p.get("sid"), p.get("shortid")));
            JSONObject tls = new JSONObject()
                    .put("enabled", true)
                    .put("utls", new JSONObject()
                            .put("enabled", true)
                            .put("fingerprint", defaultString(p.get("fp"), "chrome")))
                    .put("reality", reality);
            putOptionalString(tls, "server_name", firstNonEmpty(p.get("sni"), p.get("host")));
            return tls;
        }
        return null;
    }

    private static JSONObject buildTransport(ParsedVless parsed) throws Exception {
        Map<String, String> p = parsed.params;
        String type = parsed.transportType.toLowerCase(Locale.US);
        String headerType = defaultString(p.get("headertype"), "").toLowerCase(Locale.US);
        if ("ws".equals(type)) {
            JSONObject ws = new JSONObject().put("type", "ws");
            putOptionalString(ws, "path", p.get("path"));
            if (notEmpty(p.get("host"))) {
                ws.put("headers", new JSONObject().put("Host", p.get("host")));
            }
            return ws;
        }
        if ("grpc".equals(type)) {
            JSONObject grpc = new JSONObject().put("type", "grpc");
            putOptionalString(grpc, "service_name", firstNonEmpty(p.get("servicename"), p.get("service_name")));
            return grpc;
        }
        if ("httpupgrade".equals(type)) {
            JSONObject httpUpgrade = new JSONObject().put("type", "httpupgrade");
            putOptionalString(httpUpgrade, "path", p.get("path"));
            putOptionalString(httpUpgrade, "host", p.get("host"));
            return httpUpgrade;
        }
        if ("http".equals(type) || "http".equals(headerType)) {
            JSONObject http = new JSONObject().put("type", "http");
            putOptionalString(http, "path", p.get("path"));
            if (notEmpty(p.get("host"))) {
                http.put("host", new JSONArray().put(p.get("host")));
            }
            return http;
        }
        if ("quic".equals(type)) {
            return new JSONObject().put("type", "quic");
        }
        return null;
    }

    private static void putOptionalString(JSONObject object, String key, String value) throws Exception {
        if (notEmpty(value)) {
            object.put(key, value);
        }
    }

    private static void putOptionalBoolean(JSONObject object, String key, Boolean value) throws Exception {
        if (value != null) {
            object.put(key, value.booleanValue());
        }
    }

    private static Boolean parseBoolean(String value) {
        if (value == null || value.length() == 0) {
            return null;
        }
        return Boolean.valueOf("true".equalsIgnoreCase(value) || "1".equals(value));
    }

    private static String firstNonEmpty(String a, String b) {
        return notEmpty(a) ? a : b;
    }

    private static String defaultString(String value, String fallback) {
        return notEmpty(value) ? value : fallback;
    }

    private static boolean notEmpty(String value) {
        return value != null && value.length() > 0;
    }
}
