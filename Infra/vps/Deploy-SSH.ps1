#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$ConfigFile,[Parameter(Mandatory)][string]$ImageManifest,
      [Parameter(Mandatory)][string]$KeyFile,[switch]$ValidateOnly)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if([IO.Path]::GetFullPath($KeyFile).StartsWith($workspace+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'SSH private key must remain outside source workspace'}
$cfg=@{};foreach($line in Get-Content -LiteralPath $ConfigFile){if($line -match '^([A-Z][A-Z0-9_]*)=(.*)$'){if($cfg.Contains($Matches[1])){throw 'Duplicate VPS configuration'};$cfg[$Matches[1]]=$Matches[2]}}
if($cfg.VPS_HOST -notmatch '^(?!-)[A-Za-z0-9][A-Za-z0-9.-]{0,252}$' -or $cfg.VPS_HOST -match 'YOUR_|example' -or
   $cfg.VPS_USER -notmatch '^[a-z_][a-z0-9_-]{0,31}$' -or $cfg.VPS_USER -eq 'root' -or
   $cfg.VPS_PORT -notmatch '^\d{1,5}$' -or [int]$cfg.VPS_PORT -lt 1 -or [int]$cfg.VPS_PORT -gt 65535 -or
   $cfg.VPS_ROOT -notmatch '^/opt/baotin(?:/[a-zA-Z0-9_-]+)?$'){throw 'Configure explicit VPS host/user/port/root before any SSH connection'}
foreach($file in $KeyFile,$cfg.KNOWN_HOSTS_FILE){if(-not $file -or -not(Test-Path -LiteralPath $file -PathType Leaf) -or (Get-Item $file).LinkType){throw 'Regular external key and verified known_hosts files required'}}
$data=Get-Content -LiteralPath $ImageManifest -Raw|ConvertFrom-Json -AsHashtable
$repos=@{AUTH_IMAGE='auth-service';GATEWAY_IMAGE='api-gateway';WEB_IMAGE='frontend';THREAT_IMAGE='threat-service';REPORT_IMAGE='report-service';REPUTATION_IMAGE='reputation-service';MODEL_IMAGE='model-inference-service';NOTIFICATION_IMAGE='notification-service';SEARCH_IMAGE='search-service';CAMPAIGN_IMAGE='campaign-service';ANALYTICS_IMAGE='analytics-service'}
if($data.schemaVersion -ne 1 -or $data.images.Count -ne 11 -or @(Compare-Object @($repos.Keys) @($data.images.Keys)).Count){throw 'Reviewed eleven-image VPS manifest required'}
foreach($k in $repos.Keys){if($data.images[$k] -cnotmatch ('^ghcr\.io/vietluccuu/'+[regex]::Escape($repos[$k])+'@sha256:[a-f0-9]{64}$')){throw 'VPS deployment accepts only mapped immutable registry digests'}}
if($ValidateOnly){'VPS_SSH_PREFLIGHT PASS refs=11_immutable strict_host_key=true connection_attempted=false';return}
$ssh=Get-Command ssh -ErrorAction Stop;$scp=Get-Command scp -ErrorAction Stop
$id=[guid]::NewGuid().ToString('N');$remote=$cfg.VPS_ROOT+'/releases/'+$id
$common=@('-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes','-o',('UserKnownHostsFile='+$cfg.KNOWN_HOSTS_FILE),'-i',$KeyFile)
$target=$cfg.VPS_USER+'@'+$cfg.VPS_HOST
& $ssh.Source @common -p $cfg.VPS_PORT $target ("mkdir -p '$remote'")
if($LASTEXITCODE){throw 'Verified SSH connection failed; no deployment started'}
$temp=Join-Path ([IO.Path]::GetTempPath()) ('baotin-vps-'+$id);New-Item -ItemType Directory $temp|Out-Null
try{
 $lines=@($repos.Keys|Sort-Object|ForEach-Object {$_+'='+$data.images[$_]})
 [IO.File]::WriteAllLines((Join-Path $temp 'images.env'),$lines,[Text.UTF8Encoding]::new($false))
 & $scp.Source @common -P $cfg.VPS_PORT (Join-Path $temp 'images.env') (Join-Path $PSScriptRoot 'remote-deploy.sh') (Join-Path $PSScriptRoot '../alternative/docker-compose.yml') (Join-Path $PSScriptRoot '../alternative/prepare-secrets.sh') ($target+':'+$remote+'/')
 if($LASTEXITCODE){throw 'SSH release upload failed; active configuration unchanged'}
 & $ssh.Source @common -p $cfg.VPS_PORT $target ("sh '$remote/remote-deploy.sh' '$remote' '$($cfg.VPS_ROOT)'")
 if($LASTEXITCODE){throw 'Remote deployment failed; inspect sanitized remote gate output'}
 'VPS_SSH_DEPLOY PASS immutable_images=11 readiness=UP transport=verified_SSH'
}finally{
 $resolved=[IO.Path]::GetFullPath($temp);$expected=Join-Path ([IO.Path]::GetTempPath()) ('baotin-vps-'+$id)
 if($resolved -ne [IO.Path]::GetFullPath($expected)){throw 'Unexpected temporary cleanup target'}
 Remove-Item -LiteralPath $resolved -Recurse -Force
}
