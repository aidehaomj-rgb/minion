$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name = 'minion-win7-completion-all-small-20261012-v29'
$pkg = Join-Path $root "dist/$name-package"
$previous = Join-Path $root 'dist/minion-win7-post-v27-all-small-20261011-v28-package'
if (Test-Path -LiteralPath $pkg) { throw 'Package already exists; use a fresh version.' }
New-Item -ItemType Directory -Path "$pkg/payload" | Out-Null
$jdk = (Resolve-Path "$root/target/build-tools-v21/jdk/jdk8u462-full").Path
& "$jdk/bin/javac.exe" -encoding UTF-8 -d "$pkg/payload" "$PSScriptRoot/JarPatchApplier.java"
if ($LASTEXITCODE -ne 0) { throw 'Patcher compilation failed.' }
Copy-Item "$previous/payload/base-jar.sha256" "$pkg/payload/"
Copy-Item "$previous/V28-说明.md" "$pkg/"
Copy-Item "$root/docs/releases/V29-完成收尾与时间更新.md" "$pkg/V29-说明.md"
$script = [IO.File]::ReadAllText("$previous/Update-Minion-V28.cmd")
$script = $script.Replace('V28','V29').Replace('v28','v29')
$script = $script.Replace('Post V27 Combined Update','Completion Combined Update')
$script = $script.Replace('post V27 combined update','completion combined update')
$script = $script.Replace('Post V27 combined update','Completion combined update')
$script = $script.Replace('Includes post-V27 streaming recovery, default model, history and selective upstream fixes.',
    'Includes V28 plus completion checkpoint recovery and real completion timestamps.')
$script = $script.Replace('Upgrade from V27; preserves model settings, context thresholds, projects and history.',
    'Upgrade from V27 or V28; preserves settings, context thresholds, projects and history.')
[IO.File]::WriteAllText("$pkg/Update-Minion-V29.cmd",$script,[Text.Encoding]::ASCII)
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
