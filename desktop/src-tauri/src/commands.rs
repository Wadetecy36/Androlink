use crate::protocol::AndrolinkPacket;
use crate::server::AppState;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use tauri::State;

#[tauri::command]
pub async fn get_connection_status(state: State<'_, Arc<AppState>>) -> Result<bool, String> {
    Ok(state.is_connected.load(Ordering::SeqCst))
}

#[tauri::command]
pub async fn send_clipboard(text: String, state: State<'_, Arc<AppState>>) -> Result<(), String> {
    let packet = AndrolinkPacket {
        id: uuid::Uuid::new_v4().to_string(),
        channel: "clipboard".to_string(),
        event: "sync".to_string(),
        timestamp: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as i64,
        data: serde_json::json!({
            "mimeType": "text/plain",
            "content": text
        }),
    };

    let json_str = serde_json::to_string(&packet).map_err(|e| e.to_string())?;
    let outgoing = state.outgoing_tx.lock().await;
    if let Some(tx) = &*outgoing {
        tx.send(json_str).await.map_err(|e| e.to_string())?;
        Ok(())
    } else {
        Err("No device connected".to_string())
    }
}
