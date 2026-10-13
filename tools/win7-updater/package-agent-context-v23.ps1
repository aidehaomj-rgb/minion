$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name = 'minion-win7-agent-context-queue-small-20261010-v23'
$pkg = Join-Path $root "dist/$name-package"
$previous = Join-Path $root 'dist/minion-win7-context-memory-small-20261008-v22-package'
if (Test-Path $pkg) { throw 'Package already exists; use a fresh version.' }
New-Item -ItemType Directory -Force "$pkg/payload" | Out-Null
Copy-Item "$previous/payload/JarPatchApplier.class" "$pkg/payload/"
Copy-Item "$previous/payload/base-jar.sha256" "$pkg/payload/"
Copy-Item "$root/docs/releases/V23-任务队列与多Agent压缩.md" "$pkg/V23-说明.md"
$script = [IO.File]::ReadAllText("$previous/Update-Minion-V22.cmd")
$script = $script.Replace('V22','V23').Replace('v22','v23')
$script = $script.Replace('Context Memory Repair','Agent Context and Queue Repair')
$script = $script.Replace('context memory repair','agent context and queue repair')
$script = $script.Replace(
    'This update keeps the active task across compact and reduces old context payloads.',
    'Requires V22. This update adds durable task state, isolated child context and queued follow-ups.')
$script = $script.Replace(
    'Qwen3.8 sessions use a safer adaptive context window; local history stays complete.',
    'Model settings, knowledge bases and project data are not replaced.')
$script = $script.Replace('Context memory repair completed successfully.',
    'Agent context and queue repair completed successfully.')
[IO.File]::WriteAllText("$pkg/Update-Minion-V23.cmd", $script, [Text.Encoding]::ASCII)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$patch = "$pkg/payload/classes-patch.zip"
$zip = [IO.Compression.ZipFile]::Open($patch, 'Create')
try {
    $groups = @(
        @{ Dir='com/minion/core/context'; Pattern='^ContextManager(\$.*)?\.class$' },
        @{ Dir='com/minion/core/agent'; Pattern='^(AgentLoop|Session|SubAgentLoop|TaskState|ToolOutputStore|TodoList)(\$.*)?\.class$' },
        @{ Dir='com/minion/core/storage'; Pattern='^SessionStore(\$.*)?\.class$' },
        @{ Dir='com/minion/gui'; Pattern='^MainWindow(\$.*)?\.class$' },
        @{ Dir='com/minion/gui/session'; Pattern='^(SessionHandle|SessionManager)(\$.*)?\.class$' },
        @{ Dir='com/minion/gui/input'; Pattern='^InputView(\$.*)?\.class$' },
        @{ Dir='com/minion/gui/command'; Pattern='^CommandDispatcher(\$.*)?\.class$' }
    )
    foreach ($group in $groups) {
        $files = @(Get-ChildItem "$root/target/classes/$($group.Dir)" -Filter '*.class' | Where-Object { $_.Name -match $group.Pattern })
        if ($files.Count -eq 0) { throw "Missing class family: $($group.Dir) $($group.Pattern)" }
        foreach ($file in $files) {
            $bytes=[IO.File]::ReadAllBytes($file.FullName)
            $major=$bytes[6]*256+$bytes[7]
            if ($major -gt 52) { throw "Not Java 8 compatible: $($file.Name) major=$major" }
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file.FullName,"$($group.Dir)/$($file.Name)") | Out-Null
        }
    }
} finally { $zip.Dispose() }
[IO.File]::WriteAllText("$patch.sha256",(Get-FileHash $patch -Algorithm SHA256).Hash + "`r`n",[Text.Encoding]::ASCII)
$out = "$root/dist/$name.zip"
[IO.Compression.ZipFile]::CreateFromDirectory($pkg,$out)
Get-Item $out | Select-Object FullName,Length
Get-FileHash $out -Algorithm SHA256
