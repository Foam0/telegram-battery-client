package it.belloworld.mercurygram.vpn;

import static org.junit.Assert.*;
import android.app.Application;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 30)
public class VlessConfigRegressionTest {
    private static final String LINK = "vless://" + "00000000-0000-4000-8000-000000000000" + "@example.com:8443";
    private BatteryVpnProfile profile(String query) { return new BatteryVpnProfile("test", LINK + query); }
    private JSONObject outbound(JSONObject config) throws Exception {
        return config.getJSONArray("outbounds").getJSONObject(0);
    }
    @Test public void parsesDefaultsAndEncodedLabel() {
        ParsedVless p = VlessUriParser.parse("  vless://test@example.com#%D0%A2%D0%B5%D1%81%D1%82  ");
        assertEquals(443, p.port); assertEquals("Тест", p.name);
        assertEquals("tcp", p.transportType); assertEquals("none", p.security);
    }
    @Test public void rejectsMissingIdentityHostAndWrongScheme() {
        for (String invalid : new String[]{null, "", "https://example.com", "vless://example.com", "vless://id@"}) {
            assertThrows(IllegalArgumentException.class, () -> VlessUriParser.parse(invalid));
        }
    }
    @Test public void socksIsAuthenticatedAndOnlyListensOnLoopback() throws Exception {
        JSONObject c = new JSONObject(VlessConfigBuilder.buildLocalSocks(profile(""), 1089, "alice", "secret"));
        JSONObject in = c.getJSONArray("inbounds").getJSONObject(0);
        assertEquals("socks", in.getString("type")); assertEquals("127.0.0.1", in.getString("listen"));
        assertEquals(1089, in.getInt("listen_port"));
        JSONObject user = in.getJSONArray("users").getJSONObject(0);
        assertEquals("alice", user.getString("username")); assertEquals("secret", user.getString("password"));
        assertEquals(8443, outbound(c).getInt("server_port"));
        assertFalse(outbound(c).has("multiplex"));
    }
    @Test public void socksRejectsEmptyCredentials() {
        for (String empty : new String[]{null, ""}) {
            assertThrows(IllegalArgumentException.class, () -> VlessConfigBuilder.buildLocalSocks(profile(""),1089,empty,"secret"));
            assertThrows(IllegalArgumentException.class, () -> VlessConfigBuilder.buildLocalSocks(profile(""),1089,"alice",empty));
        }
    }
    @Test public void tunUsesSystemStackAndDnsActions() throws Exception {
        JSONObject c = new JSONObject(VlessConfigBuilder.build(profile("")));
        assertEquals("tun", c.getJSONArray("inbounds").getJSONObject(0).getString("type"));
        assertEquals("system", c.getJSONArray("inbounds").getJSONObject(0).getString("stack"));
        assertEquals("sniff",c.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("action"));
        assertEquals("hijack-dns",c.getJSONObject("route").getJSONArray("rules").getJSONObject(1).getString("action"));
    }
    @Test public void realityPreservesSniKeyFingerprintAndFlow() throws Exception {
        JSONObject out = outbound(new JSONObject(VlessConfigBuilder.build(profile("?security=reality&sni=example.org&pbk=key&sid=abcd&fp=chrome&flow=xtls-rprx-vision"))));
        JSONObject tls = out.getJSONObject("tls");
        assertTrue(tls.getBoolean("enabled")); assertEquals("example.org",tls.getString("server_name"));
        assertEquals("key",tls.getJSONObject("reality").getString("public_key"));
        assertEquals("abcd",tls.getJSONObject("reality").getString("short_id"));
        assertEquals("chrome",tls.getJSONObject("utls").getString("fingerprint"));
        assertEquals("xtls-rprx-vision",out.getString("flow"));
    }
    @Test public void websocketAndGrpcKeepTheirTransportParameters() throws Exception {
        JSONObject ws = outbound(new JSONObject(VlessConfigBuilder.build(profile("?type=ws&path=%2Fsocket&host=example.org")))).getJSONObject("transport");
        assertEquals("ws",ws.getString("type")); assertEquals("/socket",ws.getString("path"));
        assertEquals("example.org",ws.getJSONObject("headers").getString("Host"));
        JSONObject grpc = outbound(new JSONObject(VlessConfigBuilder.build(profile("?type=grpc&serviceName=test")))).getJSONObject("transport");
        assertEquals("grpc",grpc.getString("type")); assertEquals("test",grpc.getString("service_name"));
    }
}
