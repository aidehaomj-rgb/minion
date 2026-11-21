#!/usr/bin/env bash
# Win7 SP1 x64 环境 POC 自动取证脚本（Git Bash 下运行）
# 用法: bash win7-poc.sh <minion发布目录>
# 输出: 逐项 PASS/FAIL/WARN + 汇总；手工项提示见 README.md
set -u

DIR="${1:-.}"
PASS=0; FAIL=0; WARN=0

item() { # item <名称> <命令...>  —— 期望退出码 0
  local name="$1"; shift
  local out
  if out=$("$@" 2>&1); then
    echo "PASS  $name :: $(echo "$out" | head -1)"
    PASS=$((PASS+1))
  else
    echo "FAIL  $name :: $(echo "$out" | head -1)"
    FAIL=$((FAIL+1))
  fi
}

warn_item() {
  local name="$1"; shift
  local out
  out=$("$@" 2>&1) || true
  echo "WARN  $name :: $(echo "$out" | head -1)"
  WARN=$((WARN+1))
}

echo "== Minion Win7 SP1 x64 POC $(date) =="
echo "目录: $DIR"
echo

# 1. OS / JDK 架构
echo "-- 1. 操作系统与 JDK --"
echo "INFO  os.name=$(/usr/bin/env cmd //c ver 2>/dev/null | tr -d '\r' | head -1)"
item "java -version (须 64-bit 1.8)" "$DIR/jre/bin/java" -version 2>/dev/null || \
  item "java -version (PATH)" java -version
echo "INFO  os.arch=$(java -XshowSettings:properties -version 2>&1 | grep os.arch | head -1)"

# 2. JavaFX（Zulu FX 版 jre/lib/ext/jfxrt.jar 或 lib/jfxrt.jar）
echo
echo "-- 2. JavaFX --"
for p in "$DIR/jre/lib/ext/jfxrt.jar" "$DIR/jre/lib/jfxrt.jar" "$DIR/lib/jfxrt.jar"; do
  if [ -f "$p" ]; then echo "PASS  JavaFX :: $p"; PASS=$((PASS+1)); found_fx=1; break; fi
done
[ "${found_fx:-0}" = "1" ] || { echo "FAIL  JavaFX :: 未找到 jfxrt.jar"; FAIL=$((FAIL+1)); }

# 3. DPAPI（PowerShell CryptProtectData 初判；minion /doctor 为准）
echo
echo "-- 3. DPAPI --"
if powershell -NoProfile -Command "[System.Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes('poc'),$null,'CurrentUser').Length -gt 0" >/dev/null 2>&1; then
  echo "PASS  DPAPI :: CryptProtectData 可用"; PASS=$((PASS+1))
else
  echo "FAIL  DPAPI :: PowerShell 加密失败"; FAIL=$((FAIL+1))
fi

# 4. rg.exe：版本 + PE 架构 + SHA-256
echo
echo "-- 4. ripgrep --"
RG="$DIR/ripgrep/rg.exe"
if [ -f "$RG" ]; then
  item "rg --version" "$RG" --version
  SHA=$(sha256sum "$RG" | cut -d' ' -f1)
  echo "INFO  rg SHA-256=$SHA"
  # PE Machine: 偏移 0x3C 读 e_lfanew，+4 读 Machine（0x8664=x64）
  MACHINE=$(od -An -tu2 -j60 -N2 "$RG" | tr -d ' ')
  echo "INFO  e_lfanew=$MACHINE"
  PE_OFF=$((MACHINE))
  M=$(od -An -tu2 -j$((PE_OFF+4)) -N2 "$RG" | tr -d ' ')
  if [ "$M" = "34404" ]; then echo "PASS  rg 架构 :: x64 (machine=0x8664)"; PASS=$((PASS+1))
  elif [ "$M" = "332" ]; then echo "WARN  rg 架构 :: x86 (目标环境建议 x64)"; WARN=$((WARN+1))
  else echo "WARN  rg 架构 :: machine=$M 未知"; WARN=$((WARN+1)); fi
else
  echo "WARN  ripgrep :: 未随包（Grep 回退 Java 实现，性能受限）"; WARN=$((WARN+1))
fi

# 5. Git Bash / Git
echo
echo "-- 5. Git Bash / Git --"
item "bash --version" bash --version
item "git --version" git --version

# 6. 磁盘与内存
echo
echo "-- 6. 磁盘/内存 --"
df -h "$DIR" | tail -1 | awk '{print "INFO  磁盘可用: "$4}'
powershell -NoProfile -Command "[Math]::Round((Get-CimInstance Win32_OperatingSystem).TotalVisibleMemorySize/1MB,1)" 2>/dev/null \
  | tr -d '\r' | sed 's/^/INFO  物理内存 GB: /' || true

# 7. 内网 API 连通（config.properties 的 model.url）
echo
echo "-- 7. API 端点 --"
URL=$(grep -E '^model\.url=' "$DIR/config.properties" 2>/dev/null | cut -d= -f2- | head -1)
if [ -n "$URL" ]; then
  if curl -s -o /dev/null -m 5 -w '%{http_code}' "$URL" | grep -qE '^[0-9]'; then
    echo "PASS  API 连通 :: $URL → $(curl -s -o /dev/null -m 5 -w '%{http_code}' "$URL")"
    PASS=$((PASS+1))
  else
    echo "FAIL  API 连通 :: $URL 不可达"; FAIL=$((FAIL+1))
  fi
else
  echo "WARN  API 端点 :: config.properties 未配置 model.url"; WARN=$((WARN+1))
fi

echo
echo "== 汇总: PASS=$PASS FAIL=$FAIL WARN=$WARN =="
echo "手工项（README 清单 6/7/9/10/11/12）：SystemTray、LSP server、WebP、候选 IDE、并发压测、断网防逃逸"
[ "$FAIL" = "0" ]
