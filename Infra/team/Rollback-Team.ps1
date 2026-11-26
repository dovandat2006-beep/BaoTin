#Requires -Version 7.4
[CmdletBinding()]
param([string]$Project='baotin-team',[string]$StateRoot=(Join-Path $env:LOCALAPPDATA 'BaoTin\release-state'))
. (Join-Path $PSScriptRoot 'Team.Common.ps1')
if($Project -notin @('baotin-team','baotin-staging')){throw 'Invalid project'}
$path=Join-Path $StateRoot "$Project.json"
$lock=[IO.File]::Open((Join-Path $StateRoot "$Project.lock"),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try{
    $state=Get-Content -Raw -LiteralPath $path|ConvertFrom-Json -AsHashtable;if(-not $state['previous']){throw 'No previous verified release'}
    $old=$state['previous'];$db=Get-DatabaseCapabilities $state['envFile'];$auth=Get-ImageCapabilities $old['authImage'] 'auth-service';$gateway=Get-ImageCapabilities $old['gatewayImage'] 'api-gateway';Test-ReleaseCompatibility $auth $gateway $db
    $fingerprints=Get-TeamKeyFingerprints $old['envFile'];foreach($name in $fingerprints.Keys){if($old['keyFingerprints'][$name] -ne $fingerprints[$name]){throw 'Saved key files changed; rollback needs the key rotation recovery runbook'}}
    Invoke-TeamDocker -Arguments @('compose','--env-file',$old['envFile'],'-f',(Join-Path $PSScriptRoot 'docker-compose.yml'),'up','-d','auth-service','api-gateway','ingress')|Out-Null
    Wait-TeamHealthy $old['envFile'];Test-TeamSmoke;Write-AtomicJson $path $old;Write-Output 'TEAM_ROLLBACK PASS schema=compatible cipher=compatible healthy=verified'
}finally{$lock.Dispose()}
