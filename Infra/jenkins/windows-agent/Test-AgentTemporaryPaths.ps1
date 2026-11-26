$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'AgentTemporaryPaths.ps1')
$originalTemp=$env:TEMP
$originalTmp=$env:TMP
$p=Resolve-AgentTemporaryPaths -TempRoot 'L:\BaoTin-agent-state\temp' -CacheRoot 'L:\BaoTin-agent-state\cache'
if($p.TEMP -ne $p.TMP -or $p.TEMP -ne 'L:\BaoTin-agent-state\temp' -or $p.GRADLE_USER_HOME -ne 'L:\BaoTin-agent-state\cache\gradle'){throw 'Incorrect dedicated path plan'}
foreach($path in @('relative-path','L:\',(Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path,(Join-Path (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path 'Frontend/cache'))){
    $failed=$false
    try{Resolve-AgentTemporaryPaths -TempRoot $path|Out-Null}catch{$failed=$true}
    if(-not $failed){throw 'Invalid path accepted'}
}
if($env:TEMP -ne $originalTemp -or $env:TMP -ne $originalTmp){throw 'Validation changed process configuration'}
if((Resolve-AgentTemporaryPaths).Count -ne 0){throw 'Existing default behavior changed'}
'AGENT_TEMP_PATHS PASS external=validated invalid=4 validationMutation=false defaults=preserved'
