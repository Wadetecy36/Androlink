import { useState, useEffect, useRef } from "react";
import {
  Smartphone,
  Wifi,
  Battery,
  BatteryCharging,
  Clipboard,
  Bell,
  Send,
  FolderSync,
  MonitorPlay,
  Music,
  CheckCircle2,
  XCircle,
} from "lucide-react";
import "./App.css";

interface BatteryData {
  level: number;
  isCharging: boolean;
  powerSaveMode?: boolean;
}

interface NotificationData {
  key: string;
  appName: string;
  packageName: string;
  title: string;
  text: string;
}

export default function App() {
  const [isConnected, setIsConnected] = useState(false);
  const [activeTab, setActiveTab] = useState<"dashboard" | "notifications" | "clipboard" | "files">("dashboard");
  const [battery, setBattery] = useState<BatteryData>({ level: 100, isCharging: false });
  const [lastClipboard, setLastClipboard] = useState("Nothing copied yet");
  const [clipInput, setClipInput] = useState("");
  const [notifications, setNotifications] = useState<NotificationData[]>([]);
  const [laptopIp, setLaptopIp] = useState("127.0.0.1");
  const [gatewayIp, setGatewayIp] = useState("");
  const wsRef = useRef<WebSocket | null>(null);

  useEffect(() => {
    const wsUrl = `ws://${window.location.hostname || "localhost"}:8702`;
    const ws = new WebSocket(wsUrl);
    wsRef.current = ws;

    ws.onopen = () => {
      console.log("Connected to Androlink desktop server");
    };

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data);
        if (msg.type === "state") {
          setIsConnected(msg.data.isConnected);
          if (msg.data.lastClipboard) setLastClipboard(msg.data.lastClipboard);
          if (msg.data.localIp) setLaptopIp(msg.data.localIp);
          if (msg.data.gatewayIp) setGatewayIp(msg.data.gatewayIp);
        } else if (msg.type === "androlink:device_connected") {
          setIsConnected(true);
        } else if (msg.type === "androlink:device_disconnected") {
          setIsConnected(false);
        } else if (msg.type === "androlink:battery_update") {
          setBattery(msg.payload);
        } else if (msg.type === "androlink:clipboard_update") {
          setLastClipboard(msg.payload);
        } else if (msg.type === "androlink:notification_received") {
          setNotifications((prev) => [msg.payload, ...prev.slice(0, 49)]);
        }
      } catch (e) {
        console.error("WS error:", e);
      }
    };

    ws.onclose = () => {
      console.log("Disconnected from Androlink desktop server");
    };

    return () => {
      ws.close();
    };
  }, []);

  const handleSendClipboard = () => {
    if (!clipInput.trim()) return;
    if (wsRef.current && wsRef.current.readyState === WebSocket.OPEN) {
      wsRef.current.send(
        JSON.stringify({
          type: "send_clipboard",
          text: clipInput,
        })
      );
      setLastClipboard(clipInput);
      setClipInput("");
    }
  };

  return (
    <div className="app-container">
      {/* Sidebar */}
      <aside className="sidebar">
        <div className="sidebar-brand">
          <Smartphone size={24} />
          <span>Androlink</span>
        </div>

        <div className="device-status-card">
          <div className={`status-badge ${isConnected ? "connected" : "disconnected"}`}>
            {isConnected ? <CheckCircle2 size={14} /> : <XCircle size={14} />}
            <span>{isConnected ? "Phone Connected" : "Searching Phone..."}</span>
          </div>

          <div className="battery-row">
            <span>Battery</span>
            <span style={{ display: "flex", alignItems: "center", gap: 4 }}>
              {battery.isCharging ? <BatteryCharging size={16} color="#4ade80" /> : <Battery size={16} />}
              {battery.level}%
            </span>
          </div>

          <div style={{ marginTop: 6, paddingTop: 8, borderTop: "1px solid rgba(255,255,255,0.06)", fontSize: 12 }}>
            <div style={{ color: "#818cf8", fontWeight: 600 }}>PC IP: {laptopIp}:8700</div>
            {gatewayIp && <div style={{ color: "#94a3b8", fontSize: 11, marginTop: 2 }}>Phone Hotspot: {gatewayIp}</div>}
          </div>
        </div>

        <nav className="nav-list">
          <button
            className={`nav-item ${activeTab === "dashboard" ? "active" : ""}`}
            onClick={() => setActiveTab("dashboard")}
          >
            <Wifi size={18} />
            <span>Dashboard</span>
          </button>
          <button
            className={`nav-item ${activeTab === "notifications" ? "active" : ""}`}
            onClick={() => setActiveTab("notifications")}
          >
            <Bell size={18} />
            <span>Notifications ({notifications.length})</span>
          </button>
          <button
            className={`nav-item ${activeTab === "clipboard" ? "active" : ""}`}
            onClick={() => setActiveTab("clipboard")}
          >
            <Clipboard size={18} />
            <span>Shared Clipboard</span>
          </button>
          <button
            className={`nav-item ${activeTab === "files" ? "active" : ""}`}
            onClick={() => setActiveTab("files")}
          >
            <FolderSync size={18} />
            <span>File Transfer</span>
          </button>
        </nav>
      </aside>

      {/* Main View */}
      <main className="main-view">
        <div className="header-row">
          <div>
            <h1 style={{ fontSize: 24, fontWeight: 700 }}>Device Control Center</h1>
            <p style={{ color: "#94a3b8", fontSize: 13, marginTop: 4 }}>
              Real-time sync between your Windows PC and Android device
            </p>
          </div>
        </div>

        {activeTab === "dashboard" && (
          <div className="section-grid">
            {/* Clipboard Preview Card */}
            <div className="card">
              <div className="card-title">
                <Clipboard size={18} color="#6366f1" />
                <span>Quick Clipboard Push</span>
              </div>
              <div className="clip-preview">{lastClipboard}</div>
              <div className="input-row">
                <input
                  className="text-input"
                  placeholder="Type or paste to push directly to phone..."
                  value={clipInput}
                  onChange={(e) => setClipInput(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && handleSendClipboard()}
                />
                <button className="primary-btn" onClick={handleSendClipboard}>
                  <Send size={14} />
                  <span>Send</span>
                </button>
              </div>
            </div>

            {/* Quick Actions Card */}
            <div className="card">
              <div className="card-title">
                <MonitorPlay size={18} color="#6366f1" />
                <span>Remote Features</span>
              </div>
              <p style={{ fontSize: 13, color: "#94a3b8" }}>
                Screen mirroring, virtual trackpad, and media controls run over encrypted local Wi-Fi streams.
              </p>
              <div style={{ display: "flex", gap: 10, flexWrap: "wrap", marginTop: 8 }}>
                <button className="primary-btn" style={{ background: "rgba(255, 255, 255, 0.08)" }}>
                  <MonitorPlay size={16} />
                  <span>Mirror Screen</span>
                </button>
                <button className="primary-btn" style={{ background: "rgba(255, 255, 255, 0.08)" }}>
                  <Music size={16} />
                  <span>Media Controls</span>
                </button>
              </div>
            </div>

            {/* Recent Notifications Card */}
            <div className="card" style={{ gridColumn: "1 / -1" }}>
              <div className="card-title">
                <Bell size={18} color="#6366f1" />
                <span>Recent Phone Notifications</span>
              </div>
              {notifications.length === 0 ? (
                <p style={{ fontSize: 13, color: "#64748b" }}>
                  No incoming notifications yet. Ensure the notification permission is enabled on your phone.
                </p>
              ) : (
                <div className="notification-list">
                  {notifications.slice(0, 5).map((n) => (
                    <div className="notification-item" key={n.key}>
                      <span className="notif-app">{n.appName}</span>
                      <span className="notif-title">{n.title}</span>
                      <span className="notif-text">{n.text}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}

        {activeTab === "notifications" && (
          <div className="card">
            <div className="card-title">
              <Bell size={18} color="#6366f1" />
              <span>All Notifications ({notifications.length})</span>
            </div>
            {notifications.length === 0 ? (
              <p style={{ fontSize: 13, color: "#64748b" }}>No notifications recorded yet.</p>
            ) : (
              <div className="notification-list" style={{ maxHeight: "600px" }}>
                {notifications.map((n) => (
                  <div className="notification-item" key={n.key}>
                    <span className="notif-app">{n.appName}</span>
                    <span className="notif-title">{n.title}</span>
                    <span className="notif-text">{n.text}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {activeTab === "clipboard" && (
          <div className="card">
            <div className="card-title">
              <Clipboard size={18} color="#6366f1" />
              <span>Shared Clipboard Manager</span>
            </div>
            <p style={{ fontSize: 13, color: "#94a3b8" }}>
              Every time you copy text on either your phone or PC, it automatically syncs in real-time.
            </p>
            <div className="clip-preview" style={{ maxHeight: "250px" }}>
              {lastClipboard}
            </div>
            <div className="input-row">
              <input
                className="text-input"
                placeholder="Send custom text to phone clipboard..."
                value={clipInput}
                onChange={(e) => setClipInput(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && handleSendClipboard()}
              />
              <button className="primary-btn" onClick={handleSendClipboard}>
                <Send size={14} />
                <span>Send</span>
              </button>
            </div>
          </div>
        )}

        {activeTab === "files" && (
          <div className="card">
            <div className="card-title">
              <FolderSync size={18} color="#6366f1" />
              <span>Direct File Transfer</span>
            </div>
            <p style={{ fontSize: 13, color: "#94a3b8" }}>
              Drag and drop files here to beam directly to your phone's Downloads directory over high-speed local Wi-Fi.
            </p>
            <div
              style={{
                border: "2px dashed rgba(255, 255, 255, 0.15)",
                borderRadius: 12,
                padding: 40,
                textAlign: "center",
                color: "#94a3b8",
                fontSize: 14,
              }}
            >
              Drop files here or click to browse
            </div>
          </div>
        )}
      </main>
    </div>
  );
}
