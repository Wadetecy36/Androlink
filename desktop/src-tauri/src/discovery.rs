use crate::protocol::DiscoveryBeacon;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::net::UdpSocket;
use tokio::sync::broadcast;

pub const DISCOVERY_PORT: u16 = 8699;

pub struct DiscoveryService {
    device_id: String,
    device_name: String,
    tcp_port: u16,
}

impl DiscoveryService {
    pub fn new(device_id: String, device_name: String, tcp_port: u16) -> Self {
        Self {
            device_id,
            device_name,
            tcp_port,
        }
    }

    pub async fn start(
        self: Arc<Self>,
        mut shutdown_rx: broadcast::Receiver<()>,
        on_discovered: impl Fn(DiscoveryBeacon, SocketAddr) + Send + Sync + 'static,
    ) {
        let bind_addr = format!("0.0.0.0:{}", DISCOVERY_PORT);
        let socket = match UdpSocket::bind(&bind_addr).await {
            Ok(s) => {
                let _ = s.set_broadcast(true);
                Arc::new(s)
            }
            Err(e) => {
                eprintln!("[Discovery] Failed to bind UDP {}: {}", bind_addr, e);
                return;
            }
        };

        println!("[Discovery] UDP discovery listening on port {}", DISCOVERY_PORT);

        let socket_send = Arc::clone(&socket);
        let this_send = Arc::clone(&self);

        // Task: periodic broadcast
        tokio::spawn(async move {
            let broadcast_addr: SocketAddr = format!("255.255.255.255:{}", DISCOVERY_PORT).parse().unwrap();
            let beacon = DiscoveryBeacon {
                protocol: "androlink-v1".to_string(),
                beacon_type: "discovery".to_string(),
                device_id: this_send.device_id.clone(),
                device_name: this_send.device_name.clone(),
                device_type: "desktop".to_string(),
                tcp_port: this_send.tcp_port,
                version: "1.0.0".to_string(),
            };

            let payload = serde_json::to_vec(&beacon).unwrap();

            loop {
                let _ = socket_send.send_to(&payload, broadcast_addr).await;
                tokio::time::sleep(tokio::time::Duration::from_secs(3)).await;
            }
        });

        // Task: receive beacons from phones
        let mut buf = [0u8; 4096];
        loop {
            tokio::select! {
                _ = shutdown_rx.recv() => {
                    println!("[Discovery] Stopping UDP listener");
                    break;
                }
                res = socket.recv_from(&mut buf) => {
                    match res {
                        Ok((len, peer)) => {
                            if let Ok(beacon) = serde_json::from_slice::<DiscoveryBeacon>(&buf[..len]) {
                                if beacon.protocol == "androlink-v1" && beacon.device_id != self.device_id {
                                    on_discovered(beacon, peer);
                                }
                            }
                        }
                        Err(e) => {
                            eprintln!("[Discovery] Receive error: {}", e);
                            break;
                        }
                    }
                }
            }
        }
    }
}
