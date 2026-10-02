use serde::{Deserialize, Serialize};
use serde_json::Value;
use uuid::Uuid;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiscoveryBeacon {
    pub protocol: String,
    #[serde(rename = "type")]
    pub beacon_type: String,
    pub device_id: String,
    pub device_name: String,
    pub device_type: String,
    pub tcp_port: u16,
    pub version: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AndrolinkPacket {
    #[serde(default = "generate_uuid")]
    pub id: String,
    pub channel: String,
    pub event: String,
    #[serde(default = "current_timestamp")]
    pub timestamp: i64,
    pub data: Value,
}

fn generate_uuid() -> String {
    Uuid::new_v4().to_string()
}

fn current_timestamp() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BatteryData {
    pub level: i32,
    pub is_charging: bool,
    #[serde(default)]
    pub power_save_mode: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipboardData {
    pub mime_type: String,
    pub content: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NotificationData {
    pub key: String,
    pub app_name: String,
    pub package_name: String,
    pub title: String,
    pub text: String,
}
