$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name = 'minion-win7-v35-small-from-v34-20261018'
$package = Join-Path $root "dist/$name-package"
$archive = Join-Path $root "dist/$name.zip"
$base = Join-Path $root 'dist/minion-win7-v34-cumulative-20261015-package/payload/minion-0.1.0.jar'
$target = Join-Path $root 'target/minion-0.1.0.jar'
$jdk = Join-Path $root 'target/build-tools-v21/jdk/jdk8u462-full'
if ((Test-Path -LiteralPath $package) -or (Test-Path -LiteralPath $archive)) {
    throw 'Small update package already exists. Choose a new package name instead of overwriting it.'
}
foreach ($required in @($base, $target, "$jdk/bin/javac.exe", "$jdk/bin/java.exe")) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Required input missing: $required" }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-EntryHash([System.IO.Compression.ZipArchiveEntry]$entry) {
    $stream = $entry.Open()
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try { return [Convert]::ToBase64String($algorithm.ComputeHash($stream)) }
    finally { $algorithm.Dispose(); $stream.Dispose() }
}

$baseZip = [System.IO.Compression.ZipFile]::OpenRead($base)
$targetZip = [System.IO.Compression.ZipFile]::OpenRead($target)
$changed = New-Object 'System.Collections.Generic.List[System.IO.Compression.ZipArchiveEntry]'
try {
    $baseEntries = New-Object 'System.Collections.Generic.Dictionary[string,System.IO.Compression.ZipArchiveEntry]' ([System.StringComparer]::Ordinal)
    $targetEntries = New-Object 'System.Collections.Generic.Dictionary[string,System.IO.Compression.ZipArchiveEntry]' ([System.StringComparer]::Ordinal)
    foreach ($entry in $baseZip.Entries) { $baseEntries[$entry.FullName] = $entry }
    foreach ($entry in $targetZip.Entries) { $targetEntries[$entry.FullName] = $entry }
    foreach ($nameInBase in $baseEntries.Keys) {
        if (-not $targetEntries.ContainsKey($nameInBase)) {
            throw "Small patch cannot remove JAR entry: $nameInBase"
        }
    }
    foreach ($entry in $targetZip.Entries) {
        if ($entry.FullName.EndsWith('/')) { continue }
        $old = $baseEntries[$entry.FullName]
        if (($null -eq $old) -or ($old.Length -ne $entry.Length) -or
                ((Get-EntryHash $old) -cne (Get-EntryHash $entry))) {
            if (-not $entry.FullName.EndsWith('.class')) {
                throw "Unexpected non-class change: $($entry.FullName)"
            }
            $changed.Add($entry)
        }
    }
    if ($changed.Count -ne 15) { throw "Expected 15 V34-to-V35 class changes, found $($changed.Count)." }

    $payload = Join-Path $package 'payload'
    New-Item -ItemType Directory -Path $payload | Out-Null
    $patch = Join-Path $payload 'classes-patch.zip'
    $patchZip = [System.IO.Compression.ZipFile]::Open($patch, 'Create')
    try {
        foreach ($entry in $changed) {
            $classStream = $entry.Open()
            try {
                $memory = New-Object System.IO.MemoryStream
                try { $classStream.CopyTo($memory); $bytes = $memory.ToArray() }
                finally { $memory.Dispose() }
                if ($bytes.Length -lt 8 -or $bytes[0] -ne 202 -or
                        $bytes[1] -ne 254 -or $bytes[2] -ne 186 -or $bytes[3] -ne 190 -or
                        ($bytes[6] * 256 + $bytes[7]) -gt 52) {
                    throw "Class is not Java 8 compatible: $($entry.FullName)"
                }
                $newEntry = $patchZip.CreateEntry($entry.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
                $newStream = $newEntry.Open()
                try { $newStream.Write($bytes, 0, $bytes.Length) }
                finally { $newStream.Dispose() }
            } finally { $classStream.Dispose() }
        }
    } finally { $patchZip.Dispose() }
} finally { $baseZip.Dispose(); $targetZip.Dispose() }

$javac = Join-Path $jdk 'bin/javac.exe'
$java = Join-Path $jdk 'bin/java.exe'
& $javac -encoding UTF-8 -d $payload (Join-Path $PSScriptRoot 'JarPatchApplier.java')
if ($LASTEXITCODE -ne 0) { throw 'Java 8 patcher compilation failed.' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Update-Minion-V35-Small.cmd') -Destination (Join-Path $package 'Update-Minion-V35-Small.cmd')
$baseHash = (Get-FileHash -LiteralPath $base -Algorithm SHA256).Hash
$patchHash = (Get-FileHash -LiteralPath $patch -Algorithm SHA256).Hash
$patcher = Join-Path $payload 'JarPatchApplier.class'
$patcherHash = (Get-FileHash -LiteralPath $patcher -Algorithm SHA256).Hash
$ascii = [System.Text.Encoding]::ASCII
[System.IO.File]::WriteAllText((Join-Path $payload 'base-jar.sha256'), "$baseHash`r`n", $ascii)
[System.IO.File]::WriteAllText("$patch.sha256", "$patchHash`r`n", $ascii)
[System.IO.File]::WriteAllText("$patcher.sha256", "$patcherHash`r`n", $ascii)

$testJar = Join-Path $env:TEMP "minion-v35-small-test-$([guid]::NewGuid().ToString('N')).jar"
try {
    & $java -cp $payload JarPatchApplier $base $patch $testJar
    if ($LASTEXITCODE -ne 0) { throw 'Patch application test failed.' }
    $actualZip = [System.IO.Compression.ZipFile]::OpenRead($testJar)
    $expectedZip = [System.IO.Compression.ZipFile]::OpenRead($target)
    try {
        $actualEntries = New-Object 'System.Collections.Generic.Dictionary[string,System.IO.Compression.ZipArchiveEntry]' ([System.StringComparer]::Ordinal)
        foreach ($entry in $actualZip.Entries) { $actualEntries[$entry.FullName] = $entry }
        if ($actualEntries.Count -ne $expectedZip.Entries.Count) { throw 'Patched JAR entry count differs from V35.' }
        foreach ($entry in $expectedZip.Entries) {
            $actual = $actualEntries[$entry.FullName]
            if (($null -eq $actual) -or ($actual.Length -ne $entry.Length) -or
                    ((Get-EntryHash $actual) -cne (Get-EntryHash $entry))) {
                throw "Patched JAR content differs from V35: $($entry.FullName)"
            }
        }
    } finally { $actualZip.Dispose(); $expectedZip.Dispose() }
} finally {
    if (Test-Path -LiteralPath $testJar) { Remove-Item -LiteralPath $testJar }
}

$readme = @'
Minion V35 小更新包（仅适用于原版 V34 JAR）

本包只替换 V34 到 V35 变更的 15 个 Java class，不包含完整 JAR。
先退出 Minion，解压整个 ZIP，双击 Update-Minion-V35-Small.cmd，按提示输入安装目录。
脚本会校验补丁和安装目录中的原始 V34 JAR 的 SHA-256；不匹配时不会安装。
更新前会将原 JAR 备份到安装目录的 backup 文件夹。其他配置、项目和历史不会清理。
若已安装 V35、其他小更新或自编译版本，请使用 V35 完整更新包，不要强行套用此补丁。
可运行 Update-Minion-V35-Small.cmd /verify 仅校验压缩包内容。
'@
[System.IO.File]::WriteAllText((Join-Path $package '安装说明.txt'), $readme, [System.Text.Encoding]::UTF8)
[System.IO.Compression.ZipFile]::CreateFromDirectory($package, $archive)
Write-Output "Changed classes: $($changed.Count)"
Get-Item -LiteralPath $archive | Select-Object FullName, Length
Get-FileHash -LiteralPath $archive -Algorithm SHA256 | Select-Object Hash
