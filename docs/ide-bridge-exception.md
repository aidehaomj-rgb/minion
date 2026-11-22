# §12.3 本地 IDE 桥接——路径 B 豁免记录（切片 38）

日期：2026-11-22（切片 38 落档）
状态：**待用户正式确认**（本文件为路径 B 备档草案；用户签字后生效）

## 一、决策背景

v2.4 §12.3 要求本地 IDE 桥接（core/bridge/IdeBridge.java：仅绑 127.0.0.1/::1 + 随机会话
令牌 + workspace realpath 握手 + 协议版本协商 + 选区/打开文件/diff/诊断/权限回调/断线重连
不重放 + 插件更新重走 ProjectTrustStore + /ide 命令 + 离线 IDE 插件）。

**前置条件**（方向稿切片 38）：Win7 POC 第 10 项——候选 IDE + 插件 API 实测证据
（`docs/win7-poc/README.md` 清单第 10 行）。截至今日该证据**尚未取得**（Win7 实机在
用户处，POC 清单全部 12 项均待实机执行）。

## 二、候选 IDE 分析（开发机侧桌面分析，非实机证据）

| 候选 | Win7 x64 支持 | 插件 API | 内网离线安装 | 初判 |
|---|---|---|---|---|
| VS Code 1.70-（2022 终版支持 Win7） | ✅ 最后版本 | Extension API（TCP 桥需自写插件） | ✅ VSIX 离线安装 | **首选候选**：API 成熟，WSL 不需要 |
| Eclipse 4.7-4.10（2018 终版支持 Win7） | ✅ | PDE/CDT（Java 原生） | ✅ ZIP 解压即用 | 次选：零外部依赖但 UI 老 |
| IntelliJ IDEA 2018.2（终版支持 Win7） | ✅（社区版免费） | Plugin API（Gradle/SDK 构建插件较重） | ⚠️ 离线插件安装繁琐 | 备选 |
| HBuilder / Sublime / Notepad++ | ✅ | 弱或无标准 API | — | 排除：无结构化插件 API |

**最小适配方案（路径 A 设计预留）**：VS Code 离线版 + 自写 minion-bridge 插件
（Extension API + Node.js net 模块连 127.0.0.1）——JDK8 侧 IdeBridge 用
ServerSocketChannel（仅 127.0.0.1），NDJSON 帧复用 SdkFrameIo 编解码。

## 三、本备档结论

1. `ide.bridge.enabled` 保持 **false**（FeatureFlags 12.3 条目，legacy 默认关）。
2. 路径 A（IdeBridge + 离线插件）**暂缓**——不是"尚未实现"的推托，而是规格
   §14.1 守则第 6 条的执行：外部进程/原生 API 先 Win7 实机 POC 再进主线。
3. 用户取得 POC 第 10 项证据后，按方向稿路径 A 清单实施（预计 2-3 天工作量）：
   - core/bridge/IdeBridge.java（127.0.0.1 绑定 + 随机令牌 + realpath 握手 +
     协议版本协商 + 断线重连不重放）
   - 发现/选区/打开文件/diff/诊断/权限回调六命令
   - /ide 命令 + 离线 VSIX 插件（自写，走 ProjectTrustStore 信任）
4. /ide 命令当前返回"未启用"提示（`ide.bridge.enabled=false`），不空转不误导。

## 四、待用户确认

- [ ] 我已在 Win7 实机完成 POC 第 10 项（候选 IDE + 插件 API 实测），证据在
      `docs/win7-poc/README.md` 第 10 行 → **转路径 A**
- [ ] 我确认当前内网无可用 IDE 插件组合，接受路径 B 豁免（ide.bridge.enabled=false
      为正式环境例外） → **本文件转为正式豁免记录**

用户签字：＿＿＿＿＿＿＿＿　日期：＿＿＿＿＿＿
