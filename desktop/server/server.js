import dgram from 'node:dgram';
import net from 'node:net';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { exec, execSync, spawn } from 'node:child_process';
import { WebSocketServer } from 'ws';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const UDP_PORT = 8699;
const TCP_PORT = 8700;
const HTTP_PORT = 8702;

const DEVICE_NAME = process.env.COMPUTERNAME || 'Windows-PC';
const DEVICE_ID = `androlink-win-${Math.random().toString(36).substring(2, 8)}`;

console.log(`[Androlink] Starting Lightweight Desktop Host on ${DEVICE_NAME}...`);

// Network Interface Detection
function getNetworkDetails() {
  let localIp = '127.0.0.1';
  let gatewayIp = null;

  try {
    const out = execSync('route print 0.0.0.0').toString();
    const match = out.match(/0\.0\.0\.0\s+0\.0\.0\.0\s+([0-9\.]+)\s+([0-9\.]+)/);
    if (match) {
      gatewayIp = match[1];
      localIp = match[2];
    }
  } catch (e) {}

  if (localIp === '127.0.0.1') {
    const interfaces = os.networkInterfaces();
    for (const name of Object.keys(interfaces)) {
      for (const iface of interfaces[name]) {
        if (iface.family === 'IPv4' && !iface.internal) {
          localIp = iface.address;
          break;
        }
      }
    }
  }

  const parts = localIp.split('.');
  const subnetBroadcast = parts.length === 4 ? `${parts[0]}.${parts[1]}.${parts[2]}.255` : '255.255.255.255';
  return { localIp, gatewayIp, subnetBroadcast };
}

const network = getNetworkDetails();
console.log(`[Network] Active IP: ${network.localIp} | Hotspot/Gateway: ${network.gatewayIp || 'None'}`);

// State
let connectedPhoneSocket = null;
let currentPhoneName = 'None';
let lastClipboard = '';
let isConnected = false;

// 1. HTTP & WebSocket Server for React Dashboard UI
const server = http.createServer((req, res) => {
  const distDir = path.join(__dirname, '..', 'dist');
  let reqPath = req.url === '/' ? '/index.html' : req.url;
  const filePath = path.join(distDir, reqPath);

  if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
    const ext = path.extname(filePath);
    const mimeTypes = {
      '.html': 'text/html',
      '.js': 'text/javascript',
      '.css': 'text/css',
      '.svg': 'image/svg+xml',
      '.json': 'application/json',
    };
    res.writeHead(200, { 'Content-Type': mimeTypes[ext] || 'application/octet-stream' });
    fs.createReadStream(filePath).pipe(res);
  } else {
    const indexHtml = path.join(distDir, 'index.html');
    if (fs.existsSync(indexHtml)) {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      fs.createReadStream(indexHtml).pipe(res);
    } else {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end('<h1>Androlink Backend Running</h1><p>Run <code>pnpm dev</code> or <code>pnpm build</code> to access the UI.</p>');
    }
  }
});

const wss = new WebSocketServer({ server });
const frontendClients = new Set();

wss.on('connection', (ws) => {
  frontendClients.add(ws);

  const netInfo = getNetworkDetails();
  ws.send(JSON.stringify({
    type: 'state',
    data: {
      isConnected,
      deviceName: currentPhoneName,
      lastClipboard,
      localIp: netInfo.localIp,
      gatewayIp: netInfo.gatewayIp,
      tcpPort: TCP_PORT
    }
  }));

  ws.on('message', (message) => {
    try {
      const data = JSON.parse(message.toString());
      if (data.type === 'send_clipboard' && data.text) {
        setPhoneClipboard(data.text);
      }
    } catch (e) {
      console.error('[WS] Error processing client message:', e);
    }
  });

  ws.on('close', () => frontendClients.delete(ws));
});

function broadcastToFrontends(message) {
  const payload = JSON.stringify(message);
  for (const client of frontendClients) {
    if (client.readyState === 1) {
      client.send(payload);
    }
  }
}

// 2. TCP Server for Phone Connection
const tcpServer = net.createServer((socket) => {
  console.log(`[TCP] Phone connected from ${socket.remoteAddress}:${socket.remotePort}`);
  connectedPhoneSocket = socket;
  isConnected = true;

  broadcastToFrontends({
    type: 'androlink:device_connected',
    data: { address: socket.remoteAddress }
  });

  let buffer = '';
  socket.on('data', (chunk) => {
    buffer += chunk.toString('utf8');
    const lines = buffer.split('\n');
    buffer = lines.pop();

    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed) continue;
      try {
        const packet = JSON.parse(trimmed);
        handlePhonePacket(packet);
      } catch (err) {
        console.error('[TCP] Malformed packet:', trimmed, err.message);
      }
    }
  });

  socket.on('close', () => {
    console.log('[TCP] Phone disconnected');
    connectedPhoneSocket = null;
    isConnected = false;
    currentPhoneName = 'None';
    broadcastToFrontends({ type: 'androlink:device_disconnected' });
  });

  socket.on('error', (err) => {
    console.error('[TCP] Socket error:', err.message);
  });
});

tcpServer.listen(TCP_PORT, '0.0.0.0', () => {
  console.log(`[TCP] Listening for phone on 0.0.0.0:${TCP_PORT}`);
});

function handlePhonePacket(packet) {
  if (packet.channel === 'clipboard' && packet.event === 'sync') {
    const text = packet.data?.content || '';
    if (text && text !== lastClipboard) {
      lastClipboard = text;
      console.log(`[Clipboard] Synced from phone: "${text.substring(0, 40)}..."`);
      setWindowsClipboard(text);
      broadcastToFrontends({ type: 'androlink:clipboard_update', payload: text });
    }
  } else if (packet.channel === 'notification' && packet.event === 'post') {
    console.log(`[Notification] ${packet.data.appName}: ${packet.data.title} - ${packet.data.text}`);
    showWindowsToast(packet.data.appName, `${packet.data.title}: ${packet.data.text}`);
    broadcastToFrontends({ type: 'androlink:notification_received', payload: packet.data });
  } else if (packet.channel === 'system' && packet.event === 'battery') {
    broadcastToFrontends({ type: 'androlink:battery_update', payload: packet.data });
  }
}

function setPhoneClipboard(text) {
  if (!connectedPhoneSocket) return;
  lastClipboard = text;
  const packet = {
    id: `msg-${Date.now()}`,
    channel: 'clipboard',
    event: 'sync',
    timestamp: Date.now(),
    data: {
      mimeType: 'text/plain',
      content: text
    }
  };
  try {
    connectedPhoneSocket.write(JSON.stringify(packet) + '\n');
    console.log(`[Clipboard] Pushed to phone: "${text.substring(0, 40)}..."`);
  } catch (err) {
    console.error('[Clipboard] Failed to push to phone:', err.message);
  }
}

// 3. Native Windows Clipboard integration
function setWindowsClipboard(text) {
  try {
    const proc = spawn('powershell.exe', ['-Command', 'Set-Clipboard -Value $input'], { stdio: ['pipe', 'ignore', 'ignore'] });
    proc.stdin.write(text);
    proc.stdin.end();
  } catch (e) {
    console.error('[Windows Clipboard] Write error:', e);
  }
}

function getWindowsClipboard(cb) {
  exec('powershell.exe -NoProfile -Command "Get-Clipboard"', (err, stdout) => {
    if (err) return;
    cb(stdout.trim());
  });
}

setInterval(() => {
  if (!isConnected || !connectedPhoneSocket) return;
  getWindowsClipboard((text) => {
    if (text && text !== lastClipboard) {
      lastClipboard = text;
      setPhoneClipboard(text);
      broadcastToFrontends({ type: 'androlink:clipboard_update', payload: text });
    }
  });
}, 1200);

// 4. Windows Toast Notifications
function showWindowsToast(title, message) {
  const cleanTitle = (title || 'Androlink').replace(/["`$]/g, '');
  const cleanMsg = (message || '').replace(/["`$]/g, '');
  const script = `
    [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
    [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null
    $template = @"
    <toast>
      <visual>
        <binding template="ToastGeneric">
          <text>${cleanTitle}</text>
          <text>${cleanMsg}</text>
        </binding>
      </visual>
    </toast>
"@
    $xml = New-Object Windows.Data.Xml.Dom.XmlDocument
    $xml.LoadXml($template)
    $toast = [Windows.UI.Notifications.ToastNotification]::new($xml)
    [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier("Androlink").Show($toast)
  `;
  exec(`powershell.exe -NoProfile -Command "${script.replace(/\r?\n/g, ' ')}"`, () => {});
}

// 5. Multi-Target UDP Discovery (Broadcast + Subnet Directed + Gateway Direct Unicast)
const udpSocket = dgram.createSocket({ type: 'udp4', reuseAddr: true });

udpSocket.on('listening', () => {
  udpSocket.setBroadcast(true);
  console.log(`[UDP] Discovery listening on port ${UDP_PORT}`);

  setInterval(() => {
    const netInfo = getNetworkDetails();
    const beacon = JSON.stringify({
      protocol: 'androlink-v1',
      type: 'discovery',
      deviceId: DEVICE_ID,
      deviceName: DEVICE_NAME,
      deviceType: 'desktop',
      tcpPort: TCP_PORT,
      version: '1.0.0'
    });
    const message = Buffer.from(beacon);

    // 1. Global Broadcast
    udpSocket.send(message, 0, message.length, UDP_PORT, '255.255.255.255', () => {});

    // 2. Subnet Broadcast (e.g. 10.168.123.255)
    if (netInfo.subnetBroadcast) {
      udpSocket.send(message, 0, message.length, UDP_PORT, netInfo.subnetBroadcast, () => {});
    }

    // 3. Direct Unicast to Phone Hotspot Gateway (e.g. 10.168.123.154)
    if (netInfo.gatewayIp && netInfo.gatewayIp !== '0.0.0.0') {
      udpSocket.send(message, 0, message.length, UDP_PORT, netInfo.gatewayIp, () => {});
    }
  }, 3000);
});

udpSocket.on('message', (msg, rinfo) => {
  try {
    const beacon = JSON.parse(msg.toString('utf8'));
    if (beacon.protocol === 'androlink-v1' && beacon.deviceId !== DEVICE_ID) {
      if (beacon.deviceType === 'phone') {
        currentPhoneName = beacon.deviceName;
        console.log(`[UDP] Discovered phone: ${beacon.deviceName} at ${rinfo.address}`);
      }
    }
  } catch (err) {}
});

udpSocket.bind(UDP_PORT);

// Start HTTP Server
server.listen(HTTP_PORT, () => {
  const netInfo = getNetworkDetails();
  console.log(`\n=================================================`);
  console.log(`🚀 Androlink Desktop Dashboard: http://localhost:${HTTP_PORT}`);
  console.log(`💻 Laptop IP: ${netInfo.localIp} (Port ${TCP_PORT})`);
  if (netInfo.gatewayIp) {
    console.log(`📱 Phone Hotspot IP: ${netInfo.gatewayIp}`);
  }
  console.log(`=================================================\n`);
});
