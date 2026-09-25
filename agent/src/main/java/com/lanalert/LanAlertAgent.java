package com.lanalert;

import com.google.gson.Gson;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class LanAlertAgent {
    static final Gson GSON = new Gson();
    static String server, code;

    static class Alert { String type, alertId, title, message, severity, source, createdAt; boolean requiresAck, broadcastAll; }

    static class Overlay extends JFrame {
        Overlay(Alert a) {
            setUndecorated(true); setAlwaysOnTop(true); setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            setExtendedState(MAXIMIZED_BOTH); setBackground(color(a.severity));
            JPanel p = new JPanel(new BorderLayout()); p.setBackground(color(a.severity));
            JLabel title = new JLabel(a.title, SwingConstants.CENTER); title.setForeground(Color.WHITE); title.setFont(new Font("Arial", Font.BOLD, 34));
            JTextArea msg = new JTextArea(a.message); msg.setEditable(false); msg.setLineWrap(true); msg.setWrapStyleWord(true); msg.setOpaque(false); msg.setForeground(Color.WHITE); msg.setFont(new Font("Arial", Font.PLAIN, 25)); msg.setBorder(BorderFactory.createEmptyBorder(30, 50, 30, 50));
            JButton button = new JButton(a.requiresAck ? "ACKNOWLEDGE" : "CLOSE"); button.setFont(new Font("Arial", Font.BOLD, 20)); button.addActionListener(e -> { sendAck(a.alertId); dispose(); });
            p.add(title, BorderLayout.NORTH); p.add(new JScrollPane(msg) {{ setOpaque(false); getViewport().setOpaque(false); setBorder(null); }}, BorderLayout.CENTER); p.add(button, BorderLayout.SOUTH); setContentPane(p);
        }
        static Color color(String s) { if (s == null) return new Color(32,67,112); return switch (s.toUpperCase()) { case "EMERGENCY" -> new Color(100,0,0); case "CRITICAL" -> new Color(190,20,20); case "WARNING" -> new Color(180,110,0); default -> new Color(30,70,120); }; }
    }

    static class Client extends WebSocketClient {
        Client() throws Exception { super(new URI(server + "?workstationCode=" + URLEncoder.encode(code, StandardCharsets.UTF_8))); }
        public void onOpen(ServerHandshake h) { Map<String,Object> m = new HashMap<>(); m.put("type","REGISTER"); m.put("workstationCode",code); m.put("ipAddress", localIp()); send(GSON.toJson(m)); System.out.println("Connected to " + server); }
        public void onMessage(String text) { try { Alert a = GSON.fromJson(text, Alert.class); SwingUtilities.invokeLater(() -> { Overlay o = new Overlay(a); o.setVisible(true); o.toFront(); }); } catch (Exception e) { e.printStackTrace(); } }
        public void onClose(int c, String r, boolean remote) { System.out.println("Disconnected: " + r); reconnect(); }
        public void onError(Exception e) { System.err.println("WebSocket error: " + e.getMessage()); }
        void reconnect() { new Thread(() -> { while (!isOpen()) try { Thread.sleep(5000); reconnectBlocking(); } catch (Exception ignored) {} }).start(); }
    }
    static Client client;
    static void sendAck(String alertId) { if (client == null || !client.isOpen()) return; Map<String,Object> m = new HashMap<>(); m.put("type","ACK"); m.put("alertId",alertId); m.put("workstationCode",code); sendSafe(GSON.toJson(m)); }
    static void sendSafe(String s) { try { client.send(s); } catch (Exception ignored) {} }
    static String localIp() { try { return java.net.InetAddress.getLocalHost().getHostAddress(); } catch (Exception e) { return "127.0.0.1"; } }
    public static void main(String[] args) throws Exception { server = args.length > 0 ? args[0] : "ws://172.20.20.10:9090"; code = args.length > 1 ? args[1] : "WS-20"; client = new Client(); client.connectBlocking(); while (true) Thread.sleep(10000); }
}
