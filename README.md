# LAN Alert Notification System

A lightweight LAN-only Java alert system.

## Components

- `server`: WebSocket alert server on port 9090 and HTTP API on port 9091.
- `agent`: Java desktop agent for each Windows workstation.
- Alerts are displayed in an always-on-top full-screen overlay.
- Supports manual alerts, automatic workstation ping alerts, severity, targeting, acknowledgement messages, and in-memory history.

## Requirements

- Java 17+
- Maven 3.8+
- Network access between the host and workstations

TLS is intentionally not configured because this is designed for a trusted internal LAN. Do not expose the ports to an untrusted network.

## Build the server

```bash
cd server
mvn clean package
java -jar target/lan-alert-server-1.0.0-jar-with-dependencies.jar
```

The server listens on:

- WebSocket: `ws://0.0.0.0:9090`
- HTTP API: `http://0.0.0.0:9091`

## Build the agent

```bash
cd agent
mvn clean package
```

Run one agent on each workstation. Replace the host IP and workstation code as needed:

```bash
java -jar target/lan-alert-agent-1.0.0-jar-with-dependencies.jar ws://172.20.20.10:9090 WS-20
java -jar target/lan-alert-agent-1.0.0-jar-with-dependencies.jar ws://172.20.20.10:9090 WS-21
java -jar target/lan-alert-agent-1.0.0-jar-with-dependencies.jar ws://172.20.20.10:9090 WS-22
java -jar target/lan-alert-agent-1.0.0-jar-with-dependencies.jar ws://172.20.20.10:9090 WS-23
java -jar target/lan-alert-agent-1.0.0-jar-with-dependencies.jar ws://172.20.20.10:9090 WS-24
java -jar target/lan-alert-agent-1.0.0-jar-with-dependencies.jar ws://172.20.20.10:9090 WS-25
```

## Manual alert

```bash
curl -X POST http://172.20.20.10:9091/api/manual-alert \
  -H "Content-Type: application/json" \
  -d '{
    "title":"Server warning",
    "message":"The ERP server is slow.",
    "severity":"WARNING",
    "broadcastAll":false,
    "workstationCodes":["WS-20","WS-21"],
    "requiresAck":true
  }'
```

For all workstations, set `broadcastAll` to `true`.

## Automatic alerts

The server checks `172.20.20.20` through `172.20.20.25` every 30 seconds. An alert is created after a workstation changes from reachable to unreachable. The alert is sent to that workstation's connected agent.

## Windows startup

Create a shortcut or scheduled task that runs the agent command at user logon. Example target:

```text
javaw.exe -jar C:\LanAlert\lan-alert-agent-1.0.0-jar-with-dependencies.jar ws://172.20.20.10:9090 WS-20
```

The implementation uses a full-screen overlay rather than changing the Windows wallpaper. This avoids modifying user wallpaper settings and supports acknowledgement reliably.
