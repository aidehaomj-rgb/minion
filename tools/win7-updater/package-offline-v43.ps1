param([switch]$PrepareOnly)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name='Minion-Win7-x64-offline-V43-20261103'
$stage=Join-Path $root 'target/v43-offline-stage/Minion-Win7-x64-offline-V43'
$jar=Join-Path $root 'target/minion-0.1.0.jar'
$archive=Join-Path $root ('dist/'+$name+'.zip')
$utf8=New-Object Text.UTF8Encoding($true)
$ascii=[Text.Encoding]::ASCII
if(Test-Path -LiteralPath $archive){throw 'Delivered archive already exists; do not overwrite.'}
foreach($file in @($jar,(Join-Path $stage 'jdk8/bin/java.exe'),(Join-Path $stage 'python/python.exe'))){
    if(-not (Test-Path -LiteralPath $file)){throw ('Missing package input: '+$file)}
}
function Write-Utf8([string]$file,[string]$body){[IO.File]::WriteAllText($file,$body,$utf8)}
function Write-Ascii([string]$file,[string]$body){[IO.File]::WriteAllText($file,$body,$ascii)}
Copy-Item -LiteralPath $jar -Destination (Join-Path $stage 'minion-0.1.0.jar') -Force
foreach($dir in @('diagnostics','validation','source','licenses','work')){
    New-Item -ItemType Directory -Force -Path (Join-Path $stage $dir)|Out-Null
}
foreach($file in @('Import-Old-Settings.cmd','Import-Old-Settings.ps1')){
    $body=[IO.File]::ReadAllText((Join-Path $PSScriptRoot $file)) -replace "`r?`n","`r`n"
    if($file.EndsWith('.cmd')){Write-Ascii (Join-Path $stage $file) $body}
    else {Write-Utf8 (Join-Path $stage $file) $body}
}
# Versioned source snapshot, not a copy of the development workspace or its private data.
foreach($dir in @('src','docs')){
    $source=Join-Path $root $dir
    foreach($file in Get-ChildItem -LiteralPath $source -Recurse -File){
        $rel=$file.FullName.Substring($root.Length+1)
        $dest=Join-Path (Join-Path $stage 'source') $rel
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($dest))|Out-Null
        Copy-Item -LiteralPath $file.FullName -Destination $dest -Force
    }
}
foreach($file in @('pom.xml','README.md','CLAUDE.md')){
    Copy-Item -LiteralPath (Join-Path $root $file) -Destination (Join-Path $stage 'source') -Force
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'PackageSmokeCheck.java') -Destination (Join-Path $stage 'diagnostics') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'SettingsMigration.java') -Destination (Join-Path $stage 'diagnostics') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'check_environment_v43.py') -Destination (Join-Path $stage 'check_environment.py') -Force
$runtimeJar=Join-Path $stage 'minion-0.1.0.jar'
$java=Join-Path $stage 'jdk8/bin/java.exe'
$javac=Join-Path $stage 'jdk8/bin/javac.exe'
& $javac -encoding UTF-8 -cp $runtimeJar -d (Join-Path $stage 'diagnostics') (Join-Path $stage 'diagnostics/PackageSmokeCheck.java') (Join-Path $stage 'diagnostics/SettingsMigration.java')
if($LASTEXITCODE -ne 0){throw 'Package smoke checker compilation failed.'}
$helpers=Join-Path $root 'target/v43-manifest-helper'
New-Item -ItemType Directory -Force -Path $helpers|Out-Null
& $javac -encoding UTF-8 -d $helpers (Join-Path $PSScriptRoot 'JarEntryVerifier.java')
if($LASTEXITCODE -ne 0){throw 'Manifest helper compilation failed.'}
& $java -cp $helpers JarEntryVerifier write $runtimeJar (Join-Path $stage 'validation/v43-entries.tsv')
if($LASTEXITCODE -ne 0){throw 'V43 manifest generation failed.'}
$jarHash=(Get-FileHash -LiteralPath $runtimeJar -Algorithm SHA256).Hash
Write-Ascii (Join-Path $stage 'minion-0.1.0.jar.sha256') ($jarHash+"`r`n")
$info=[ordered]@{release='V43';date='2026-11-03';applicationVersion='0.1.0';featureBaseline='V42';
    compatibilityBaseline='2026-09-28 R2';java='Zulu 8u222 FX x64';python='CPython 3.7.9 x64';
    bundledPythonPackages=40;personalContactsRemoved=$true;jarSHA256=$jarHash}
Write-Utf8 (Join-Path $stage 'build-info.json') ($info|ConvertTo-Json)
Write-Utf8 (Join-Path $stage 'REVISION.txt') "Minion V43 complete offline bundle, 2026-11-03`r`nV42 features + preserved R2 Windows compatibility fixes.`r`nApplication JAR SHA256: $jarHash`r`n"
$readme=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'README-新用户安装说明.txt'))
Write-Utf8 (Join-Path $stage 'README-先读我.txt') $readme
Write-Utf8 (Join-Path $stage 'THIRD-PARTY-NOTICES.txt') @'
This bundle includes third-party Java/Python libraries and runtimes.
Their original license/copyright files remain inside jdk8, python and the application JAR.
The source/pom.xml and requirements-locked.txt identify Java and Python dependency versions.
JNA 5.13.0 (Windows process handle compatibility) is included in the application JAR.
Removal of the application contact section does not remove third-party license obligations.
'@
if($PrepareOnly){Write-Output ('Prepared stage: '+$stage);exit 0}
foreach($name in @('model.json','workspace.json','config.properties','mcp.json','tools.json','session','.git','.minion')){
    if(Test-Path -LiteralPath (Join-Path $stage $name)){throw ('Unexpected user/runtime data at package root: '+$name)}
}
foreach($file in Get-ChildItem -LiteralPath (Join-Path $stage 'source/src') -Recurse -File){
    if($file.Extension -in @('.java','.css','.properties','.py')){
        $text=[IO.File]::ReadAllText($file.FullName)
        if($text.Contains('258915527') -or $text.Contains('联系方式：')){throw ('Application contact leaked: '+$file.FullName)}
    }
}
if(-not (Test-Path -LiteralPath (Join-Path $stage 'validation/本次验证报告.txt'))){throw 'Current validation report missing.'}
# Build hashes once during packaging; never scan these on every application start.
$hashes=New-Object 'System.Collections.Generic.List[string]'
foreach($file in Get-ChildItem -LiteralPath $stage -Recurse -File | Sort-Object FullName){
    if($file.Name -eq 'SHA256SUMS.txt'){continue}
    $relative=$file.FullName.Substring($stage.Length+1).Replace('\','/')
    $hashes.Add((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash+'  '+$relative)
}
Write-Utf8 (Join-Path $stage 'SHA256SUMS.txt') ([string]::Join("`r`n",$hashes)+"`r`n")
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$zip=[IO.Compression.ZipFile]::Open($archive,[IO.Compression.ZipArchiveMode]::Create)
try {
    $parent=(Split-Path -Parent $stage)
    foreach($file in Get-ChildItem -LiteralPath $stage -Recurse -File){
        $entry=$file.FullName.Substring($parent.Length+1).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file.FullName,$entry,[IO.Compression.CompressionLevel]::Optimal)|Out-Null
    }
    $zip.CreateEntry('Minion-Win7-x64-offline-V43/work/')|Out-Null
} finally {$zip.Dispose()}
$hash=(Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash
Write-Ascii ($archive+'.sha256') ($hash+"`r`n")
Write-Output ('PACKAGE='+$archive)
Write-Output ('SHA256='+$hash)
Write-Output ('FILES='+$hashes.Count)
