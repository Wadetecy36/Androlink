# Androlink Protocol Specification (v1)

This document specifies the communication protocol between the Androlink Android client and the Windows desktop client.

## 1. Network Discovery (UDP Broadcast)

Both the Windows PC and Android phone listen on UDP port `8699` for discovery beacons.

### Discovery Beacon Payload (JSON)
Broadcasted every 2 seconds when searching, or responded directly via unicast UDP when discovered:

```json
{
  "protocol": "androlink-v1",
  "type": "discovery",
  "deviceId": "androlink-win-a1b2c3d4",
  "deviceName": "Trinity-Laptop",
  "deviceType": "desktop", // "desktop" | "phone"
  "tcpPort": 8700,
  "tlsFingerprint": "SHA256:7B:3F:...",
  "version": "1.0.0"
}
```

---

## 2. Pairing & Secure Channel (mTLS over TCP)

1. Client initiates a TCP connection to the peer's `tcpPort` (default: `8700`).
2. Mutual TLS handshake takes place using self-signed X.509 certificates.
3. If not yet paired:
   - Both devices derive a 6-digit confirmation PIN from their mutual public keys.
   - User verifies that PINs match on both screens and clicks "Approve".
   - Devices store each other's certificate SHA-256 fingerprint in local trusted storage.

---

## 3. Framing & Packet Structure

Packets are UTF-8 encoded JSON strings delimited by a single newline byte `\n` (or prefixed by a 4-byte big-endian length header for binary streams).

```json
{
  "id": "e4b3c0c0-1111-4f12-9c12-32a76fef1234",
  "channel": "clipboard | notification | media | input | file | system",
  "event": "string",
  "timestamp": 1727889600000,
  "data": {}
}
```

---

## 4. Channels & Events

### 4.1. System Channel (`system`)

* **`system.ping` / `system.pong`**: Heartbeat every 10 seconds.
* **`system.battery`**:
  ```json
  {
    "channel": "system",
    "event": "battery",
    "data": {
      "level": 85,
      "isCharging": true,
      "powerSaveMode": false
    }
  }
  ```

### 4.2. Clipboard Channel (`clipboard`)

* **`clipboard.sync`**:
  ```json
  {
    "channel": "clipboard",
    "event": "sync",
    "data": {
      "mimeType": "text/plain",
      "content": "Copied text content here"
    }
  }
  ```

### 4.3. Notification Channel (`notification`)

* **`notification.post`**:
  ```json
  {
    "channel": "notification",
    "event": "post",
    "data": {
      "key": "com.whatsapp:1001",
      "appName": "WhatsApp",
      "packageName": "com.whatsapp",
      "title": "Jane Doe",
      "text": "Are we still meeting at 5?",
      "iconBase64": "...",
      "actions": [
        { "id": "reply", "title": "Reply", "hasInput": true },
        { "id": "mark_read", "title": "Mark as read", "hasInput": false }
      ]
    }
  }
  ```
* **`notification.action`**:
  ```json
  {
    "channel": "notification",
    "event": "action",
    "data": {
      "key": "com.whatsapp:1001",
      "actionId": "reply",
      "inputText": "Yes, on my way!"
    }
  }
  ```
* **`notification.dismiss`**:
  ```json
  {
    "channel": "notification",
    "event": "dismiss",
    "data": {
      "key": "com.whatsapp:1001"
    }
  }
  ```

### 4.4. Media Control Channel (`media`)

* **`media.state`** (Phone to PC):
  ```json
  {
    "channel": "media",
    "event": "state",
    "data": {
      "player": "Spotify",
      "title": "Song Title",
      "artist": "Artist Name",
      "album": "Album Name",
      "durationMs": 210000,
      "positionMs": 45000,
      "isPlaying": true
    }
  }
  ```
* **`media.command`** (PC to Phone):
  `play`, `pause`, `next`, `previous`, `volume_up`, `volume_down`, `seek`.

### 4.5. Remote Input Channel (`input`)

* **`input.pointer_move`**:
  `{ "dx": 12.5, "dy": -4.2 }`
* **`input.click`**:
  `{ "button": "left" | "right" | "double" }`
* **`input.scroll`**:
  `{ "deltaY": -120 }`
* **`input.key`**:
  `{ "key": "Backspace", "modifiers": ["Control"] }`

### 4.6. File Transfer Channel (`file`)

1. **`file.offer`**:
   `{ "transferId": "...", "fileName": "photo.jpg", "fileSize": 4521000, "checksumSha256": "..." }`
2. **`file.accept`**:
   `{ "transferId": "...", "status": "accepted", "port": 8701 }`
3. Raw binary stream flows over dedicated TCP port `8701` with chunk acknowledgment.
