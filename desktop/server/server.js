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

const DOWNLOADS_DIR = path.join(os.homedir(), 'Downloads');
const STAGING_DIR = path.join(__dirname, 'staging');

if (!fs.existsSync(STAGING_DIR)) {
  fs.mkdirSync(STAGING_DIR, { recursive: true });
}

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

// 1. HTTP Server (Serves SPA, File Upload/Download APIs)
const server = http.createServer((req, res) => {
  // Enable CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-Filename');

  if (req.method === 'OPTIONS') {
    res.writeHead(200);
    return res.end();
  }

  // Handle File Download for Phone: /api/download/:filename
  if (req.url.startsWith('/api/download/')) {
    const filename = decodeURIComponent(req.url.replace('/api/download/', ''));
    const safePath = path.join(STAGING_DIR, path.basename(filename));
    if (fs.existsSync(safePath)) {
      const stat = fs.statSync(safePath);
      res.writeHead(200, {
        'Content-Type': 'application/octet-stream',
        'Content-Length': stat.size,
        'Content-Disposition': `attachment; filename="${path.basename(filename)}"`
      });
      return fs.createReadStream(safePath).pipe(res);
    } else {
      res.writeHead(404);
      return res.end('File not found');
    }
  }

  // Handle File Upload: POST /api/upload
  if (req.method === 'POST' && req.url === '/api/upload') {
    const rawFilename = req.headers['x-filename'] || `file-${Date.now()}`;
    const filename = path.basename(decodeURIComponent(rawFilename));
    const isFromPhone = req.headers['x-source'] === 'phone';

    // If from phone, save directly to Windows Downloads; if from PC UI, save to staging to beam to phone
    const targetDir = isFromPhone ? DOWNLOADS_DIR : STAGING_DIR;
    const destPath = path.join(targetDir, filename);

    const fileStream = fs.createWriteStream(destPath);
    req.pipe(fileStream);

    fileStream.on('finish', () => {
      console.log(`[File Transfer] Saved ${filename} to ${targetDir}`);

      if (isFromPhone) {
        showWindowsToast('File Received from Phone', `${filename} saved to Downloads folder`);
        broadcastToFrontends({
          type: 'androlink:file_received',
          data: { filename, path: destPath }
        });
      } else {
        // Send offer to phone so it downloads the file
        const netInfo = getNetworkDetails();
        const stat = fs.statSync(destPath);
        const downloadUrl = `http://${netInfo.localIp}:${HTTP_PORT}/api/download/${encodeURIComponent(filename)}`;
        
        sendPacketToPhone({
          channel: 'file',
          event: 'offer',
          data: {
            fileName: filename,
            fileSize: stat.size,
            downloadUrl
          }
        });
        showWindowsToast('Beaming File to Phone', `Sending ${filename} to your phone...`);
      }

      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ status: 'success', filename }));
    });

    fileStream.on('error', (err) => {
      console.error('[File Transfer] Stream error:', err);
      res.writeHead(500);
      res.end('Error saving file');
    });
    return;
  }

  // Static Assets / SPA Serving
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
      } else if (data.type === 'ring_phone') {
        sendPacketToPhone({ channel: 'system', event: 'ring', data: {} });
        console.log('[System] Sent ring command to phone');
      } else if (data.type === 'media_command' && data.action) {
        sendPacketToPhone({ channel: 'media', event: 'command', data: { action: data.action } });
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
  } else if (packet.channel === 'system' && packet.event === 'ping') {
    console.log('[System] Phone pinged laptop!');
    showWindowsToast('Androlink', 'Your phone pinged this laptop! 👋');
    try {
      // Play Windows alert beep
      exec('powershell.exe -c "[console]::beep(800, 300); [console]::beep(1200, 300)"', () => {});
    } catch (_) {}
  }
}

function sendPacketToPhone(packetObj) {
  if (!connectedPhoneSocket) return false;
  const packet = {
    id: `msg-${Date.now()}`,
    timestamp: Date.now(),
    ...packetObj
  };
  try {
    connectedPhoneSocket.write(JSON.stringify(packet) + '\n');
    return true;
  } catch (err) {
    console.error('[TCP] Failed sending packet to phone:', err.message);
    return false;
  }
}

function setPhoneClipboard(text) {
  lastClipboard = text;
  sendPacketToPhone({
    channel: 'clipboard',
    event: 'sync',
    data: {
      mimeType: 'text/plain',
      content: text
    }
  });
  console.log(`[Clipboard] Pushed to phone: "${text.substring(0, 40)}..."`);
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

// 5. Multi-Target UDP Discovery
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

    // 2. Subnet Broadcast
    if (netInfo.subnetBroadcast) {
      udpSocket.send(message, 0, message.length, UDP_PORT, netInfo.subnetBroadcast, () => {});
    }

    // 3. Direct Unicast to Phone Hotspot Gateway
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
  console.log(`📁 Downloads Target: ${DOWNLOADS_DIR}`);
  console.log(`=================================================\n`);
});
