#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$EnvFile,[switch]$RequireTailscale,[switch]$RequireBuildDaemon)
. (Join-Path $PSScriptRoot 'Team.Common.ps1')
$config=Read-TeamConfig $EnvFile;$root=Assert-TeamPaths $config
foreach($name in @('AUTH_IMAGE','GATEWAY_IMAGE','POSTGRES_IMAGE','REDIS_IMAGE','NGINX_IMAGE')){Assert-PinnedImage $config[$name]}
$endpoint=Invoke-TeamDocker -Arguments @('context','inspect','desktop-linux','--format','{{.Endpoints.docker.Host}}')
if($endpoint.Trim() -ne 'npipe:////./pipe/dockerDesktopLinuxEngine'){throw 'Only local Docker Desktop named-pipe target allowed'}
foreach($rel in @('postgres/password','migration/password','operator/password','redis/password','redis/redis.conf','auth-config/spring.datasource.password','gateway-config/spring.data.redis.password','auth-keys/private.pem','auth-keys/encryption.bin')){
    $path=Join-Path $root $rel;if(-not(Test-Path -LiteralPath $path -PathType Leaf)){throw "Missing external secret file: $rel"};if((Get-Item -LiteralPath $path).Length -eq 0){throw "Empty secret file: $rel"}
}
if((Get-Item -LiteralPath (Join-Path $root 'auth-keys/encryption.bin')).Length -ne 32){throw 'Encryption key must be 32 raw bytes'}
$redisPassword=[IO.File]::ReadAllText((Join-Path $root 'redis/password')).TrimEnd()
if($redisPassword -notmatch '^[A-Za-z0-9_-]{32,128}$'){throw 'Redis requires high-entropy file password'}
if([IO.File]::ReadAllText((Join-Path $root 'gateway-config/spring.data.redis.password')).TrimEnd() -ne $redisPassword){throw 'Redis/Gateway credential files differ'}
$redisConfig=[IO.File]::ReadAllText((Join-Path $root 'redis/redis.conf'))
if($redisConfig -notmatch ('(?m)^requirepass\s+'+[regex]::Escape($redisPassword)+'\s*$')){throw 'Redis config must require the matching file password'}
$model=(Invoke-TeamDocker -Arguments @('compose','--env-file',$EnvFile,'-f',(Join-Path $PSScriptRoot 'docker-compose.yml'),'config','--format','json'))|ConvertFrom-Json
foreach($service in $model.services.PSObject.Properties){
    if($service.Name -ne 'ingress' -and $service.Value.PSObject.Properties.Name -contains 'ports' -and $service.Value.ports){throw 'Only ingress may publish'}
    if($service.Name -match 'jenkins|docker$'){throw 'Team Compose must not manage Jenkins/dind'}
}
if($RequireBuildDaemon){$first=(Invoke-TeamDocker -Arguments @('inspect','docker','--format','{{json .State}} {{.RestartCount}}')).Trim();Start-Sleep -Seconds 20;$second=(Invoke-TeamDocker -Arguments @('inspect','docker','--format','{{json .State}} {{.RestartCount}}')).Trim();if($first -ne $second){throw 'Build dind restarted during preflight; project scripts must not recreate it'}}
if($RequireTailscale){
    $status=tailscale status --json|ConvertFrom-Json;if($status.BackendState -ne 'Running' -or -not $status.Self.DNSName){throw 'Tailscale must be logged in and Running'}
    $hostName=$status.Self.DNSName.TrimEnd('.')
    $serve=tailscale serve status --json|ConvertFrom-Json -AsHashtable
    Assert-TeamTailnet $config['AUTH_ALLOWED_ORIGINS'] $hostName $serve
}
Write-Output 'TEAM_PREFLIGHT PASS target=desktop-linux secrets=files ports=ingress-only'
