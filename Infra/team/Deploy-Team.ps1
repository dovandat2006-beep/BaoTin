#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$EnvFile,[Parameter(Mandatory)][string]$ReleaseFile,[string]$StateRoot=(Join-Path $env:LOCALAPPDATA 'BaoTin\release-state'),[switch]$SkipTailscaleCheck)
. (Join-Path $PSScriptRoot 'Team.Common.ps1')
& (Join-Path $PSScriptRoot 'Test-TeamPreflight.ps1') -EnvFile $EnvFile -RequireTailscale:(-not $SkipTailscaleCheck)
$config=Read-TeamConfig $EnvFile;$release=Get-Content -Raw -LiteralPath $ReleaseFile|ConvertFrom-Json -AsHashtable
foreach($name in @('authImage','gatewayImage')){Assert-PinnedImage $release[$name]}
if($release['authImage'].Split('@')[0] -ne $config['AUTH_IMAGE'].Split('@')[0] -or $release['gatewayImage'].Split('@')[0] -ne $config['GATEWAY_IMAGE'].Split('@')[0]){throw 'Release cannot change approved repositories'}
New-Item -ItemType Directory -Path $StateRoot -Force|Out-Null
$project=$config['COMPOSE_PROJECT_NAME'];$statePath=Join-Path $StateRoot "$project.json"
$lock=[IO.File]::Open((Join-Path $StateRoot "$project.lock"),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try{
    foreach($image in @($release['authImage'],$release['gatewayImage'])){Ensure-TeamImage $image}
    $authCap=Get-ImageCapabilities $release['authImage'] 'auth-service';$gateCap=Get-ImageCapabilities $release['gatewayImage'] 'api-gateway'
    $previous=if(Test-Path -LiteralPath $statePath){Get-Content -Raw -LiteralPath $statePath|ConvertFrom-Json -AsHashtable}else{$null}
    $fingerprints=Get-TeamKeyFingerprints $EnvFile
    if($previous){
        if($previous['project'] -ne $project){throw 'Release state belongs to a different project'}
        foreach($name in $fingerprints.Keys){if($previous['keyFingerprints'][$name] -ne $fingerprints[$name]){throw 'Normal release cannot silently rotate/overwrite shared key files; use the reviewed key rotation workflow'}}
        $oldAuth=Get-ImageCapabilities $previous['authImage'] 'auth-service';$oldGate=Get-ImageCapabilities $previous['gatewayImage'] 'api-gateway'
        $prospective=@{schemaRevision=$authCap['migrationVersion'];readerFloor=$authCap['readerFloorAfterMigration'];cipherFloor=$authCap['cipherFloorAfterMigration']}
        Test-ReleaseCompatibility $oldAuth $oldGate $prospective
    }else{
        $existing=Invoke-TeamDocker -Arguments @('ps','-aq','--filter',"label=com.docker.compose.project=$project")
        if($existing.Trim()){throw 'Unmanaged existing project detected; import/review its release state before deployment'}
    }
    $candidate=Join-Path $StateRoot "$project.candidate.env"
    $lines=Get-Content -LiteralPath $EnvFile|ForEach-Object {if($_ -match '^AUTH_IMAGE='){"AUTH_IMAGE=$($release['authImage'])"}elseif($_ -match '^GATEWAY_IMAGE='){"GATEWAY_IMAGE=$($release['gatewayImage'])"}else{$_}};[IO.File]::WriteAllLines($candidate,$lines)
    $compose=Join-Path $PSScriptRoot 'docker-compose.yml';$base=@('compose','--env-file',$candidate,'-f',$compose)
    Invoke-TeamDocker -Arguments ($base+@('up','-d','--wait','--wait-timeout','90','postgres','redis'))|Out-Null
    Invoke-TeamProvisioning -EnvFile $candidate -Image $release['authImage'] -Phase before -SetCredentials
    Invoke-TeamDocker -Arguments ($base+@('run','--rm','migrate'))|Out-Null
    Invoke-TeamProvisioning -EnvFile $candidate -Image $release['authImage'] -Phase after
    $database=Get-DatabaseCapabilities $candidate;Test-ReleaseCompatibility $authCap $gateCap $database
    if($previous){$oldAuth=Get-ImageCapabilities $previous['authImage'] 'auth-service';$oldGate=Get-ImageCapabilities $previous['gatewayImage'] 'api-gateway';Test-ReleaseCompatibility $oldAuth $oldGate $database}
    try{
        Invoke-TeamDocker -Arguments ($base+@('up','-d','auth-service','api-gateway','ingress'))|Out-Null
        Wait-TeamHealthy $candidate;Test-TeamSmoke
        $saved=Join-Path $StateRoot "$project.$([DateTime]::UtcNow.ToString('yyyyMMddHHmmssfff')).env";Copy-Item -LiteralPath $candidate -Destination $saved
        $prior=if($previous){@{project=$previous['project'];authImage=$previous['authImage'];gatewayImage=$previous['gatewayImage'];envFile=$previous['envFile'];keyFingerprints=$previous['keyFingerprints'];capabilities=$previous['capabilities'];verifiedAt=$previous['verifiedAt'];previous=$null}}else{$null}
        Write-AtomicJson $statePath @{project=$project;authImage=$release['authImage'];gatewayImage=$release['gatewayImage'];envFile=$saved;previous=$prior;keyFingerprints=$fingerprints;verifiedAt=[DateTime]::UtcNow.ToString('o');capabilities=@{auth=$authCap;gateway=$gateCap}}
        Write-Output "TEAM_RELEASE PASS project=$project images=pinned rollback=compatible"
    }catch{
        $failure=$_.Exception.Message
        if($previous){Invoke-TeamDocker -Arguments @('compose','--env-file',$previous['envFile'],'-f',$compose,'up','-d','auth-service','api-gateway','ingress')|Out-Null;Wait-TeamHealthy $previous['envFile'];Test-TeamSmoke;Write-Output 'TEAM_RELEASE_ROLLBACK PASS healthy=verified'}
        else {try{Invoke-TeamDocker -Arguments ($base+@('stop','auth-service','api-gateway','ingress'))|Out-Null}catch{}}
        throw "Release verification failed: $failure; previous compatible application state restored when present"
    }
}finally{$lock.Dispose()}
