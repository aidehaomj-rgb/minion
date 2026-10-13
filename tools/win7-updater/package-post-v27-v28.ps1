$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name = 'minion-win7-post-v27-all-small-20261011-v28'
$pkg = Join-Path $root "dist/$name-package"
$previous = Join-Path $root 'dist/minion-win7-action-recovery-all-small-20261011-v27-package'
if (Test-Path -LiteralPath $pkg) { throw 'Package already exists; use a fresh version.' }
New-Item -ItemType Directory -Path "$pkg/payload" | Out-Null
$jdk = (Resolve-Path "$root/target/build-tools-v21/jdk/jdk8u462-full").Path
& "$jdk/bin/javac.exe" -encoding UTF-8 -d "$pkg/payload" "$PSScriptRoot/JarPatchApplier.java"
if ($LASTEXITCODE -ne 0) { throw 'Patcher compilation failed.' }
Copy-Item "$previous/payload/base-jar.sha256" "$pkg/payload/"
Copy-Item "$root/docs/releases/V28-V27后合并更新.md" "$pkg/V28-说明.md"
$script = [IO.File]::ReadAllText("$previous/Update-Minion-V27.cmd")
$script = $script.Replace('V27','V28').Replace('v27','v28')
$script = $script.Replace('Action Recovery Combined Update','Post V27 Combined Update')
$script = $script.Replace('action recovery combined update','post V27 combined update')
$script = $script.Replace('Action recovery combined update','Post V27 combined update')
$script = $script.Replace('Combined V23 + V24 + V25 + V26 + V28. Upgrade directly from V22-V26.',
    'Includes post-V27 streaming recovery, default model, history and selective upstream fixes.')
$script = $script.Replace('Fix repeat Enter stopping tasks and recover repeated tool plans; preserves settings and history.',
    'Upgrade from V27; preserves model settings, context thresholds, projects and history.')
[IO.File]::WriteAllText("$pkg/Update-Minion-V28.cmd",$script,[Text.Encoding]::ASCII)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$patch = "$pkg/payload/classes-patch.zip"
$zip = [IO.Compression.ZipFile]::Open($patch,'Create')
$count=0
try {
    $classesRoot=(Resolve-Path "$root/target/classes").Path
    foreach($file in (Get-ChildItem "$classesRoot/com/minion" -Recurse -Filter '*.class')) {
        $bytes=[IO.File]::ReadAllBytes($file.FullName)
        if($bytes[6]*256+$bytes[7] -gt 52) {throw "Not Java 8 compatible: $($file.Name)"}
        $entry=$file.FullName.Substring($classesRoot.Length+1).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file.FullName,$entry)|Out-Null
        $count++
    }
    if($count -lt 300) {throw "Application class set incomplete: $count"}
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$classesRoot/theme/theme.css",'theme/theme.css')|Out-Null
} finally {$zip.Dispose()}
[IO.File]::WriteAllText("$patch.sha256",(Get-FileHash $patch -Algorithm SHA256).Hash+"`r`n",[Text.Encoding]::ASCII)
$out="$root/dist/$name.zip"
[IO.Compression.ZipFile]::CreateFromDirectory($pkg,$out)
"Shipped application classes: $count"
Get-Item $out | Format-List FullName,Length
Get-FileHash $out -Algorithm SHA256 | Format-List Hash
