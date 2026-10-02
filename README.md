# Androlink 📱 ↔️ 💻

Seamless Android-to-Windows device integration inspired by KDE Connect and Windows Phone Link.

- **Lightweight Windows Host**: Runs directly via **Node.js** with zero heavy compilers (no Visual Studio or C++ build tools required).
- **Modern Fluent UI Dashboard**: React + TypeScript interface accessible at `http://localhost:8702` (or installable as a Desktop PWA).
- **Native Android Client**: Built with **Kotlin + Jetpack Compose** with foreground service background execution.

---

## 🌟 Key Features

1. **Auto Discovery & Pairing:**
   - Automatic zero-configuration peer discovery over local Wi-Fi via UDP broadcast (port `8699`).
   - Secure TCP socket communication (port `8700`).

2. **Bidirectional Clipboard Sync:**
   - Copy text on Android -> immediately paste on Windows.
   - Copy text on Windows -> immediately available on Android.

3. **Notification Mirroring:**
   - Real-time mirroring of incoming phone notifications to the Windows desktop.
   - Native Windows Toast notifications via PowerShell WinRT.

4. **Device State & Battery Monitor:**
   - Real-time phone battery level and charging indicator in the PC control center.

5. **Direct File Transfer & Media Integration:**
   - High-throughput local Wi-Fi transfer pipeline.

---

## 📁 Repository Structure

```
Androlink/
├── desktop/                  # Lightweight Windows Host + Dashboard
│   ├── server/               # Node.js networking host
│   │   └── server.js         # UDP discovery, TCP socket server, Windows clipboard & toasts
│   ├── src/                  # React dashboard with Windows 11 Fluent UI
│   │   ├── App.tsx           # WebSocket client and control center
│   │   └── App.css           # Fluent dark styling
│   ├── dist/                 # Pre-built production bundle
│   └── package.json
│
├── android/                  # Native Android application (Kotlin + Jetpack Compose)
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml
│   │   └── java/com/androlink/app/
│   │       ├── MainActivity.kt               # Jetpack Compose mobile UI
│   │       ├── service/                      # Background services
│   │       │   ├── AndrolinkForegroundService.kt # Persistent background connection
│   │       │   └── NotificationListener.kt   # System notification listener
│   │       ├── network/                      # Networking engine
│   │       │   ├── UdpDiscovery.kt           # UDP beacon broadcast & listener
│   │       │   └── TcpClient.kt              # Streaming TCP client
│   │       └── model/Packet.kt               # Protocol models
│   └── build.gradle.kts
│
├── protocol/                 # Specification documents
│   └── PROTOCOL.md           # Wire format, packet schemas, and event definitions
│
└── README.md
```

---

## 🚀 How to Run

### 1. Windows Desktop Host (Zero-install, instant startup)

Open a terminal in the `desktop` directory and run:

```bash
cd desktop
pnpm start
```

Then open **[http://localhost:8702](http://localhost:8702)** in your browser (e.g. Edge / Chrome). You can also click the "Install app" icon in the address bar to install it as a standalone Windows desktop app!

### 2. Android Mobile Client

1. Open Android Studio.
2. Select **Open** and choose `C:\Users\Trinity\Desktop\Androlink\android`.
3. Let Gradle sync and run on your Android phone (connected via USB or Wi-Fi).
4. Tap **Grant** for Notification Access permission.
5. Tap **Start Service**.
6. The phone and PC will automatically discover each other over your local Wi-Fi and pair instantly!
