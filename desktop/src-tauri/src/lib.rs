mod commands;
mod discovery;
mod protocol;
mod server;

use discovery::DiscoveryService;
use server::{start_clipboard_monitor, AppState, TcpServer, DEFAULT_TCP_PORT};
use std::sync::Arc;
use tauri::Manager;
use tokio::sync::broadcast;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app_state = Arc::new(AppState::new());
    let state_for_setup = Arc::clone(&app_state);

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_notification::init())
        .manage(app_state)
        .invoke_handler(tauri::generate_handler![
            commands::get_connection_status,
            commands::send_clipboard
        ])
        .setup(move |app| {
            let app_handle = app.handle().clone();
            let (shutdown_tx, _) = broadcast::channel(1);

            let device_name = whoami_hostname();
            let device_id = format!("androlink-win-{}", &uuid::Uuid::new_v4().to_string()[..8]);

            // Start UDP discovery
            let discovery = Arc::new(DiscoveryService::new(
                device_id,
                device_name,
                DEFAULT_TCP_PORT,
            ));
            let disc_shutdown = shutdown_tx.subscribe();
            tauri::async_runtime::spawn(async move {
                discovery.start(disc_shutdown, |_beacon, _peer| {}).await;
            });

            // Start TCP server
            let server = Arc::new(TcpServer::new(
                DEFAULT_TCP_PORT,
                app_handle,
                Arc::clone(&state_for_setup),
            ));
            let srv_shutdown = shutdown_tx.subscribe();
            tauri::async_runtime::spawn(async move {
                server.start(srv_shutdown).await;
            });

            // Start Clipboard monitor
            let state_for_clip = Arc::clone(&state_for_setup);
            tauri::async_runtime::spawn(async move {
                start_clipboard_monitor(state_for_clip).await;
            });

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

fn whoami_hostname() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| "Windows PC".to_string())
}
