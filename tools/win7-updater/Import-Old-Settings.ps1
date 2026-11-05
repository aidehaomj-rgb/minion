param([Parameter(Mandatory=$true)][string]$OldDir)
$ErrorActionPreference='Stop'
$target=Split-Path -Parent $MyInvocation.MyCommand.Path
$old=(Resolve-Path -LiteralPath $OldDir).Path.TrimEnd('\')
$target=(Resolve-Path -LiteralPath $target).Path.TrimEnd('\')
if($old -eq $target){throw 'Source and destination must differ.'}
if(-not (Test-Path -LiteralPath (Join-Path $old 'minion-0.1.0.jar'))){throw 'Old installation JAR not found.'}
# Only copy user-owned configuration/history, never old JARs, runtimes or diagnostic caches.
$names=@('model.json','workspace.json','config.properties','mcp.json','tools.json','session','skills','.minion\memory','.minion\secrets.json')
foreach($name in $names){
    if((Test-Path -LiteralPath (Join-Path $old $name)) -and (Test-Path -LiteralPath (Join-Path $target $name))){
        throw ('Destination already exists; nothing copied: '+$name)
    }
}
foreach($name in $names){
    $source=Join-Path $old $name
    if(-not (Test-Path -LiteralPath $source)){continue}
    $dest=Join-Path $target $name
    $parent=Split-Path -Parent $dest
    if(-not (Test-Path -LiteralPath $parent)){New-Item -ItemType Directory -Path $parent -Force|Out-Null}
    Copy-Item -LiteralPath $source -Destination $dest -Recurse
    Write-Host ('Copied: '+$name)
}
$java=Join-Path $target 'jdk8\bin\java.exe'
& $java '-Dfile.encoding=UTF-8' -cp ((Join-Path $target 'minion-0.1.0.jar')+';'+(Join-Path $target 'diagnostics')) SettingsMigration $old $target
if($LASTEXITCODE -ne 0){throw 'Files copied, but relative path migration failed. Check the new directory before starting.'}
Write-Host 'Migration complete. Old installation was not changed.'
Write-Host 'Check workspace paths and Python settings before resuming old tasks.'
Write-Host 'Encrypted secrets are tied to the original Windows user/computer; re-enter them if needed.'
