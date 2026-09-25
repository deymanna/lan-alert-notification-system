package com.lanalert;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class LanAlertServer {
    static final Gson GSON = new GsonBuilder().create();
    static final int WS_PORT = 9090;
    static final int HTTP_PORT = 9091;
    static final Map<String, WebSocket> CLIENTS = new ConcurrentHashMap<>();
    static final Map<String, Workstation> WORKSTATIONS = new ConcurrentHashMap<>();
    static final List<Alert> HISTORY = new CopyOnWriteArrayList<>();
    static final Map<String, Long> LAST_OFFLINE_ALERT = new ConcurrentHashMap<>();

    static class Alert {
        String type = "ALERT";
        String alertId = UUID.randomUUID().toString();
        String title;
        String message;
        String severity = "WARNING";
        String source;
        String createdAt = Instant.now().toString();
        boolean requiresAck;
        boolean broadcastAll;
        List<String> workstationCodes = new ArrayList<>();
    }

    static class Workstation {
        String workstationCode;
        String ipAddress;
        volatile boolean online;
        volatile long lastChecked;

        Workstation(String code, String ip) {
            workstationCode = code;
            ipAddress = ip;
        }
    }

    static class ManualRequest {
        String title;
        String message;
        String severity;
        boolean broadcastAll;
        boolean requiresAck;
        List<String> workstationCodes;
    }

    static class WsServer extends WebSocketServer {
        WsServer() {
            super(new InetSocketAddress(WS_PORT));
        }

        public void onOpen(WebSocket c, ClientHandshake h) {
            System.out.println("WebSocket connected: " + c.getRemoteSocketAddress());
        }

        public void onClose(WebSocket c, int code, String reason, boolean remote) {
            CLIENTS.entrySet().removeIf(e -> e.getValue() == c);
            System.out.println("WebSocket closed: " + reason);
        }

        public void onMessage(WebSocket c, String text) {
            try {
                // Use a typed map. Map<?, ?> cannot accept a String default value
                // in getOrDefault because of Java wildcard capture rules.
                Map<String, Object> m = GSON.fromJson(text, Map.class);
                if (m == null) {
                    return;
                }

                String type = String.valueOf(m.getOrDefault("type", ""));
                if ("REGISTER".equalsIgnoreCase(type)) {
                    String code = String.valueOf(m.get("workstationCode"));
                    String ip = String.valueOf(m.getOrDefault("ipAddress", remoteIp(c)));
                    Workstation w = WORKSTATIONS.computeIfAbsent(
                            code,
                            k -> new Workstation(code, ip));
                    w.ipAddress = ip;
                    w.online = true;
                    w.lastChecked = System.currentTimeMillis();
                    CLIENTS.put(code, c);
                    System.out.println("Registered " + code + " at " + ip);
                } else if ("ACK".equalsIgnoreCase(type)) {
                    System.out.println(
                            "Acknowledged alert " + m.get("alertId")
                                    + " by " + m.get("workstationCode"));
                }
            } catch (Exception e) {
                System.err.println("Invalid WebSocket message: " + text);
            }
        }

        public void onError(WebSocket c, Exception e) {
            e.printStackTrace();
        }

        public void onStart() {
            System.out.println("WebSocket listening on ws://0.0.0.0:" + WS_PORT);
        }
    }

    public static void main(String[] args) throws Exception {
        for (int i = 20; i <= 25; i++) {
            String code = "WS-" + i;
            WORKSTATIONS.put(code, new Workstation(code, "172.20.20." + i));
        }

        new WsServer().start();
        startHttp();
        startMonitor();

        System.out.println("LAN Alert Server is running.");
        Thread.currentThread().join();
    }

    static void startHttp() throws IOException {
        HttpServer http = HttpServer.create(
                new InetSocketAddress(HTTP_PORT),
                0);

        http.createContext("/api/manual-alert", e -> {
            if (!"POST".equalsIgnoreCase(e.getRequestMethod())) {
                json(e, 405, "{\"error\":\"POST required\"}");
                return;
            }

            try {
                ManualRequest r = GSON.fromJson(
                        read(e),
                        ManualRequest.class);

                if (r == null || blank(r.title) || blank(r.message)) {
                    json(e, 400,
                            "{\"error\":\"title and message are required\"}");
                    return;
                }

                Alert a = new Alert();
                a.title = r.title;
                a.message = r.message;
                a.severity = blank(r.severity)
                        ? "WARNING"
                        : r.severity.toUpperCase(Locale.ROOT);
                a.source = "MANUAL";
                a.requiresAck = r.requiresAck;
                a.broadcastAll = r.broadcastAll;
                a.workstationCodes = r.workstationCodes == null
                        ? new ArrayList<>()
                        : r.workstationCodes;

                broadcast(a);
                json(e, 200, GSON.toJson(Map.of(
                        "status", "ok",
                        "alertId", a.alertId)));
            } catch (Exception ex) {
                json(e, 400, "{\"error\":\"invalid request\"}");
            }
        });

        http.createContext(
                "/api/history",
                e -> json(e, 200, GSON.toJson(HISTORY)));

        http.createContext(
                "/api/workstations",
                e -> json(e, 200,
                        GSON.toJson(new ArrayList<>(WORKSTATIONS.values()))));

        http.start();
        System.out.println(
                "HTTP API listening on http://0.0.0.0:" + HTTP_PORT);
    }

    static void startMonitor() {
        Timer t = new Timer(true);
        t.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                for (Workstation w : WORKSTATIONS.values()) {
                    boolean reachable = ping(w.ipAddress);
                    boolean old = w.online;
                    w.online = reachable;
                    w.lastChecked = System.currentTimeMillis();

                    long lastAlert = LAST_OFFLINE_ALERT.getOrDefault(
                            w.workstationCode,
                            0L);

                    if (old
                            && !reachable
                            && System.currentTimeMillis() - lastAlert > 300000) {
                        Alert a = new Alert();
                        a.title = "Workstation offline";
                        a.message = w.workstationCode
                                + " at " + w.ipAddress
                                + " is not responding.";
                        a.severity = "CRITICAL";
                        a.source = "AUTO";
                        a.workstationCodes = List.of(w.workstationCode);
                        broadcast(a);
                        LAST_OFFLINE_ALERT.put(
                                w.workstationCode,
                                System.currentTimeMillis());
                    }
                }
            }
        }, 5000, 30000);
    }

    static void broadcast(Alert a) {
        HISTORY.add(a);
        String payload = GSON.toJson(a);

        if (a.broadcastAll
                || a.workstationCodes == null
                || a.workstationCodes.isEmpty()) {
            CLIENTS.values().forEach(c -> {
                if (c.isOpen()) {
                    c.send(payload);
                }
            });
        } else {
            for (String code : a.workstationCodes) {
                WebSocket c = CLIENTS.get(code);
                if (c != null && c.isOpen()) {
                    c.send(payload);
                }
            }
        }
    }

    static boolean ping(String ip) {
        try {
            return InetAddress.getByName(ip).isReachable(2000);
        } catch (Exception e) {
            return false;
        }
    }

    static String remoteIp(WebSocket c) {
        try {
            return c.getRemoteSocketAddress()
                    .getAddress()
                    .getHostAddress();
        } catch (Exception e) {
            return "";
        }
    }

    static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    static String read(HttpExchange e) throws IOException {
        return new String(
                e.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8);
    }

    static void json(HttpExchange e, int status, String body)
            throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set(
                "Content-Type",
                "application/json; charset=utf-8");
        e.sendResponseHeaders(status, b.length);
        try (OutputStream o = e.getResponseBody()) {
            o.write(b);
        }
    }
}
