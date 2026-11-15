$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name='minion-win7-v33-cumulative-20261015'
$package=Join-Path $root "dist/$name-package"
$archive=Join-Path $root "dist/$name.zip"
$jar=Join-Path $root 'target/minion-0.1.0.jar'
if((Test-Path -LiteralPath $package)-or(Test-Path -LiteralPath $archive)){throw 'V33 package already exists; refusing to overwrite'}
if(-not(Test-Path -LiteralPath $jar)){throw 'Build target JAR first'}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($jar)
try {
    $manifest=$zip.GetEntry('META-INF/MANIFEST.MF')
    if($null -eq $manifest){throw 'Missing Minion manifest'}
    $reader=New-Object IO.StreamReader($manifest.Open())
    try {$manifestText=$reader.ReadToEnd()} finally {$reader.Dispose()}
    if($manifestText -notmatch 'Main-Class: com\.minion\.Boot'){throw 'Wrong JAR entry point'}
    foreach($required in @('com/minion/gui/ReliabilityPanel.class','com/minion/core/agent/LocalJobs.class','com/minion/core/agent/TaskEvaluation.class','com/minion/core/tools/data/PdfViews.class','workflows/office_workflow.py','theme/theme.css')) {
        if($null -eq $zip.GetEntry($required)){throw "Missing payload: $required"}
    }
    foreach($entry in $zip.Entries) {
        if(-not($entry.FullName.StartsWith('com/minion/') -and $entry.FullName.EndsWith('.class'))){continue}
        $stream=$entry.Open();$bytes=New-Object byte[] 8
        try {$n=$stream.Read($bytes,0,8)} finally {$stream.Dispose()}
        if($n -ne 8 -or $bytes[0] -ne 202 -or $bytes[1] -ne 254 -or $bytes[2] -ne 186 -or $bytes[3] -ne 190 -or ($bytes[6]*256+$bytes[7]) -gt 52){throw "Not a Java 8 class: $($entry.FullName)"}
    }
} finally {$zip.Dispose()}

New-Item -ItemType Directory -Path (Join-Path $package 'payload') | Out-Null
Copy-Item -LiteralPath $jar -Destination (Join-Path $package 'payload/minion-0.1.0.jar')
Copy-Item -LiteralPath (Join-Path $root 'tools/win7-updater/Update-Minion-V33.cmd') -Destination $package
Copy-Item -LiteralPath (Join-Path $root 'docs/releases/V32-数据材料与视觉文档更新.md') -Destination (Join-Path $package 'V32-说明.md')
Copy-Item -LiteralPath (Join-Path $root 'docs/releases/V33-累计可靠执行更新.md') -Destination (Join-Path $package 'V33-安装说明.md')
Copy-Item -LiteralPath (Join-Path $root 'docs/releases/可靠执行与待引导编辑-开发说明.md') -Destination (Join-Path $package '可靠执行功能说明.md')
$payload=Join-Path $package 'payload/minion-0.1.0.jar'
[IO.File]::WriteAllText((Join-Path $package 'payload/minion-0.1.0.jar.sha256'),(Get-FileHash -LiteralPath $payload -Algorithm SHA256).Hash+"`r`n",[Text.Encoding]::ASCII)
[IO.Compression.ZipFile]::CreateFromDirectory($package,$archive)
Get-Item -LiteralPath $archive | Select-Object FullName,Length
Get-FileHash -LiteralPath $archive -Algorithm SHA256
