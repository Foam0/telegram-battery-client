package it.belloworld.mercurygram.vpn;

import java.util.Map;

public final class ParsedVless {
    public final String uuid;
    public final String server;
    public final int port;
    public final String name;
    public final String flow;
    public final String security;
    public final String transportType;
    public final Map<String, String> params;

    ParsedVless(String uuid, String server, int port, String name, String flow,
                String security, String transportType, Map<String, String> params) {
        this.uuid = uuid;
        this.server = server;
        this.port = port;
        this.name = name;
        this.flow = flow;
        this.security = security;
        this.transportType = transportType;
        this.params = params;
    }
}
