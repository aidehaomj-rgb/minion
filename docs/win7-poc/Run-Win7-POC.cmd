@echo off
chcp 65001 >nul
setlocal
set "TARGET_DIR=%~1"
if "%TARGET_DIR%"=="" set "TARGET_DIR=%~dp0"
if "%TARGET_DIR:~-1%"=="\" set "TARGET_DIR=%TARGET_DIR:~0,-1%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$s=[System.IO.File]::ReadAllText('%~f0',[System.Text.Encoding]::UTF8); $m='#___PS_'+'BEGIN___'; $i=$s.IndexOf($m); $code=$s.Substring($i+$m.Length); $sb=[scriptblock]::Create($code); & $sb '%TARGET_DIR%'"
set "EC=%ERRORLEVEL%"
if /i not "%~2"=="--nopause" pause
exit /b %EC%
#___PS_BEGIN___
param([string]$TargetDir)

$ErrorActionPreference = 'SilentlyContinue'
if (-not $TargetDir -or -not (Test-Path $TargetDir)) {
    $TargetDir = (Get-Location).Path
}
$TargetDir = (Resolve-Path $TargetDir).Path
$ReportPath = Join-Path $TargetDir 'win7-poc-report.txt'

$lines = New-Object System.Collections.Generic.List[string]
$script:PassCount = 0
$script:WarnCount = 0
$script:FailCount = 0

function Write-Line([string]$text) {
    $lines.Add($text)
    Write-Host $text
}
function Add-Pass([string]$name, [string]$detail) {
    $script:PassCount++
    Write-Line ("[PASS] " + $name + " :: " + $detail)
}
function Add-Warn([string]$name, [string]$detail) {
    $script:WarnCount++
    Write-Line ("[WARN] " + $name + " :: " + $detail)
}
function Add-Fail([string]$name, [string]$detail) {
    $script:FailCount++
    Write-Line ("[FAIL] " + $name + " :: " + $detail)
}
function Add-Info([string]$name, [string]$detail) {
    Write-Line ("[INFO] " + $name + " :: " + $detail)
}

function Run-Exe([string]$exe, [string]$arguments, [int]$timeoutMs) {
    try {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $exe
        $psi.Arguments = $arguments
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.CreateNoWindow = $true
        $p = [System.Diagnostics.Process]::Start($psi)
        $out = $p.StandardOutput.ReadToEnd()
        $err = $p.StandardError.ReadToEnd()
        if (-not $p.WaitForExit($timeoutMs)) {
            try { $p.Kill() } catch {}
            return @{ Ok = $false; Code = -1; Text = 'TIMEOUT' }
        }
        $combined = ($out + "`n" + $err).Trim()
        return @{ Ok = ($p.ExitCode -eq 0); Code = $p.ExitCode; Text = $combined }
    } catch {
        return @{ Ok = $false; Code = -2; Text = $_.Exception.Message }
    }
}

function First-Line([string]$s) {
    if (-not $s) { return '' }
    $arr = $s -split "(`r`n|`n|`r)"
    foreach ($x in $arr) {
        if ($x -and $x.Trim().Length -gt 0) { return $x.Trim() }
    }
    return ''
}

function Get-Sha256Hex([string]$filePath) {
    $sha = New-Object System.Security.Cryptography.SHA256Managed
    $fs = [System.IO.File]::OpenRead($filePath)
    try {
        $hash = $sha.ComputeHash($fs)
        return ([System.BitConverter]::ToString($hash)).Replace('-', '').ToLower()
    } finally {
        $fs.Close()
        $sha.Dispose()
    }
}

function Get-PeArch([string]$filePath) {
    try {
        $fs = [System.IO.File]::OpenRead($filePath)
        try {
            $buf = New-Object byte[] 64
            if ($fs.Read($buf, 0, 64) -lt 64) { return 'unknown' }
            $peOffset = [System.BitConverter]::ToInt32($buf, 60)
            $fs.Seek($peOffset + 4, [System.IO.SeekOrigin]::Begin) | Out-Null
            $mb = New-Object byte[] 2
            if ($fs.Read($mb, 0, 2) -lt 2) { return 'unknown' }
            $machine = [System.BitConverter]::ToUInt16($mb, 0)
            if ($machine -eq 0x8664) { return 'x64' }
            if ($machine -eq 0x014c) { return 'x86' }
            return ('0x' + $machine.ToString('X4'))
        } finally {
            $fs.Close()
        }
    } catch {
        return 'unknown'
    }
}

function Test-ComProgId([string]$progId) {
    try {
        $key = [Microsoft.Win32.Registry]::ClassesRoot.OpenSubKey($progId)
        if ($null -ne $key) { $key.Close(); return $true }
    } catch {}
    return $false
}

Write-Line "============================================================"
Write-Line ("Minion Win7 / 内网环境验收报告  时间: " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
Write-Line ("检查目录: " + $TargetDir)
Write-Line "============================================================"
Write-Line ""

# 1. 操作系统与硬件
Write-Line "-- 1. 操作系统、内存与磁盘 --"
try {
    $os = Get-WmiObject Win32_OperatingSystem
    $totalGb = [Math]::Round($os.TotalVisibleMemorySize / 1048576.0, 2)
    $freeGb = [Math]::Round($os.FreePhysicalMemory / 1048576.0, 2)
    Add-Info "操作系统" ($os.Caption + " | " + $os.Version + " | " + $os.OSArchitecture)
    Add-Info "物理内存" ("总计 " + $totalGb + " GB / 当前可用 " + $freeGb + " GB")
} catch {
    Add-Info "操作系统" ([System.Environment]::OSVersion.VersionString)
}
try {
    $cpu = Get-WmiObject Win32_Processor | Select-Object -First 1
    if ($cpu) { Add-Info "处理器" ($cpu.Name.Trim()) }
} catch {}
try {
    $driveLetter = $TargetDir.Substring(0, 2)
    $disk = Get-WmiObject Win32_LogicalDisk -Filter ("DeviceID='" + $driveLetter + "'")
    if ($disk) {
        $freeDiskGb = [Math]::Round($disk.FreeSpace / 1073741824.0, 1)
        Add-Info "磁盘空间" ($driveLetter + " 可用 " + $freeDiskGb + " GB")
    }
} catch {}
$jarPath = Join-Path $TargetDir 'minion-0.1.0.jar'
if (Test-Path $jarPath) {
    $jarMb = [Math]::Round((Get-Item $jarPath).Length / 1048576.0, 2)
    Add-Pass "minion-0.1.0.jar" ("已找到 (" + $jarMb + " MB)")
} else {
    Add-Warn "minion-0.1.0.jar" "当前目录未找到 minion-0.1.0.jar（如脚本不在安装目录，请传入安装目录路径）"
}
Write-Line ""

# 2. Java 8 x64 & JavaFX
Write-Line "-- 2. JDK 8 x64 与 JavaFX --"
$javaCandidates = @(
    (Join-Path $TargetDir 'jre\bin\java.exe'),
    (Join-Path $TargetDir 'jdk\bin\java.exe'),
    (Join-Path $TargetDir 'java\bin\java.exe'),
    $env:MINION_JAVA
)
if ($env:JAVA_HOME) { $javaCandidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
foreach ($pat in @(
    (Join-Path $TargetDir 'runtime\*\bin\java.exe'),
    (Join-Path $TargetDir 'zulu*\bin\java.exe'),
    'C:\Program Files\Java\jdk1.8*\bin\java.exe',
    'C:\Program Files\Zulu\zulu-8*\bin\java.exe'
)) {
    $hit = Get-Item $pat -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($hit) { $javaCandidates += $hit.FullName }
}
$javaExe = $null
foreach ($c in $javaCandidates) {
    if ($c -and (Test-Path $c)) { $javaExe = $c; break }
}
if (-not $javaExe) {
    $cmdJava = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($cmdJava) { $javaExe = $cmdJava.Definition }
}
if ($javaExe) {
    $jv = Run-Exe $javaExe '-version' 8000
    $verLine = First-Line $jv.Text
    $is64 = $jv.Text -match '64-Bit'
    $is8 = $jv.Text -match '1\.8\.0'
    if ($is8 -and $is64) {
        Add-Pass "Java 8 x64" ($javaExe + " | " + $verLine)
    } else {
        Add-Warn "Java 版本/架构" ($javaExe + " | " + $verLine + " (建议 Zulu/Oracle JDK 8 x64)")
    }
    $javaHomeDir = Split-Path (Split-Path $javaExe -Parent) -Parent
    $fxPaths = @(
        (Join-Path $javaHomeDir 'jre\lib\ext\jfxrt.jar'),
        (Join-Path $javaHomeDir 'lib\ext\jfxrt.jar'),
        (Join-Path $javaHomeDir 'lib\jfxrt.jar')
    )
    $fxFound = $null
    foreach ($fp in $fxPaths) { if (Test-Path $fp) { $fxFound = $fp; break } }
    if ($fxFound) {
        Add-Pass "JavaFX (jfxrt.jar)" $fxFound
    } else {
        Add-Fail "JavaFX (jfxrt.jar)" ("未在 " + $javaHomeDir + " 下找到 jfxrt.jar，GUI 无法启动")
    }
} else {
    Add-Fail "Java 8 x64" "未找到 java.exe（请检查 jre 目录或 JAVA_HOME / PATH）"
}
Write-Line ""

# 3. DPAPI 加解密往返
Write-Line "-- 3. Windows DPAPI (SecretStore 安全存储) --"
try {
    Add-Type -AssemblyName System.Security
    $plain = [System.Text.Encoding]::UTF8.GetBytes('minion-win7-poc-dpapi')
    $enc = [System.Security.Cryptography.ProtectedData]::Protect($plain, $null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
    $dec = [System.Security.Cryptography.ProtectedData]::Unprotect($enc, $null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
    if ([System.Text.Encoding]::UTF8.GetString($dec) -eq 'minion-win7-poc-dpapi') {
        Add-Pass "DPAPI" "CryptProtectData / CryptUnprotectData 往返正常"
    } else {
        Add-Fail "DPAPI" "解密校验不一致"
    }
} catch {
    Add-Fail "DPAPI" ("调用失败: " + $_.Exception.Message)
}
Write-Line ""

# 4. ripgrep (rg.exe)
Write-Line "-- 4. ripgrep (rg.exe) --"
$rgCandidates = @(
    (Join-Path $TargetDir '.session\tools\rg.exe'),
    (Join-Path $TargetDir 'ripgrep\rg.exe'),
    (Join-Path $TargetDir 'tools\rg.exe'),
    (Join-Path $TargetDir 'rg.exe')
)
$rgExe = $null
foreach ($c in $rgCandidates) { if (Test-Path $c) { $rgExe = $c; break } }
if (-not $rgExe) {
    $cmdRg = Get-Command rg.exe -ErrorAction SilentlyContinue
    if ($cmdRg) { $rgExe = $cmdRg.Definition }
}
if ($rgExe) {
    $rv = Run-Exe $rgExe '--version' 5000
    $arch = Get-PeArch $rgExe
    $sha = Get-Sha256Hex $rgExe
    if ($rv.Ok) {
        Add-Pass "rg.exe" ((First-Line $rv.Text) + " | arch=" + $arch + " | path=" + $rgExe)
        Add-Info "rg SHA-256" $sha
    } else {
        Add-Fail "rg.exe" ("执行失败 (arch=" + $arch + "): " + (First-Line $rv.Text))
    }
} else {
    Add-Warn "rg.exe" "当前目录未解压出 rg.exe（首次使用 Grep 时会从 JAR 自解压到 .session\tools\rg.exe，不可用时自动回退 Java 搜索）"
}
Write-Line ""

# 5. Git Bash / Git / Python
Write-Line "-- 5. Git Bash / Git / Python --"
$bashCandidates = @(
    'C:\Program Files\Git\bin\bash.exe',
    'C:\Program Files (x86)\Git\bin\bash.exe'
)
$bashExe = $null
$cmdBash = Get-Command bash.exe -ErrorAction SilentlyContinue
if ($cmdBash) { $bashExe = $cmdBash.Definition }
if (-not $bashExe) { foreach ($c in $bashCandidates) { if (Test-Path $c) { $bashExe = $c; break } } }
if ($bashExe) {
    $bv = Run-Exe $bashExe '--version' 5000
    Add-Pass "Git Bash" ($bashExe + " | " + (First-Line $bv.Text))
} else {
    Add-Warn "Git Bash" "未检测到 bash.exe（Bash 工具会回退 PowerShell/CMD）"
}

$gitExe = $null
$cmdGit = Get-Command git.exe -ErrorAction SilentlyContinue
if ($cmdGit) { $gitExe = $cmdGit.Definition }
if (-not $gitExe -and (Test-Path 'C:\Program Files\Git\cmd\git.exe')) { $gitExe = 'C:\Program Files\Git\cmd\git.exe' }
if ($gitExe) {
    $gv = Run-Exe $gitExe '--version' 5000
    Add-Pass "Git" ((First-Line $gv.Text) + " | arch=" + (Get-PeArch $gitExe))
} else {
    Add-Warn "Git" "未检测到 git.exe"
}

$pyExe = $null
$cfgPath = Join-Path $TargetDir 'config.properties'
if (Test-Path $cfgPath) {
    $pyLine = Select-String -Path $cfgPath -Pattern '^\s*python\.path\s*=' | Select-Object -First 1
    if ($pyLine) {
        $pv = ($pyLine.Line -split '=', 2)[1].Trim().Replace('\\', '\')
        if ($pv -and (Test-Path $pv)) { $pyExe = $pv }
    }
}
if (-not $pyExe) {
    foreach ($c in @('C:\ProgramData\Anaconda3\python.exe', 'D:\Anaconda3\python.exe', (Join-Path $env:USERPROFILE 'Anaconda3\python.exe'))) {
        if (Test-Path $c) { $pyExe = $c; break }
    }
}
if (-not $pyExe) {
    $cmdPy = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($cmdPy -and ($cmdPy.Definition -notmatch 'WindowsApps\\python\.exe$')) { $pyExe = $cmdPy.Definition }
}
if ($pyExe) {
    $pyv = Run-Exe $pyExe '--version' 5000
    $sqlTest = Run-Exe $pyExe '-c "import sqlite3; print(sqlite3.sqlite_version)"' 5000
    if ($sqlTest.Ok) {
        Add-Pass "Python + sqlite3" ($pyExe + " | " + (First-Line $pyv.Text) + " | sqlite=" + (First-Line $sqlTest.Text))
    } else {
        Add-Warn "Python + sqlite3" ($pyExe + " | " + (First-Line $pyv.Text) + " | sqlite3 导入失败: " + (First-Line $sqlTest.Text))
    }
} else {
    Add-Warn "Python" "未检测到 Python/Anaconda（不影响基础对话，影响 Excel/Python 分析工具）"
}
Write-Line ""

# 6. Office / WPS / LibreOffice（PPT/DOCX 预览引擎）
Write-Line "-- 6. 文档预览引擎 (LibreOffice / Office / WPS) --"
$loFound = $null
foreach ($c in @('C:\Program Files\LibreOffice\program\soffice.exe', 'C:\Program Files (x86)\LibreOffice\program\soffice.exe')) {
    if (Test-Path $c) { $loFound = $c; break }
}
if ($loFound) { Add-Pass "LibreOffice" $loFound } else { Add-Info "LibreOffice" "未安装" }
$pptCom = Test-ComProgId 'PowerPoint.Application'
$wordCom = Test-ComProgId 'Word.Application'
$wpsPptCom = (Test-ComProgId 'KWPP.Application') -or (Test-ComProgId 'WPP.Application')
$wpsWordCom = (Test-ComProgId 'KWps.Application') -or (Test-ComProgId 'WPS.Application')
Add-Info "Office COM" ("PowerPoint=" + $pptCom + ", Word=" + $wordCom)
Add-Info "WPS COM" ("WPP(PPT)=" + $wpsPptCom + ", WPS(Word)=" + $wpsWordCom)
if ($loFound -or $pptCom -or $wpsPptCom) {
    Add-Pass "PPT 高保真预览引擎" "已具备可用转换引擎（可渲染全页原版 PPT/DOCX）"
} else {
    Add-Warn "PPT 高保真预览引擎" "未检测到 LibreOffice/PowerPoint/WPS（PPTX 将自动走内置离线幻灯片卡片视图）"
}
Write-Line ""

# 7. 候选 IDE 与 LSP 探测（为 §12.3 路径 A/B 拍板提供证据）
Write-Line "-- 7. 内网可用 IDE 与 LSP Server 探测 (§12.3 / §3.1) --"
$ideList = @()
$ideChecks = @(
    @{ Name = 'VS Code'; Paths = @('C:\Program Files\Microsoft VS Code\Code.exe', (Join-Path $env:LOCALAPPDATA 'Programs\Microsoft VS Code\Code.exe')); Cmd = 'code.cmd' },
    @{ Name = 'IntelliJ IDEA'; Paths = @('C:\Program Files\JetBrains\IntelliJ IDEA*\bin\idea64.exe', 'D:\Program Files\JetBrains\IntelliJ IDEA*\bin\idea64.exe'); Cmd = 'idea64.exe' },
    @{ Name = 'Eclipse'; Paths = @('C:\eclipse\eclipse.exe', 'D:\eclipse\eclipse.exe'); Cmd = 'eclipse.exe' },
    @{ Name = 'PyCharm'; Paths = @('C:\Program Files\JetBrains\PyCharm*\bin\pycharm64.exe'); Cmd = 'pycharm64.exe' }
)
foreach ($ide in $ideChecks) {
    $hit = $null
    foreach ($pat in $ide.Paths) {
        $resolved = Get-Item $pat -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($resolved) { $hit = $resolved.FullName; break }
    }
    if (-not $hit) {
        $cmd = Get-Command $ide.Cmd -ErrorAction SilentlyContinue
        if ($cmd) { $hit = $cmd.Definition }
    }
    if ($hit) {
        $ver = ''
        try { $ver = (Get-Item $hit).VersionInfo.ProductVersion } catch {}
        $ideList += ($ide.Name + " (" + $ver + ") -> " + $hit)
    }
}
if ($ideList.Count -gt 0) {
    foreach ($item in $ideList) { Add-Info "检测到 IDE" $item }
} else {
    Add-Info "检测到 IDE" "未在常见路径/PATH 中发现 VS Code / IDEA / Eclipse / PyCharm（如装在其他目录请口头补充）"
}

$lspFound = @()
foreach ($lsp in @('pyright-langserver', 'pylsp', 'jdtls', 'clangd', 'typescript-language-server', 'gopls')) {
    $cmd = Get-Command $lsp -ErrorAction SilentlyContinue
    if ($cmd) { $lspFound += ($lsp + " -> " + $cmd.Definition) }
}
if ($lspFound.Count -gt 0) {
    Add-Info "检测到 LSP" ($lspFound -join '; ')
} else {
    Add-Info "检测到 LSP" "PATH 中未检测到常见 LSP server（保持 lsp.enabled=false 即可）"
}
Write-Line ""

# 8. 内网连通性与断网隔离检查（零密钥输出）
Write-Line "-- 8. 内网 API 连通与外网隔离 --"
$apiUrl = $null
$modelJsonPath = Join-Path $TargetDir 'model.json'
if (Test-Path $modelJsonPath) {
    $mj = [System.IO.File]::ReadAllText($modelJsonPath, [System.Text.Encoding]::UTF8)
    $m = [regex]::Match($mj, '"url"\s*:\s*"([^"]+)"')
    if ($m.Success) { $apiUrl = $m.Groups[1].Value }
}
if (-not $apiUrl -and (Test-Path $cfgPath)) {
    $uLine = Select-String -Path $cfgPath -Pattern '^\s*model\.url\s*=' | Select-Object -First 1
    if ($uLine) { $apiUrl = ($uLine.Line -split '=', 2)[1].Trim() }
}
function Probe-Http([string]$url) {
    try {
        $req = [System.Net.HttpWebRequest]::Create($url)
        $req.Timeout = 4000
        $req.ReadWriteTimeout = 4000
        $req.Method = 'GET'
        try {
            $resp = $req.GetResponse()
            $code = [int]$resp.StatusCode
            $resp.Close()
            return "HTTP $code"
        } catch [System.Net.WebException] {
            if ($_.Exception.Response) {
                $code = [int]$_.Exception.Response.StatusCode
                $_.Exception.Response.Close()
                return "HTTP $code"
            }
            return "UNREACHABLE (" + $_.Exception.Status + ")"
        }
    } catch {
        return "ERROR (" + $_.Exception.Message + ")"
    }
}
if ($apiUrl) {
    $r = Probe-Http $apiUrl
    if ($r.StartsWith('HTTP ')) {
        Add-Pass "内网模型端点" ($apiUrl + " -> " + $r)
    } else {
        Add-Fail "内网模型端点" ($apiUrl + " -> " + $r)
    }
} else {
    Add-Warn "内网模型端点" "未在 model.json 或 config.properties 中找到 url（首次启动后在设置->模型配置即可）"
}
$extProbe = Probe-Http 'https://example.com'
Add-Info "外网隔离探测" ("https://example.com -> " + $extProbe)
Write-Line ""

# 9. Minion CLI 烟测（不弹窗口）
Write-Line "-- 9. Minion CLI 烟测 --"
if ($javaExe -and (Test-Path $jarPath)) {
    $cliRes = Run-Exe $javaExe ('-jar "' + $jarPath + '" doctor') 15000
    if ($cliRes.Ok) {
        Add-Pass "CLI doctor" (First-Line $cliRes.Text)
    } else {
        Add-Warn "CLI doctor" ("exit=" + $cliRes.Code + " | " + (First-Line $cliRes.Text))
    }
} else {
    Add-Warn "CLI doctor" "跳过（缺少 java.exe 或 minion-0.1.0.jar）"
}
Write-Line ""

# 10. 汇总与人工检查提示
Write-Line "============================================================"
Write-Line ("汇总: PASS=" + $script:PassCount + "  WARN=" + $script:WarnCount + "  FAIL=" + $script:FailCount)
Write-Line "============================================================"
Write-Line ""
Write-Line "【请打开 Minion GUI 顺手确认以下 3 项，把结果一起告诉我】"
Write-Line "  A. 在聊天框输入 /doctor，把输出的文本复制给我（确认 JNA/DPAPI 行）"
Write-Line "  B. 发一句「用 Bash 执行 echo ok」，确认弹窗确认正常、模型无 400 报错"
Write-Line "  C. 打开一份 PPTX 和多页 PDF，确认右侧能正常翻页预览；顺手看托盘通知是否正常"

[System.IO.File]::WriteAllLines($ReportPath, $lines.ToArray(), (New-Object System.Text.UTF8Encoding($false)))
exit $script:FailCount
