import { useState, useEffect, useRef } from "react";
import {
  Wifi,
  Battery,
  BatteryCharging,
  Clipboard,
  Bell,
  Send,
  FolderSync,
  MonitorPlay,
  Music,
  Check,
  Copy,
  Zap,
  Radio,
  Trash2,
  Share2,
  Layers,
  ArrowRight,
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
  time?: string;
}

export default function App() {
  const [isConnected, setIsConnected] = useState(false);
  const [activeTab, setActiveTab] = useState<"dashboard" | "notifications" | "clipboard" | "files">("dashboard");
  const [battery, setBattery] = useState<BatteryData>({ level: 100, isCharging: false });
  const [lastClipboard, setLastClipboard] = useState("Waiting for copied text...");
  const [clipInput, setClipInput] = useState("");
  const [copiedSuccess, setCopiedSuccess] = useState(false);
  const [notifications, setNotifications] = useState<NotificationData[]>([]);
  const [laptopIp, setLaptopIp] = useState("127.0.0.1");
  const [gatewayIp, setGatewayIp] = useState("");
  const [phoneDeviceName, setPhoneDeviceName] = useState("Android Device");

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
          if (msg.data.deviceName && msg.data.deviceName !== "None") {
            setPhoneDeviceName(msg.data.deviceName);
          }
        } else if (msg.type === "androlink:device_connected") {
          setIsConnected(true);
        } else if (msg.type === "androlink:device_disconnected") {
          setIsConnected(false);
        } else if (msg.type === "androlink:battery_update") {
          setBattery(msg.payload);
        } else if (msg.type === "androlink:clipboard_update") {
          setLastClipboard(msg.payload);
        } else if (msg.type === "androlink:notification_received") {
          const item: NotificationData = {
            ...msg.payload,
            time: new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }),
          };
          setNotifications((prev) => [item, ...prev.slice(0, 49)]);
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

  const handleCopyCurrentClipboard = () => {
    if (!lastClipboard) return;
    navigator.clipboard.writeText(lastClipboard);
    setCopiedSuccess(true);
    setTimeout(() => setCopiedSuccess(false), 2000);
  };

  const dismissNotification = (key: string) => {
    setNotifications((prev) => prev.filter((n) => n.key !== key));
  };

  return (
    <div className="app-layout">
      {/* Sidebar */}
      <aside className="sidebar">
        <div className="brand-row">
          <div className="brand-logo">
            <div className="brand-icon-box">
              <Zap size={20} />
            </div>
            <span>Androlink</span>
          </div>
          <span className="version-pill">v1.2</span>
        </div>

        {/* Device Status Card */}
        <div className="device-hero-card">
          <div className="live-indicator-row">
            <div className={`radar-pill ${isConnected ? "connected" : "searching"}`}>
              <div className={`pulse-dot ${isConnected ? "online" : ""}`} />
              <span>{isConnected ? "Linked & Active" : "Searching Phone..."}</span>
            </div>
          </div>

          <div>
            <div className="device-meta-title">{isConnected ? phoneDeviceName : "Galaxy Hotspot"}</div>
            <div className="device-meta-subtitle">
              <Radio size={13} color="#818cf8" />
              <span>{isConnected ? "High-Speed Wi-Fi Stream" : "Awaiting phone connection"}</span>
            </div>
          </div>

          <div className="battery-visual-gauge">
            <span style={{ fontSize: 13, color: "var(--text-muted)", display: "flex", alignItems: "center", gap: 6 }}>
              {battery.isCharging ? <BatteryCharging size={16} color="#34d399" /> : <Battery size={16} />}
              Battery
            </span>
            <span style={{ fontSize: 14, fontWeight: 700, fontVariantNumeric: "tabular-nums" }}>
              {battery.level}%
            </span>
          </div>

          <div className="network-ip-badge">
            <span>PC Host:</span>
            <span>{laptopIp}:8700</span>
          </div>
          {gatewayIp && (
            <div style={{ fontSize: 11, color: "var(--text-dim)", textAlign: "center", marginTop: 2 }}>
              Hotspot Gateway: {gatewayIp}
            </div>
          )}
        </div>

        {/* Nav list */}
        <nav className="nav-group">
          <button
            className={`nav-link ${activeTab === "dashboard" ? "active" : ""}`}
            onClick={() => setActiveTab("dashboard")}
          >
            <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <Wifi size={18} />
              <span>Overview</span>
            </div>
            <ArrowRight size={14} opacity={activeTab === "dashboard" ? 1 : 0} />
          </button>

          <button
            className={`nav-link ${activeTab === "notifications" ? "active" : ""}`}
            onClick={() => setActiveTab("notifications")}
          >
            <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <Bell size={18} />
              <span>Notifications</span>
            </div>
            {notifications.length > 0 && <span className="nav-counter">{notifications.length}</span>}
          </button>

          <button
            className={`nav-link ${activeTab === "clipboard" ? "active" : ""}`}
            onClick={() => setActiveTab("clipboard")}
          >
            <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <Clipboard size={18} />
              <span>Clipboard Hub</span>
            </div>
          </button>

          <button
            className={`nav-link ${activeTab === "files" ? "active" : ""}`}
            onClick={() => setActiveTab("files")}
          >
            <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <FolderSync size={18} />
              <span>File Beam</span>
            </div>
          </button>
        </nav>
      </aside>

      {/* Main Stage */}
      <main className="main-stage">
        <header className="top-masthead">
          <div>
            <h1 className="page-title">
              {activeTab === "dashboard" && "Device Control Center"}
              {activeTab === "notifications" && "Notification Mirroring Feed"}
              {activeTab === "clipboard" && "Universal Clipboard Synchronizer"}
              {activeTab === "files" && "High-Speed Wi-Fi File Beam"}
            </h1>
            <p className="page-subtitle">
              Encrypted bidirectional stream running locally on your Wi-Fi network
            </p>
          </div>
        </header>

        {activeTab === "dashboard" && (
          <div className="dashboard-grid">
            {/* Clipboard Preview */}
            <div className="glass-panel col-span-8">
              <div className="panel-header">
                <div className="panel-title-group">
                  <Clipboard size={18} color="#6366f1" />
                  <span>Real-Time Shared Clipboard</span>
                </div>
                <div style={{ display: "flex", gap: 8 }}>
                  <button className="action-btn-secondary" onClick={handleCopyCurrentClipboard}>
                    {copiedSuccess ? <Check size={14} color="#34d399" /> : <Copy size={14} />}
                    <span>{copiedSuccess ? "Copied!" : "Copy to PC"}</span>
                  </button>
                </div>
              </div>

              <div className="clipboard-canvas">
                {lastClipboard}
              </div>

              <div className="clipboard-actions-bar">
                <input
                  className="text-bar-input"
                  placeholder="Type or paste text to beam directly to your phone..."
                  value={clipInput}
                  onChange={(e) => setClipInput(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && handleSendClipboard()}
                />
                <button className="action-btn-primary" onClick={handleSendClipboard}>
                  <Send size={14} />
                  <span>Push to Phone</span>
                </button>
              </div>
            </div>

            {/* Quick Actions Panel */}
            <div className="glass-panel col-span-4">
              <div className="panel-header">
                <div className="panel-title-group">
                  <Layers size={18} color="#06b6d4" />
                  <span>Quick Features</span>
                </div>
                <span className="panel-badge">Ready</span>
              </div>

              <p style={{ fontSize: 13, color: "var(--text-muted)", lineHeight: 1.5 }}>
                Stream media controls, remote mouse trackpad, and notifications directly over your local Wi-Fi.
              </p>

              <div style={{ display: "flex", flexDirection: "column", gap: 10, marginTop: "auto" }}>
                <button className="action-btn-secondary" style={{ justifyContent: "flex-start" }}>
                  <MonitorPlay size={16} color="#6366f1" />
                  <span>Launch Screen Mirroring</span>
                </button>
                <button className="action-btn-secondary" style={{ justifyContent: "flex-start" }}>
                  <Music size={16} color="#06b6d4" />
                  <span>Media Controls</span>
                </button>
              </div>
            </div>

            {/* Live Notification Feed */}
            <div className="glass-panel col-span-12">
              <div className="panel-header">
                <div className="panel-title-group">
                  <Bell size={18} color="#6366f1" />
                  <span>Incoming Phone Notifications</span>
                </div>
                <span className="panel-badge">{notifications.length} received</span>
              </div>

              {notifications.length === 0 ? (
                <div className="empty-visual">
                  <div className="empty-icon-ring">
                    <Bell size={24} />
                  </div>
                  <div style={{ fontSize: 14, fontWeight: 600 }}>No notifications yet</div>
                  <p style={{ fontSize: 13, maxWidth: 360 }}>
                    Incoming notifications on your Android phone will instantly appear here and trigger Windows toasts.
                  </p>
                </div>
              ) : (
                <div className="notification-stream">
                  {notifications.map((n) => (
                    <div className="notification-bubble" key={n.key}>
                      <div className="notif-app-avatar">
                        <Bell size={18} />
                      </div>
                      <div className="notif-body">
                        <div className="notif-header-row">
                          <span className="notif-app-name">{n.appName}</span>
                          <span style={{ fontSize: 11, color: "var(--text-dim)" }}>{n.time}</span>
                        </div>
                        <span className="notif-headline">{n.title}</span>
                        <p className="notif-snippet">{n.text}</p>
                      </div>
                      <button
                        onClick={() => dismissNotification(n.key)}
                        style={{
                          background: "none",
                          border: "none",
                          color: "var(--text-dim)",
                          cursor: "pointer",
                          padding: 4,
                        }}
                        title="Dismiss"
                      >
                        <Trash2 size={16} />
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        )}

        {activeTab === "notifications" && (
          <div className="glass-panel" style={{ flex: 1 }}>
            <div className="panel-header">
              <div className="panel-title-group">
                <Bell size={18} color="#6366f1" />
                <span>All Notifications</span>
              </div>
              {notifications.length > 0 && (
                <button className="action-btn-secondary" onClick={() => setNotifications([])}>
                  <Trash2 size={14} />
                  <span>Clear All</span>
                </button>
              )}
            </div>

            {notifications.length === 0 ? (
              <div className="empty-visual" style={{ margin: "auto" }}>
                <div className="empty-icon-ring">
                  <Bell size={24} />
                </div>
                <div style={{ fontSize: 14, fontWeight: 600 }}>No notification history</div>
              </div>
            ) : (
              <div className="notification-stream" style={{ maxHeight: "650px" }}>
                {notifications.map((n) => (
                  <div className="notification-bubble" key={n.key}>
                    <div className="notif-app-avatar">
                      <Bell size={18} />
                    </div>
                    <div className="notif-body">
                      <div className="notif-header-row">
                        <span className="notif-app-name">{n.appName}</span>
                        <span style={{ fontSize: 11, color: "var(--text-dim)" }}>{n.time}</span>
                      </div>
                      <span className="notif-headline">{n.title}</span>
                      <p className="notif-snippet">{n.text}</p>
                    </div>
                    <button
                      onClick={() => dismissNotification(n.key)}
                      style={{
                        background: "none",
                        border: "none",
                        color: "var(--text-dim)",
                        cursor: "pointer",
                        padding: 4,
                      }}
                    >
                      <Trash2 size={16} />
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {activeTab === "clipboard" && (
          <div className="glass-panel" style={{ flex: 1 }}>
            <div className="panel-header">
              <div className="panel-title-group">
                <Clipboard size={18} color="#6366f1" />
                <span>Clipboard Synchronizer</span>
              </div>
              <button className="action-btn-secondary" onClick={handleCopyCurrentClipboard}>
                {copiedSuccess ? <Check size={14} color="#34d399" /> : <Copy size={14} />}
                <span>{copiedSuccess ? "Copied to PC" : "Copy to PC"}</span>
              </button>
            </div>

            <div className="clipboard-canvas" style={{ minHeight: "240px", fontSize: 14 }}>
              {lastClipboard}
            </div>

            <div className="clipboard-actions-bar" style={{ marginTop: "auto" }}>
              <input
                className="text-bar-input"
                placeholder="Type or paste to beam to phone clipboard..."
                value={clipInput}
                onChange={(e) => setClipInput(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && handleSendClipboard()}
              />
              <button className="action-btn-primary" onClick={handleSendClipboard}>
                <Send size={14} />
                <span>Push to Phone</span>
              </button>
            </div>
          </div>
        )}

        {activeTab === "files" && (
          <div className="glass-panel" style={{ flex: 1 }}>
            <div className="panel-header">
              <div className="panel-title-group">
                <FolderSync size={18} color="#6366f1" />
                <span>Direct Wi-Fi File Beam</span>
              </div>
            </div>

            <div
              style={{
                border: "2px dashed var(--border-light)",
                borderRadius: 18,
                padding: "60px 24px",
                display: "flex",
                flexDirection: "column",
                alignItems: "center",
                justifyContent: "center",
                gap: 16,
                background: "rgba(255, 255, 255, 0.02)",
                cursor: "pointer",
                margin: "auto 0",
              }}
            >
              <div className="empty-icon-ring" style={{ width: 64, height: 64 }}>
                <Share2 size={28} color="#818cf8" />
              </div>
              <div style={{ textAlign: "center" }}>
                <div style={{ fontSize: 16, fontWeight: 700 }}>Drag and drop files to beam to your phone</div>
                <div style={{ fontSize: 13, color: "var(--text-muted)", marginTop: 4 }}>
                  Files transfer over high-speed local TCP directly to your phone's Downloads directory
                </div>
              </div>
              <button className="action-btn-secondary" style={{ marginTop: 8 }}>
                Select File from PC
              </button>
            </div>
          </div>
        )}
      </main>
    </div>
  );
}
