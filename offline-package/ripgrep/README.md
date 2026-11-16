# ripgrep 13.0.0（Windows x64，Win7 兼容）

Grep/Glob 的可选提速引擎。打包时把 `rg.exe` 复制到 jar 同目录（或 jar 同目录 `tools/` 子目录），
minion 启动搜索时自动探测启用；探测不到则走 Java 遍历回退，功能不变。

- 版本固定 **13.0.0**：ripgrep 14.x 起要求 Windows 10，本项目目标环境含 Win7，不得升级。
- 来源：https://github.com/BurntSushi/ripgrep/releases/tag/13.0.0
  资产 `ripgrep-13.0.0-x86_64-pc-windows-msvc.zip`（2026-11-16 经 gh-proxy.com 镜像下载，
  GitHub 直连在内网不可达）。
- zip SHA256：`a47ace6f654c5ffa236792fc3ee3fefd9c7e88e026928b44da801acb72124aa8`
- 二进制自报版本：`ripgrep 13.0.0 (rev af6b6c543b)`，与官方 13.0.0 标签提交一致。
- 许可：MIT / Unlicense 双许可（见 LICENSE-MIT、UNLICENSE、COPYING），可自由随包分发。
- 32 位 Windows 无对应 exe 时自动走 Java 回退，不影响使用。