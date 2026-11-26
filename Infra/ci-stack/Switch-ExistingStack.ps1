#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$StateDirectory,[switch]$Apply,[switch]$Rollback,[switch]$SkipVolumeBackup)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$state=(Resolve-Path -LiteralPath $StateDirectory).Path
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if($state.Equals($workspace,[StringComparison]::OrdinalIgnoreCase) -or $state.StartsWith($workspace+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Get-Item $state).LinkType){throw 'Use a regular external private state directory'}
function Docker([string[]]$Arguments){
    $executable=(Get-Command docker -CommandType Application|Select-Object -First 1).Source
    $result=& $executable @Arguments
    if($LASTEXITCODE){throw ('Docker operation failed: '+$Arguments[0])}
    return $result
}
function Assert-VolumeArchives([string]$Directory,[bool]$Skip){
    if($Skip){'CI_STACK_VOLUME_ARCHIVE skipped=explicit_operator_option';return}
    $expected=@('jenkins-volumes.tar.xz','docker-volumes.tar.xz','sonar-volumes.tar.xz','sonar-db-volumes.tar.xz')
    foreach($file in $expected){
        if(-not(Test-Path "$Directory/$file") -or (Get-Item "$Directory/$file").Length -lt 1024){throw 'Complete consistent backups required'}
    }
    $hashes=Get-Content "$Directory/backup-hashes.json" -Raw|ConvertFrom-Json
    if(@($hashes).Count -ne 4 -or @(Compare-Object $expected @($hashes.name)).Count){throw 'All four backup hashes required'}
    foreach($entry in $hashes){
        if($entry.sha256 -notmatch '^[a-fA-F0-9]{64}$' -or (Get-Item "$Directory/$($entry.name)").Length -ne $entry.bytes -or (Get-FileHash "$Directory/$($entry.name)").Hash -ne $entry.sha256){throw 'Backup integrity mismatch'}
    }
}
$names=@('jenkins-blueocean','docker','sonarqube','baotin-sonar-db')
$originals=@{}
foreach($name in $names){
    $c=@(Get-Content -LiteralPath "$state/$name.inspect.json" -Raw|ConvertFrom-Json)[0]
    if($c.Name -ne '/'+$name -or $c.Id -notmatch '^[a-f0-9]{64}$'){throw 'Invalid saved container identity'}
    $originals[$name]=$c
}
$manifest=Join-Path $state 'manifest.env'
$compose=Join-Path $PSScriptRoot 'compose.yml'
Docker -Arguments @('compose','--env-file',$manifest,'-f',$compose,'config','--quiet')|Out-Null
if(-not $Apply){'CI_STACK_PREFLIGHT PASS containers=4 external_volumes=true changed=false';return}
if($Rollback){
    # Candidate must be stopped before any original writer can restart.
    foreach($name in $names){
        $c=@((Docker -Arguments @('inspect',$name))|ConvertFrom-Json)[0]
        if($c.Config.Labels.'com.docker.compose.project' -ne 'baotin-ci'){throw 'Rollback target is not the adopted CI stack'}
    }
    foreach($name in @('jenkins-blueocean','sonarqube','docker','baotin-sonar-db')){
        Docker -Arguments @('stop','--time','60',$name)|Out-Null
        Docker -Arguments @('rename',$name,($name+'.candidate-ci'))|Out-Null
        $c=@((Docker -Arguments @('inspect',($name+'.candidate-ci')))|ConvertFrom-Json)[0]
        foreach($network in $c.NetworkSettings.Networks.PSObject.Properties.Name){Docker -Arguments @('network','disconnect',$network,$c.Id)|Out-Null}
    }
    foreach($name in $names){
        $saved=$originals[$name]
        $c=@((Docker -Arguments @('inspect',$saved.Id))|ConvertFrom-Json)[0]
        if($c.State.Running){throw 'Original unexpectedly running'}
        Docker -Arguments @('rename',$saved.Id,$name)|Out-Null
        foreach($network in $saved.NetworkSettings.Networks.PSObject.Properties){
            $args=@('network','connect')
            foreach($alias in @($network.Value.Aliases|Where-Object {$_ -and $_ -ne $saved.Id.Substring(0,12)})){$args+=@('--alias',$alias)}
            Docker -Arguments ($args+@($network.Name,$saved.Id))|Out-Null
        }
    }
    foreach($name in @('baotin-sonar-db','docker','sonarqube','jenkins-blueocean')){Docker -Arguments @('start',$name)|Out-Null}
    'CI_STACK_ROLLBACK PASS originals=4 candidate_writers=stopped volumes=retained'
    return
}
foreach($name in $names){
    $c=@((Docker -Arguments @('inspect',$name))|ConvertFrom-Json)[0]
    if($c.Id -ne $originals[$name].Id -or $c.State.Running){throw 'Adoption requires the exact saved, stopped originals; drain builds first'}
}
# Explicit operator waiver affects archives only, never volume identity/writer guards.
Assert-VolumeArchives -Directory $state -Skip ([bool]$SkipVolumeBackup)
foreach($name in $names){
    $saved=$originals[$name]
    Docker -Arguments @('rename',$saved.Id,($name+'.before-ci'))|Out-Null
    foreach($network in $saved.NetworkSettings.Networks.PSObject.Properties.Name){Docker -Arguments @('network','disconnect',$network,$saved.Id)|Out-Null}
}
try{
    Docker -Arguments @('compose','--env-file',$manifest,'-f',$compose,'up','-d','--pull','never','--wait','--wait-timeout','360')|Out-Null
    foreach($name in $names){
        $now=@((Docker -Arguments @('inspect',$name))|ConvertFrom-Json)[0]
        $old=$originals[$name]
        foreach($mount in $old.Mounts){
            if($mount.Type -ne 'volume'){throw 'Unsupported original mount type'}
            $match=@($now.Mounts|Where-Object {$_.Destination -eq $mount.Destination -and $_.Name -eq $mount.Name -and $_.RW -eq $mount.RW})
            if($match.Count -ne 1){throw 'Persistent mount changed'}
        }
        if($now.Mounts.Count -ne $old.Mounts.Count){throw 'Unexpected persistent mount'}
    }
    'CI_STACK_ADOPT PASS containers=4 exact_volumes=preserved originals=stopped_retained'
}catch{
    # Keep originals/backups; never start originals while candidate writers survive.
    Write-Error 'CI adoption incomplete; inspect current state, stop candidate writers, then use -Apply -Rollback. Originals/backups remain.'
    throw
}
