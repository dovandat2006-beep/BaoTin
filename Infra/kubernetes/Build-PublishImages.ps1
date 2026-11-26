#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$VerifiedArtifactsDirectory,
      [Parameter(Mandatory)][string]$PublicOrigin,[Parameter(Mandatory)][string]$WorkRoot,
      [Parameter(Mandatory)][string]$OutputManifest,[switch]$Publish,
      [string]$RegistryPrefix,[string]$RegistryUsername,[string]$RegistryTokenFile,
      [string]$DockerContext=((& docker context show).Trim()))
$ErrorActionPreference='Stop'
$work=[IO.Path]::GetFullPath($WorkRoot)
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if($work -eq [IO.Path]::GetPathRoot($work) -or $work.StartsWith($workspace+'\',[StringComparison]::OrdinalIgnoreCase) -or
   -not(Test-Path -LiteralPath $work -PathType Container) -or (Get-Item -LiteralPath $work).LinkType){throw 'Existing regular external work directory required'}
if(Test-Path -LiteralPath $OutputManifest){throw 'Do not overwrite a reviewed registry manifest'}
if($DockerContext -ne ((& docker context show).Trim())){throw 'Image scanner and producer must use the same explicit current Docker context; no context switching is performed'}
$endpoint=(& docker context inspect $DockerContext --format '{{.Endpoints.docker.Host}}').Trim()
if($LASTEXITCODE -or ($endpoint -notlike 'npipe:////./pipe/*' -and $endpoint -notlike 'unix:///*')){throw 'Use a local packaging daemon; remote cluster deployment still uses the published registry digests'}
if($Publish){
    if($RegistryPrefix -cnotmatch '^[a-z0-9][a-z0-9.:-]+/[a-z0-9][a-z0-9_/-]+$' -or $RegistryPrefix.Contains('..') -or
       $RegistryUsername -notmatch '^[A-Za-z0-9][A-Za-z0-9_.@+-]{0,127}$'){throw 'Explicit registry prefix/user required'}
    $token=Get-Item -LiteralPath $RegistryTokenFile -ErrorAction Stop
    if($token.LinkType -or $token.PSIsContainer -or $token.Length -gt 4096 -or $token.FullName.StartsWith($workspace+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Bounded regular external package-write token file required'}
    $allowed=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')
    foreach($rule in (Get-Acl -LiteralPath $token.FullName).Access){if($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin $allowed){throw 'Registry token ACL must be restricted'}}
}
$release=Join-Path $work ('images-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $release|Out-Null
$local=Join-Path $release 'local-images.json'
& python -B (Join-Path $PSScriptRoot 'images.py') --artifacts $VerifiedArtifactsDirectory --origin $PublicOrigin --work-root $work --docker-context $DockerContext --output $local
if($LASTEXITCODE){throw 'Artifact-only image packaging failed'}
# Existing scanner validates OS+packaged-JAR coverage and CycloneDX for all11,
# and owns its cache lock. It does not publish or mount a Docker socket.
& (Join-Path $PSScriptRoot '../alternative/Test-ImageSecurity.ps1') -Manifest $local -OutputDirectory (Join-Path $release 'security')
if(-not $Publish){'KUBERNETES_LOCAL_IMAGES PASS scanned=11 sboms=11 registry_push=false local_manifest='+$local;return}
$data=Get-Content -LiteralPath $local -Raw|ConvertFrom-Json -AsHashtable
$registry=($RegistryPrefix -split '/')[0];$loggedIn=$false
$authDir=Join-Path $release 'temporary-registry-auth';New-Item -ItemType Directory -Path $authDir|Out-Null
$acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetAccessRuleProtection($true,$false)
foreach($sid in $allowed){$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid),'FullControl','ContainerInherit,ObjectInherit','None','Allow'))}
Set-Acl -LiteralPath $authDir -AclObject $acl
$isolated=@('--config',$authDir,'--host',$endpoint)
try{
    # Pipe the token into the fixed registry login. Never put a credential in argv.
    [IO.File]::ReadAllText($token.FullName).Trim()|& docker @isolated login $registry --username $RegistryUsername --password-stdin *> $null
    if($LASTEXITCODE){throw 'Registry login failed; token/output withheld'};$loggedIn=$true
    foreach($name in @($data.images.Keys)){
        $service=switch($name){
            'AUTH_IMAGE'{'auth-service'} 'GATEWAY_IMAGE'{'api-gateway'} 'WEB_IMAGE'{'frontend'}
            'THREAT_IMAGE'{'threat-service'} 'REPORT_IMAGE'{'report-service'} 'REPUTATION_IMAGE'{'reputation-service'}
            'MODEL_IMAGE'{'model-inference-service'} 'NOTIFICATION_IMAGE'{'notification-service'}
            'SEARCH_IMAGE'{'search-service'} 'CAMPAIGN_IMAGE'{'campaign-service'} 'ANALYTICS_IMAGE'{'analytics-service'}
            default{throw 'Unexpected service image field'}
        }
        $id=$data.images[$name];$tag=$RegistryPrefix+'/'+$service+':ci-'+$id.Substring(7,16)
        & docker @isolated tag $id $tag;if($LASTEXITCODE){throw 'OCI tag failed'}
        & docker @isolated push $tag *> (Join-Path $release ($service+'-push.log'));if($LASTEXITCODE){throw 'OCI publication failed; no deploy manifest committed'}
        $digests=(& docker @isolated image inspect $tag --format '{{json .RepoDigests}}')|ConvertFrom-Json
        $expected=$RegistryPrefix+'/'+$service+'@sha256:'
        $matched=@($digests|Where-Object {$_.StartsWith($expected,[StringComparison]::Ordinal)})
        if($matched.Count -ne 1 -or $matched[0] -cnotmatch '@sha256:[a-f0-9]{64}$'){throw 'Published pullable digest missing or ambiguous'}
        $data.images[$name]=$matched[0]
    }
    $pending=$OutputManifest+'.pending'
    if(Test-Path -LiteralPath $pending){throw 'Preserve pending image publication state'}
    $data|ConvertTo-Json -Depth 7|Set-Content -LiteralPath $pending
    Move-Item -LiteralPath $pending -Destination $OutputManifest
    'KUBERNETES_OCI_PUBLICATION PASS images=11 scanned_before_push=true provenance=retained refs=registry_digests'
}finally{
    if($loggedIn){& docker @isolated logout $registry *> $null}
    # Only this newly-created auth directory is removed; existing Docker logins,
    # daemon contexts, image caches and other projects are never rewritten.
    $resolved=[IO.Path]::GetFullPath($authDir)
    if($resolved -ne [IO.Path]::GetFullPath((Join-Path $release 'temporary-registry-auth')) -or (Get-Item -LiteralPath $resolved).LinkType){throw 'Unexpected temporary registry-auth cleanup target'}
    $entries=@(Get-ChildItem -LiteralPath $resolved -Force)
    if(@($entries|Where-Object {$_.PSIsContainer -or $_.LinkType -or $_.Name -ne 'config.json'}).Count){throw 'Unexpected auth-directory content; preserve for restricted operator review'}
    foreach($entry in $entries){Remove-Item -LiteralPath $entry.FullName -Force}
    Remove-Item -LiteralPath $resolved
}
