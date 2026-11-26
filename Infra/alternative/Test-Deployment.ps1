#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$EnvFile,[Parameter(Mandatory)][string]$ImageManifest,
      [Parameter(Mandatory)][string]$OutputFile,[string]$DockerContext=((& docker context show).Trim()))
$ErrorActionPreference='Stop'
$cfg=@{};foreach($line in Get-Content -LiteralPath $EnvFile){if($line -match '^([A-Z][A-Z0-9_]*)=(.*)$'){$cfg[$Matches[1]]=$Matches[2]}}
$manifest=Get-Content -LiteralPath $ImageManifest -Raw|ConvertFrom-Json -AsHashtable
$services=@{'auth-service'='AUTH_IMAGE';'api-gateway'='GATEWAY_IMAGE';'frontend'='WEB_IMAGE';'threat-service'='THREAT_IMAGE';'report-service'='REPORT_IMAGE';'reputation-service'='REPUTATION_IMAGE';'model-inference-service'='MODEL_IMAGE';'notification-service'='NOTIFICATION_IMAGE';'search-service'='SEARCH_IMAGE';'campaign-service'='CAMPAIGN_IMAGE';'analytics-service'='ANALYTICS_IMAGE'}
$results=@()
foreach($name in $services.Keys){
    $ids=@(& docker --context $DockerContext ps -aq --filter 'label=com.docker.compose.project=baotin-alt' --filter "label=com.docker.compose.service=$name")
    if($LASTEXITCODE -or $ids.Count -ne 1){throw 'Exactly one alternative container per service required'}
    $meta=(& docker --context $DockerContext inspect $ids[0])|ConvertFrom-Json
    if($meta.Image -ne $manifest.images[$services[$name]] -or $meta.Image -ne $cfg[$services[$name]] -or
       $meta.State.Health.Status -ne 'healthy' -or -not $meta.HostConfig.ReadonlyRootfs -or
       $meta.Config.User -in @('','0','root','0:0')){throw "Alternative readiness/image/privilege failed: $name"}
    $actual=(& docker --context $DockerContext image inspect $meta.Image --format '{{.Id}}').Trim()
    if($LASTEXITCODE -or $actual -ne $meta.Image){throw 'Deploy image must remain resolvable/retained for recreation'}
    foreach($property in $meta.HostConfig.PortBindings.PSObject.Properties){foreach($binding in @($property.Value)){
        if($binding -and ($name -ne 'frontend' -or $binding.HostIp -ne '127.0.0.1' -or $binding.HostPort -ne $cfg.WEB_PORT)){throw 'Unexpected alternative host publication'}
    }}
    if($name -ne 'frontend'){
        $repo=($manifest.artifacts.Keys|Where-Object {$_.ToLowerInvariant() -eq $name}|Select-Object -First 1)
        if(-not $repo){throw 'Missing source artifact identity'}
        $hash=& docker --context $DockerContext exec $ids[0] sha256sum /app/app.jar
        if($LASTEXITCODE -or ($hash -split '\s+')[0] -ne $manifest.artifacts[$repo].sha256){throw 'Running JAR differs from tested artifact'}
    }
    $results+=@{service=$name;image=$meta.Image;healthy=$true;readOnly=$true;user=$meta.Config.User}
}
& docker --context $DockerContext exec baotin-alt-api-gateway-1 sh -c 'test ! -e /run/auth-keys/private.pem'
if($LASTEXITCODE){throw 'Gateway must not receive Auth private key'}
$modes=& docker --context $DockerContext exec baotin-alt-auth-service-1 sh -c 'stat -c "%u:%g:%a" /run/auth-keys/private.pem /run/auth-config/spring.datasource.password'
if($LASTEXITCODE -or @($modes|Where-Object{$_ -ne '1001:1001:400'}).Count){throw 'Linux secret ownership/modes incorrect'}
$web=Invoke-RestMethod ('http://127.0.0.1:'+$cfg.WEB_PORT+'/healthz') -NoProxy -TimeoutSec 5
if($web.status -ne 'UP'){throw 'Alternative web health failed'}
[IO.File]::WriteAllText($OutputFile,(@{services=$results;backend=10;web=1;scopedKeys=$true;linuxSecretMode='1001:1001:400';domainConfigured=$false}|ConvertTo-Json -Depth 5))
'ALT_DEPLOYMENT PASS backend=10 web=1 health=UP images=immutable_and_retained jar_hashes=matched user=nonroot filesystem=readonly secrets=scoped host_ports=frontend_loopback'
