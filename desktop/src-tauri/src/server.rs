use crate::protocol::{AndrolinkPacket, BatteryData, ClipboardData, NotificationData};
use arboard::Clipboard;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tauri::{AppHandle, Emitter};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{broadcast, mpsc, Mutex};

pub const DEFAULT_TCP_PORT: u16 = 8700;

pub struct AppState {
    pub is_connected: AtomicBool,
    pub connected_device_name: Mutex<String>,
    pub outgoing_tx: Mutex<Option<mpsc::Sender<String>>>,
    pub last_synced_clipboard: Mutex<String>,
}

impl AppState {
    pub fn new() -> Self {
        Self {
            is_connected: AtomicBool::new(false),
            connected_device_name: Mutex::new(String::new()),
            outgoing_tx: Mutex::new(None),
            last_synced_clipboard: Mutex::new(String::new()),
        }
    }
}

pub struct TcpServer {
    port: u16,
    app_handle: AppHandle,
    state: Arc<AppState>,
}

impl TcpServer {
    pub fn new(port: u16, app_handle: AppHandle, state: Arc<AppState>) -> Self {
        Self {
            port,
            app_handle,
            state,
        }
    }

    pub async fn start(self: Arc<Self>, mut shutdown_rx: broadcast::Receiver<()>) {
        let addr = format!("0.0.0.0:{}", self.port);
        let listener = match TcpListener::bind(&addr).await {
            Ok(l) => l,
            Err(e) => {
                eprintln!("[Server] Failed to bind TCP {}: {}", addr, e);
                return;
            }
        };

        println!("[Server] Listening for phone connection on TCP {}", addr);

        loop {
            tokio::select! {
                _ = shutdown_rx.recv() => {
                    println!("[Server] Stopping TCP server");
                    break;
                }
                res = listener.accept() => {
                    match res {
                        Ok((stream, peer_addr)) => {
                            println!("[Server] Incoming connection from: {}", peer_addr);
                            let server_clone = Arc::clone(&self);
                            tokio::spawn(async move {
                                server_clone.handle_connection(stream).await;
                            });
                        }
                        Err(e) => {
                            eprintln!("[Server] Accept error: {}", e);
                        }
                    }
                }
            }
        }
    }

    async fn handle_connection(&self, mut stream: TcpStream) {
        let (reader, mut writer) = stream.split();
        let mut buf_reader = BufReader::new(reader);
        let (tx, mut rx) = mpsc::channel::<String>(32);

        {
            let mut outgoing = self.state.outgoing_tx.lock().await;
            *outgoing = Some(tx);
            self.state.is_connected.store(true, Ordering::SeqCst);
        }

        let _ = self.app_handle.emit("androlink:device_connected", ());

        let mut line = String::new();

        loop {
            tokio::select! {
                // Outgoing message to send to phone
                Some(msg) = rx.recv() => {
                    if let Err(e) = writer.write_all(format!("{}\n", msg).as_bytes()).await {
                        eprintln!("[Server] Write error: {}", e);
                        break;
                    }
                }
                // Incoming message from phone
                res = buf_reader.read_line(&mut line) => {
                    match res {
                        Ok(0) => {
                            println!("[Server] Client disconnected");
                            break;
                        }
                        Ok(_) => {
                            let trimmed = line.trim();
                            if !trimmed.is_empty() {
                                self.process_incoming_packet(trimmed).await;
                            }
                            line.clear();
                        }
                        Err(e) => {
                            eprintln!("[Server] Read error: {}", e);
                            break;
                        }
                    }
                }
            }
        }

        // Clean up connection
        {
            let mut outgoing = self.state.outgoing_tx.lock().await;
            *outgoing = None;
            self.state.is_connected.store(false, Ordering::SeqCst);
        }
        let _ = self.app_handle.emit("androlink:device_disconnected", ());
    }

    async fn process_incoming_packet(&self, json_str: &str) {
        let packet: AndrolinkPacket = match serde_json::from_str(json_str) {
            Ok(p) => p,
            Err(e) => {
                eprintln!("[Server] Malformed packet: {} - {}", json_str, e);
                return;
            }
        };

        match packet.channel.as_str() {
            "clipboard" => {
                if packet.event == "sync" {
                    if let Ok(clip) = serde_json::from_value::<ClipboardData>(packet.data) {
                        println!("[Clipboard] Syncing from phone: {}", clip.content);
                        {
                            let mut last_clip = self.state.last_synced_clipboard.lock().await;
                            *last_clip = clip.content.clone();
                        }
                        // Write to Windows clipboard
                        if let Ok(mut cb) = Clipboard::new() {
                            let _ = cb.set_text(&clip.content);
                        }
                        let _ = self.app_handle.emit("androlink:clipboard_update", clip.content);
                    }
                }
            }
            "notification" => {
                if packet.event == "post" {
                    if let Ok(notif) = serde_json::from_value::<NotificationData>(packet.data) {
                        println!("[Notification] {}: {} - {}", notif.app_name, notif.title, notif.text);
                        let _ = self.app_handle.emit("androlink:notification_received", notif);
                    }
                }
            }
            "system" => {
                if packet.event == "battery" {
                    if let Ok(battery) = serde_json::from_value::<BatteryData>(packet.data) {
                        println!("[Battery] Level: {}%, Charging: {}", battery.level, battery.is_charging);
                        let _ = self.app_handle.emit("androlink:battery_update", battery);
                    }
                }
            }
            _ => {}
        }
    }
}

// Background task: watch desktop clipboard and forward to phone
pub async fn start_clipboard_monitor(state: Arc<AppState>) {
    let mut clipboard = match Clipboard::new() {
        Ok(c) => c,
        Err(e) => {
            eprintln!("[Clipboard Monitor] Failed to initialize: {}", e);
            return;
        }
    };

    let mut last_text = String::new();

    loop {
        tokio::time::sleep(tokio::time::Duration::from_millis(800)).await;

        if !state.is_connected.load(Ordering::SeqCst) {
            continue;
        }

        if let Ok(current_text) = clipboard.get_text() {
            if !current_text.is_empty() && current_text != last_text {
                let last_synced = state.last_synced_clipboard.lock().await.clone();
                if current_text != last_synced {
                    last_text = current_text.clone();
                    let packet = AndrolinkPacket {
                        id: uuid::Uuid::new_v4().to_string(),
                        channel: "clipboard".to_string(),
                        event: "sync".to_string(),
                        timestamp: chrono_now(),
                        data: serde_json::json!({
                            "mimeType": "text/plain",
                            "content": current_text
                        }),
                    };

                    if let Ok(json_str) = serde_json::to_string(&packet) {
                        let outgoing = state.outgoing_tx.lock().await;
                        if let Some(tx) = &*outgoing {
                            let _ = tx.send(json_str).await;
                        }
                    }
                }
            }
        }
    }
}

fn chrono_now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}
