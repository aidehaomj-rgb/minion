# 安全修复例外迁移说明（v2.4 §0.3 / §15）

> 本文件随切片 26-40（v2.4 复刻）同步。7 个默认开的安全修复例外在 legacy 与
> replica-offline 两种形态下都生效——这是唯一允许偏离"V68 非回归默认关"原则的类别。
> 回退方法：config.properties 逐键置 false/旧值即恢复 V68 原行为（仅应急排障）。

| # | 键 | 默认 | 修复内容 | false 回退行为 | 切片 |
|---|---|---|---|---|---|
| 1 | `permission.engine.v2` | **true** | PermissionEngine 13 步统一裁决（deny 前置、内容级 deny 免疫 bypass、模式映射） | 旧 ConfirmGate 白名单路径 | 早期 |
| 2 | `bash.analyzer.enabled` | **true** | Bash/PowerShell/CMD 三 Shell 分析器（too-complex/parse-failed→高危） | DangerousCommands 黑名单 | 早期 |
| 3 | `mcp.confirm.skip` | **false** | MCP 工具默认高危确认（`mcp:<server>` 或单工具白名单放行） | MCP 永不确认（应急） | 早期 |
| 4 | `secret.store.v2` | **true** | SecretStore 加固：随机主密钥+DPAPI/PBKDF2+AES/GCM | 可预测密钥 CBC | 早期 |
| 5 | `ssh.strictHostKey` | **true** | SSH TOFU 首次指纹询问+变化按中间人拒绝 | StrictHostKeyChecking=no | 早期 |
| 6 | Hook 超时（`hooks.timeoutBehavior`） | **ask** | 超时/畸形 fail-ask（headless 转 deny），无 continue 放行 | —（Hook 系整体默认关） | 早期 |
| 7 | `trust.store.v2` | **true** | §10.9 ProjectTrustStore 持续信任：Hook/MCP stdio/LSP/statusLine/Skill 内联 shell 启动命令按指纹持久批准；git pull/外部编辑变化即失效；拒绝=零子进程 | 无持续复核（2026-11-22 用户裁决：第 7 个例外） | 26 |

## 迁移注意

1. **升级路径**：从 V68 升级后首次运行——已配置的 MCP stdio 服务器与新 Hook 配置需要
   逐项批准一次（GUI 弹窗/SessionHooksPane 批准），指纹不变则跨会话免提示。
2. **应急排障**：上述键逐项 false/旧值可完整回退 V68 行为；不建议长期停留。
3. **replica-offline**：`src/resource/replica-offline.properties` 在 ★ 段显式列出全部
   7 项供核对（两种形态行为一致）。
4. **权限规则落盘**（§10.3）：确认弹窗"允许并写规则"按作用域写
   `{workspace}/.minion/settings.json` permissions.allow（RuleWriter 原子写）；
   `permissions.managedOnly` 策略层只读不可写。
