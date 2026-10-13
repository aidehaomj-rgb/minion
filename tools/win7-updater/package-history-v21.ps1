$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name = 'minion-win7-history-paging-small-20260930-v21'
$pkg = Join-Path $root "dist/$name-package"
$previous = Join-Path $root 'dist/minion-win7-long-session-ui-recovery-small-20260928-v20-package'
New-Item -ItemType Directory -Force "$pkg/payload" | Out-Null
Copy-Item "$previous/payload/JarPatchApplier.class" "$pkg/payload/"
Copy-Item "$previous/payload/base-jar.sha256" "$pkg/payload/"
$script = [IO.File]::ReadAllText("$previous/Update-Minion-V20.cmd")
$script = $script.Replace('V20','V21').Replace('v20','v21').Replace('Long Session UI Repair','History Paging').Replace('long-session UI repair','history paging update').Replace('This update fixes the UI freeze when opening a long conversation.','This update adds earlier/newer history pages without deleting conversations.')
[IO.File]::WriteAllText("$pkg/Update-Minion-V21.cmd", $script, [Text.Encoding]::ASCII)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$patch = "$pkg/payload/classes-patch.zip"
if (Test-Path $patch) { throw 'Package already exists; use a fresh version.' }
$zip = [IO.Compression.ZipFile]::Open($patch, 'Create')
try {
    foreach ($sub in @('com/minion/gui/chat','com/minion/gui/session')) {
        Get-ChildItem "$root/target/classes/$sub" -Filter '*.class' | Where-Object {
            $_.Name -match '^(ChatView|CollapsibleText|MessageTextArea|EventList)(\$.*)?\.class$'
        } | ForEach-Object {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$_.FullName,"$sub/$($_.Name)") | Out-Null
        }
    }
} finally { $zip.Dispose() }
[IO.File]::WriteAllText("$patch.sha256", (Get-FileHash $patch -Algorithm SHA256).Hash + "`r`n",[Text.Encoding]::ASCII)
[IO.Compression.ZipFile]::CreateFromDirectory($pkg,"$root/dist/$name.zip")
Get-Item "$root/dist/$name.zip" | Select-Object FullName,Length
Get-FileHash "$root/dist/$name.zip" -Algorithm SHA256
