$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name = 'minion-win7-context-memory-small-20261008-v22'
$pkg = Join-Path $root "dist/$name-package"
$previous = Join-Path $root 'dist/minion-win7-history-paging-small-20260930-v21-package'
if (Test-Path $pkg) { throw 'Package already exists; use a fresh version.' }
New-Item -ItemType Directory -Force "$pkg/payload" | Out-Null
Copy-Item "$previous/payload/JarPatchApplier.class" "$pkg/payload/"
Copy-Item "$previous/payload/base-jar.sha256" "$pkg/payload/"

$script = [IO.File]::ReadAllText("$previous/Update-Minion-V21.cmd")
$script = $script.Replace('V21','V22').Replace('v21','v22')
$script = $script.Replace('History Paging','Context Memory Repair')
$script = $script.Replace('history paging update','context memory repair update')
$script = $script.Replace(
    'This update adds earlier/newer history pages without deleting conversations.',
    'This update keeps the active task across compact and reduces old context payloads.')
$script = $script.Replace(
    'Complete conversation history and model context remain unchanged.',
    'Qwen3.8 sessions use a safer adaptive context window; local history stays complete.')
$script = $script.Replace(
    'Long-session UI repair completed successfully.',
    'Context memory repair completed successfully.')
[IO.File]::WriteAllText("$pkg/Update-Minion-V22.cmd", $script, [Text.Encoding]::ASCII)

Add-Type -AssemblyName System.IO.Compression.FileSystem
$patch = "$pkg/payload/classes-patch.zip"
$zip = [IO.Compression.ZipFile]::Open($patch, 'Create')
try {
    $groups = @(
        @{ Dir='com/minion/core/context'; Pattern='^ContextManager(\$.*)?\.class$' },
        @{ Dir='com/minion/core/agent'; Pattern='^(AgentLoop|Session)(\$.*)?\.class$' },
        @{ Dir='com/minion/gui/session'; Pattern='^SessionManager(\$.*)?\.class$' }
    )
    foreach ($group in $groups) {
        Get-ChildItem "$root/target/classes/$($group.Dir)" -Filter '*.class' | Where-Object {
            $_.Name -match $group.Pattern
        } | ForEach-Object {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $zip, $_.FullName, "$($group.Dir)/$($_.Name)") | Out-Null
        }
    }
} finally { $zip.Dispose() }

[IO.File]::WriteAllText("$patch.sha256",
    (Get-FileHash $patch -Algorithm SHA256).Hash + "`r`n", [Text.Encoding]::ASCII)
$out = "$root/dist/$name.zip"
[IO.Compression.ZipFile]::CreateFromDirectory($pkg, $out)
Get-Item $out | Select-Object FullName,Length
Get-FileHash $out -Algorithm SHA256
