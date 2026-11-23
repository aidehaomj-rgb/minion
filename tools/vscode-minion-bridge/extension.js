'use strict';

// Minion 本地 IDE 桥接插件（零外部 npm 依赖，兼容 VS Code 1.70.2 / Node 16.13.2 / Win7 x64）
const vscode = require('vscode');
const net = require('net');
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');

const PROTOCOL_VERSION = 1;
const PLUGIN_ID = 'minion.minion-ide-bridge';
const PLUGIN_VERSION = '0.1.0';
const DIFF_SCHEME = 'minion-diff';

let socket = null;
let connectedPort = null;
let statusBar = null;
let pollTimer = null;
let lineBuffer = '';
let diffSeq = 0;
const diffStore = new Map();
const diffEmitter = new vscode.EventEmitter();

function computePluginHash() {
  try {
    const content = fs.readFileSync(__filename);
    return crypto.createHash('sha256').update(content).digest('hex');
  } catch (e) {
    return 'unknown';
  }
}

function lockDir() {
  return path.join(os.homedir(), '.minion', 'ide');
}

function activeWorkspaceRealPath() {
  const folders = vscode.workspace.workspaceFolders;
  if (!folders || folders.length === 0) return null;
  try {
    return fs.realpathSync(folders[0].uri.fsPath);
  } catch (e) {
    return path.resolve(folders[0].uri.fsPath);
  }
}

function samePath(a, b) {
  if (!a || !b) return false;
  if (process.platform === 'win32') {
    return path.resolve(a).toLowerCase() === path.resolve(b).toLowerCase();
  }
  return path.resolve(a) === path.resolve(b);
}

function discoverMatchingLock() {
  const ws = activeWorkspaceRealPath();
  if (!ws) return null;
  const dir = lockDir();
  if (!fs.existsSync(dir)) return null;
  let files;
  try {
    files = fs.readdirSync(dir).filter(f => /^minion-ide-\d+\.lock$/.test(f));
  } catch (e) {
    return null;
  }
  const candidates = [];
  for (const name of files) {
    const full = path.join(dir, name);
    try {
      const raw = fs.readFileSync(full, 'utf8');
      const doc = JSON.parse(raw);
      if (!doc || typeof doc.port !== 'number' || !doc.token || !doc.workspace) continue;
      let lockWs = doc.workspace;
      try { lockWs = fs.realpathSync(lockWs); } catch (_) { /* keep raw */ }
      if (samePath(lockWs, ws)) {
        candidates.push({ file: full, doc, mtime: fs.statSync(full).mtimeMs });
      }
    } catch (_) {
      // 忽略损坏或陈旧锁文件
    }
  }
  candidates.sort((a, b) => b.mtime - a.mtime);
  return candidates.length > 0 ? candidates[0].doc : null;
}

function updateStatus(connected, detail) {
  if (!statusBar) return;
  if (connected) {
    statusBar.text = '$(plug) Minion: 已连接';
    statusBar.tooltip = detail || ('已连接到 127.0.0.1:' + connectedPort);
  } else {
    statusBar.text = '$(debug-disconnect) Minion: 未连接';
    statusBar.tooltip = detail || '点击尝试发现并连接 Minion 本地 IDE 桥接';
  }
}

function sendFrame(obj) {
  if (!socket || socket.destroyed) return;
  socket.write(JSON.stringify(obj) + '\n', 'utf8');
}

function sendResponse(id, ok, data, error) {
  const frame = { type: 'response', id: id, ok: !!ok };
  if (ok) {
    frame.data = data || {};
  } else {
    frame.error = error || 'IDE 操作失败';
  }
  sendFrame(frame);
}

async function handleRequest(frame) {
  const type = frame.type;
  const id = frame.id || '';
  try {
    if (type === 'hello_ack') {
      if (frame.ok) {
        updateStatus(true, '已连接到 127.0.0.1:' + connectedPort + ' (' + (frame.workspace || '') + ')');
      } else {
        const msg = frame.error || frame.code || '握手被拒绝';
        updateStatus(false, '握手失败: ' + msg);
        vscode.window.showWarningMessage('Minion IDE 桥接握手失败: ' + msg);
        disconnect();
      }
      return;
    }

    if (type === 'get_selection') {
      const editor = vscode.window.activeTextEditor;
      if (!editor) {
        sendResponse(id, true, { file: '', startLine: 0, startColumn: 0, endLine: 0, endColumn: 0, text: '' });
        return;
      }
      const sel = editor.selection;
      const text = editor.document.getText(sel);
      sendResponse(id, true, {
        file: editor.document.uri.fsPath,
        startLine: sel.start.line + 1,
        startColumn: sel.start.character + 1,
        endLine: sel.end.line + 1,
        endColumn: sel.end.character + 1,
        text: text
      });
      return;
    }

    if (type === 'open_file') {
      const target = frame.file;
      if (!target) {
        sendResponse(id, false, null, '缺少 file 参数');
        return;
      }
      const doc = await vscode.workspace.openTextDocument(vscode.Uri.file(target));
      const editor = await vscode.window.showTextDocument(doc, { preview: false });
      const line = Math.max(1, Number(frame.line) || 1) - 1;
      const col = Math.max(1, Number(frame.column) || 1) - 1;
      const pos = new vscode.Position(line, col);
      editor.selection = new vscode.Selection(pos, pos);
      editor.revealRange(new vscode.Range(pos, pos), vscode.TextEditorRevealType.InCenter);
      sendResponse(id, true, { opened: target });
      return;
    }

    if (type === 'open_diff') {
      const target = frame.file;
      if (!target) {
        sendResponse(id, false, null, '缺少 file 参数');
        return;
      }
      const proposed = typeof frame.proposedContent === 'string' ? frame.proposedContent : '';
      const title = frame.title || ('Minion Diff: ' + path.basename(target));
      const key = 'diff-' + (++diffSeq) + '/' + path.basename(target);
      const proposedUri = vscode.Uri.parse(DIFF_SCHEME + ':/' + encodeURIComponent(key));
      diffStore.set(proposedUri.toString(), proposed);
      diffEmitter.fire(proposedUri);
      await vscode.commands.executeCommand('vscode.diff', vscode.Uri.file(target), proposedUri, title);
      sendResponse(id, true, { opened: target });
      return;
    }

    if (type === 'get_diagnostics') {
      const severityNames = ['error', 'warning', 'info', 'hint'];
      const items = [];
      const filterFile = frame.file ? path.resolve(frame.file) : null;
      const all = filterFile
        ? [[vscode.Uri.file(filterFile), vscode.languages.getDiagnostics(vscode.Uri.file(filterFile))]]
        : vscode.languages.getDiagnostics();
      for (const pair of all) {
        const uri = pair[0];
        const diags = pair[1] || [];
        for (const d of diags) {
          items.push({
            file: uri.fsPath,
            line: d.range.start.line + 1,
            column: d.range.start.character + 1,
            severity: severityNames[d.severity] || 'info',
            message: d.message || '',
            source: d.source || ''
          });
        }
      }
      sendResponse(id, true, { items: items });
      return;
    }

    if (type === 'can_use_tool') {
      const tool = frame.tool || 'unknown';
      const summary = frame.summary || '';
      const detail = summary ? (tool + ': ' + summary) : tool;
      const choice = await vscode.window.showWarningMessage(
        'Minion 请求执行工具确认：' + detail,
        { modal: true },
        '允许',
        '拒绝'
      );
      const allowed = choice === '允许';
      sendResponse(id, true, {
        allowed: allowed,
        reason: allowed ? '用户在 VS Code 中点击允许' : '用户在 VS Code 中拒绝或取消'
      });
      return;
    }

    if (type === 'pong') {
      return;
    }

    if (id) {
      sendResponse(id, false, null, '未知请求类型: ' + type);
    }
  } catch (err) {
    if (id) {
      sendResponse(id, false, null, err && err.message ? err.message : String(err));
    }
  }
}

function connect(silent) {
  if (socket && !socket.destroyed) return;
  const lock = discoverMatchingLock();
  if (!lock) {
    updateStatus(false, '未发现当前工作区的 Minion 桥接锁文件');
    if (!silent) {
      vscode.window.showInformationMessage('未发现当前工作区的 Minion IDE 桥接，请先在 Minion 中输入 /ide start。');
    }
    return;
  }
  const ws = activeWorkspaceRealPath();
  lineBuffer = '';
  connectedPort = lock.port;
  const sock = net.createConnection({ host: '127.0.0.1', port: lock.port }, () => {
    sendFrame({
      type: 'hello',
      protocolVersion: PROTOCOL_VERSION,
      token: lock.token,
      workspace: ws,
      ideName: 'Visual Studio Code',
      ideVersion: vscode.version,
      pluginId: PLUGIN_ID,
      pluginVersion: PLUGIN_VERSION,
      pluginHash: computePluginHash()
    });
  });
  socket = sock;

  sock.setEncoding('utf8');
  sock.on('data', chunk => {
    lineBuffer += chunk;
    let idx;
    while ((idx = lineBuffer.indexOf('\n')) >= 0) {
      const line = lineBuffer.slice(0, idx).trim();
      lineBuffer = lineBuffer.slice(idx + 1);
      if (!line) continue;
      try {
        const frame = JSON.parse(line);
        handleRequest(frame);
      } catch (_) {
        // 忽略非法帧
      }
    }
  });

  sock.on('error', err => {
    updateStatus(false, '连接错误: ' + (err && err.message ? err.message : String(err)));
  });

  sock.on('close', () => {
    if (socket === sock) {
      socket = null;
      connectedPort = null;
      lineBuffer = '';
      updateStatus(false, '连接已断开');
    }
  });
}

function disconnect() {
  if (socket) {
    try { socket.destroy(); } catch (_) { /* ignore */ }
    socket = null;
  }
  connectedPort = null;
  lineBuffer = '';
  updateStatus(false, '已手动断开');
}

function activate(context) {
  const provider = {
    onDidChange: diffEmitter.event,
    provideTextDocumentContent(uri) {
      return diffStore.get(uri.toString()) || '';
    }
  };
  context.subscriptions.push(
    vscode.workspace.registerTextDocumentContentProvider(DIFF_SCHEME, provider)
  );

  statusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
  statusBar.command = 'minionBridge.connect';
  updateStatus(false);
  statusBar.show();
  context.subscriptions.push(statusBar);

  context.subscriptions.push(
    vscode.commands.registerCommand('minionBridge.connect', () => connect(false)),
    vscode.commands.registerCommand('minionBridge.disconnect', () => {
      disconnect();
      vscode.window.showInformationMessage('已断开 Minion IDE 桥接。');
    }),
    vscode.commands.registerCommand('minionBridge.status', () => {
      const msg = socket && !socket.destroyed
        ? ('Minion IDE 桥接已连接（127.0.0.1:' + connectedPort + '）')
        : 'Minion IDE 桥接未连接';
      vscode.window.showInformationMessage(msg);
    })
  );

  // 自动发现并定时重连（不重放旧请求）
  connect(true);
  pollTimer = setInterval(() => {
    if (!socket || socket.destroyed) {
      connect(true);
    }
  }, 5000);
  context.subscriptions.push({ dispose: () => { if (pollTimer) clearInterval(pollTimer); } });
}

function deactivate() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
  disconnect();
  diffStore.clear();
}

module.exports = { activate, deactivate };
