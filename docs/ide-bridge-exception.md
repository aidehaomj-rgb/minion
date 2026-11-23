# §12.3 本地 IDE 桥接——实施记录（切片 38，已转路径 A）

日期：2026-11-23（基于 Win7 SP1 x64 实机 VS Code 1.70.2 证据转路径 A 实施）
状态：**已实施（路径 A）**——替代 2026-11-22 路径 B 备档草案

## 一、Win7 实机证据（POC 第 10 项）

用户在内网办公机实机确认已安装 **Visual Studio Code 1.70.2**（最后支持 Win7 的官方稳定版）：
- Version: `1.70.2`（Commit `e4503b30fc78200f846c62cf8091b76ff5547662`，2022-08-16）
- Electron: `18.3.5` / Chromium: `100.0.4896.160` / Node.js: `16.13.2`
- OS: `Windows_NT x64 6.1.7601`（Win7 SP1 x64）

依据 v2.4 §12.3 与方向稿切片 38 裁决规则，正式从路径 B 备档转入 **路径 A 实施**。

## 二、已落地交付物

1. **Minion 端核心桥接**：`src/main/java/com/minion/core/bridge/IdeBridge.java`
   - 仅绑定 `127.0.0.1` 回环地址，accept 后二次校验 `isLoopbackAddress()`
   - 32 字节随机会话令牌（`MessageDigest.isEqual` 常量时间校验）
   - 发现锁文件 `~/.minion/ide/minion-ide-<port>.lock`（关闭自动清理）
   - 严格 `hello` 握手（5 秒超时）：校验 `protocolVersion == 1`、令牌、`workspace` realpath 一致性，以及 `ProjectTrustStore` 插件指纹（插件更新/改动即失效重审）
   - 双向 NDJSON 协议（复用 `SdkFrameIo` 1MiB 有界帧）：`get_selection`、`open_file`、`open_diff`（只读预览）、`get_diagnostics`、`can_use_tool`（fail-closed）
   - `open_file` / `open_diff` / `get_diagnostics` 全部经 `PathsGuard.inside` 越界守卫校验
   - 断线立即作废全部在途请求，重连绝不重放旧请求；入站请求 ID 有界 LRU 去重
2. **会话与命令接线**：
   - `Config.ideBridgeEnabled()`（`ide.bridge.enabled` 默认保持 `false`，保守起步）
   - `CommandDispatcher` + `SessionManager` 接入 `/ide [status|start|stop|selection|diagnostics|open <file> [line]]`，`SessionManager.shutdown()` 自动清理全部桥接与锁文件
3. **离线 VS Code 1.70.2 插件**：`tools/vscode-minion-bridge/`
   - `package.json`（`engines.vscode: ^1.60.0`，零 npm 依赖）
   - `extension.js`（纯 Node 16 内置模块 + VS Code API，上报 `extension.js` SHA-256 指纹）
   - `build-vsix.py`（纯 Python 标准库离线生成 `.vsix`）
   - `README.md`（离线打包与内网安装指南）
4. **自动化测试**：`src/test/java/com/minion/core/bridge/IdeBridgeTest.java`

## 三、默认配置说明

`ide.bridge.enabled` 默认维持 `false`（不影响未安装插件的内网机器）；需要联调 VS Code 时，可在 Minion 输入 `/ide start` 立即监听，或在配置中设置 `ide.bridge.enabled=true`。
