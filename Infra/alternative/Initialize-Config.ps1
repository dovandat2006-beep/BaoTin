#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$ImageManifest,[Parameter(Mandatory)][string]$JarEnvFile,
      [Parameter(Mandatory)][string]$OutputFile,[string]$DependencyNetwork='baotin-deps_dependencies',
      [string]$DockerContext=((& docker context show).Trim()))
$ErrorActionPreference='Stop'
$app=@{};foreach($line in Get-Content -LiteralPath $JarEnvFile){if($line -match '^([A-Z][A-Z0-9_]*)=(.*)$'){$app[$Matches[1]]=$Matches[2]}}
$manifest=Get-Content -LiteralPath $ImageManifest -Raw|ConvertFrom-Json -AsHashtable
$expected=@('AUTH_IMAGE','GATEWAY_IMAGE','THREAT_IMAGE','REPORT_IMAGE','REPUTATION_IMAGE','MODEL_IMAGE','NOTIFICATION_IMAGE','SEARCH_IMAGE','CAMPAIGN_IMAGE','ANALYTICS_IMAGE','WEB_IMAGE')
if($manifest.schemaVersion -ne 1 -or $manifest.images.Count -ne 11 -or @(Compare-Object $expected @($manifest.images.Keys)).Count){throw 'Full immutable image manifest required'}
foreach($image in $manifest.images.Values){if($image -notmatch '^sha256:[a-f0-9]{64}$'){throw 'Local immutable image IDs required'}}
$secretRoot=[IO.Path]::GetFullPath((Split-Path -Parent $app.AUTH_CONFIG_DIR))
if([IO.Path]::GetFullPath($app.GATEWAY_CONFIG_DIR) -ne [IO.Path]::GetFullPath((Join-Path $secretRoot 'gateway-config')) -or
   [IO.Path]::GetFullPath($app.JWT_PRIVATE_KEY_PATH) -ne [IO.Path]::GetFullPath((Join-Path $secretRoot 'auth-keys/private.pem')) -or
   [IO.Path]::GetFullPath($app.AUTH_ENCRYPTION_KEY_PATH) -ne [IO.Path]::GetFullPath((Join-Path $secretRoot 'auth-keys/encryption.bin'))){throw 'Reviewed external secret layout required'}
$networkRaw=& docker --context $DockerContext network inspect $DependencyNetwork
if($LASTEXITCODE){throw 'Existing dependency network required'}
$network=$networkRaw|ConvertFrom-Json
if($network.Labels.'com.docker.compose.project' -ne 'baotin-deps'){throw 'Reviewed dependency project required'}
$subnet=@($network.IPAM.Config|Where-Object {$_.Subnet -match '^\d+\.'})[0].Subnet
$octets=($subnet.Split('/')[0]).Split('.');$bits=[int]$subnet.Split('/')[1]
if($bits -gt 24 -or $bits -lt 16){throw 'Review dependency address range before assignment'}
$prefix=($octets[0..2] -join '.')
$addresses=@{GATEWAY_IP=$prefix+'.200';AUTH_IP=$prefix+'.201';WEB_IP=$prefix+'.202'}
$i=203;foreach($service in 'THREAT','REPORT','REPUTATION','MODEL','NOTIFICATION','SEARCH','CAMPAIGN','ANALYTICS'){$addresses[$service+'_IP']=$prefix+'.'+$i;$i++}
foreach($property in $network.Containers.PSObject.Properties){
    $container=$property.Value
    if($container.Name -notlike 'baotin-alt-*' -and $container.IPv4Address.Split('/')[0] -in $addresses.Values){throw 'Reserved alternative IP belongs to another container'}
}
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output=[IO.Path]::GetFullPath($OutputFile)
if($output.StartsWith($workspace+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $output)){throw 'New external public alternative configuration required'}
$lines=@("BAOTIN_SECRETS_DIR=$($secretRoot.Replace('\','/'))","DEPENDENCY_NETWORK=$DependencyNetwork",'WEB_PORT=8095',"JWT_KEY_ID=$($app.JWT_KEY_ID)","AUTH_ALLOWED_ORIGINS=$($app.AUTH_ALLOWED_ORIGINS)")
foreach($key in $addresses.Keys){$lines+="$key=$($addresses[$key])"}
foreach($key in $expected){$lines+="$key=$($manifest.images[$key])"}
[IO.File]::WriteAllLines($output,$lines,[Text.UTF8Encoding]::new($false))
'ALT_CONFIG_READY images=11 network=existing_dependencies db=one_physical keys=shared refs=immutable'
