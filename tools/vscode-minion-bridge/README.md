# Minion IDE Bridge（VS Code 1.70+ 离线插件）

面向 **Windows 7 SP1 x64 + VS Code 1.70.2（Node 16.13.2）离线内网环境** 的 Minion 本地 IDE 桥接插件（v2.4 §12.3 路径 A）。

- **零外部依赖**：仅使用 Node.js 16 内置 `net` / `fs` / `path` / `os` / `crypto` 与 VS Code Extension API（`engines.vscode: ^1.60.0`）。
- **安全边界**：仅连接 `127.0.0.1`，通过 `~/.minion/ide/minion-ide-<port>.lock` 自动发现同工作区 Minion 实例，握手校验随机令牌 + 工作区 realpath + `extension.js` SHA-256 指纹（`ProjectTrustStore`）。
- **支持能力**：获取当前选区（`get_selection`）、打开工作区内文件并跳转行号（`open_file`）、只读 Diff 预览（`open_diff`）、读取诊断信息（`get_diagnostics`）、工具执行弹窗确认（`can_use_tool`，fail-closed）。

## 离线打包 VSIX（无需 npm / 外网）

在任意装有 Python 3 的机器上执行：

```bash
python tools/vscode-minion-bridge/build-vsix.py
```

会在同目录生成 `minion-ide-bridge-0.1.0.vsix`。

## 内网 Win7 安装与使用

1. **安装插件**（二选一）：
   - 命令行：`code --install-extension minion-ide-bridge-0.1.0.vsix`
   - 界面：VS Code 扩展面板右上角 `...` → **从 VSIX 安装...** → 选中 `minion-ide-bridge-0.1.0.vsix`
2. **启动 Minion 桥接**：
   - 在 Minion 聊天框输入 `/ide start`（或在配置中启用 `ide.bridge.enabled=true`）
   - 首次连接（或插件更新后）Minion 会弹出 `ProjectTrustStore` 信任确认框，批准后持久生效
3. **验证连接**：
   - VS Code 右下角状态栏显示 `Minion: 已连接`
   - 在 Minion 中输入 `/ide`、`/ide selection`、`/ide diagnostics` 或 `/ide open <文件> [行号]`
