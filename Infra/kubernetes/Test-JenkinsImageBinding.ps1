#Requires -Version 7.4
# Bind a reviewed OCI manifest to the exact immutable successful child builds.
# Existing CI gates are revalidated by the artifact/image preparation lane; this
# check prevents an old or unrelated image manifest being promoted by a new parent.
[CmdletBinding()]
param([Parameter(Mandatory)][string]$BuildsFile,[Parameter(Mandatory)][string]$JarEnvFile,
      [Parameter(Mandatory)][string]$ImageManifest,[Parameter(Mandatory)][string]$ConfigFile)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$cfg=@{};foreach($line in Get-Content -LiteralPath $JarEnvFile){if($line -match '^([A-Z][A-Z0-9_]*)=(.*)$'){$cfg[$Matches[1]]=$Matches[2]}}
$base=[uri]$cfg.JENKINS_URL
if($base.Scheme -ne 'http' -or $base.DnsSafeHost -notin @('127.0.0.1','localhost','::1') -or $base.Port -notin @(8080,8081) -or $base.UserInfo -or $base.Query -or $base.Fragment -or $base.AbsolutePath -ne '/'){
    throw 'Identified local Jenkins URL required before credentials are read'
}
$probe=Invoke-WebRequest ($base.AbsoluteUri+'login') -NoProxy -MaximumRedirection 0 -TimeoutSec 10
if(($probe.Headers['X-Jenkins'] -join '') -notmatch '^\d+\.\d+(\.\d+)?$'){throw 'Jenkins identity missing'}
$file=Get-Item -LiteralPath $cfg.JENKINS_API_TOKEN_FILE
if($file.LinkType -or $file.PSIsContainer -or $file.Length -gt 1024 -or $file.FullName.StartsWith($workspace+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Bounded regular external Jenkins credential file required'}
$allowed=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')
foreach($rule in (Get-Acl -LiteralPath $file.FullName).Access){if($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin $allowed){throw 'Jenkins token ACL must be restricted'}}
$credential=[pscredential]::new($cfg.JENKINS_USERNAME,(ConvertTo-SecureString ([IO.File]::ReadAllText($file.FullName).Trim()) -AsPlainText -Force))
$http=@{Credential=$credential;Authentication='Basic';AllowUnencryptedAuthentication=$true;NoProxy=$true;MaximumRedirection=0;TimeoutSec=120}
$identity=Invoke-RestMethod @http -Uri ($base.AbsoluteUri+'whoAmI/api/json')
if(-not $identity.authenticated -or $identity.name -ne $cfg.JENKINS_USERNAME){throw 'Jenkins operator mismatch'}
$builds=Get-Content -LiteralPath $BuildsFile -Raw|ConvertFrom-Json -AsHashtable
$images=Get-Content -LiteralPath $ImageManifest -Raw|ConvertFrom-Json -AsHashtable
$cluster=Get-Content -LiteralPath $ConfigFile -Raw|ConvertFrom-Json -AsHashtable
$repos=@('API-Gateway','Auth-Service','Threat-Service','Report-Service','Reputation-Service','Model-Inference-Service','Notification-Service','Search-Service','Campaign-Service','Analytics-Service','Frontend')
if($builds.Count -ne 11 -or $images.artifacts.Count -ne 11 -or @(Compare-Object $repos @($builds.Keys)).Count -or @(Compare-Object $repos @($images.artifacts.Keys)).Count){throw 'Eleven immutable build/image provenance records required'}
foreach($repo in $repos){
    $number=[string]$builds[$repo];$record=$images.artifacts[$repo]
    if($number -notmatch '^[1-9][0-9]{0,8}$' -or [string]$record.build -ne $number -or $record.sourceRevision -cnotmatch '^[a-f0-9]{40}$' -or $record.artifactSha256 -cnotmatch '^[a-f0-9]{64}$'){throw 'Image/build provenance missing or mismatched'}
    $route="job/baotin/job/$repo/$number/"
    $build=Invoke-RestMethod @http -Uri ($base.AbsoluteUri+$route+'api/json?tree=result,building,actions[lastBuiltRevision[SHA1],parameters[name,value]]')
    if($build.building -or $build.result -ne 'SUCCESS'){throw 'Only immutable completed successful child builds may be promoted'}
    if($repo -ne 'Frontend'){
        $evidence=Invoke-RestMethod @http -Uri ($base.AbsoluteUri+$route+'artifact/build/release-evidence.json')
        if($evidence.sourceRevision -ne $record.sourceRevision -or $evidence.jarSha256 -ne $record.artifactSha256){throw 'Image provenance differs from the actual CI JAR/source'}
    }else{
        $revisions=@($build.actions|ForEach-Object {$_.lastBuiltRevision.SHA1}|Where-Object {$_}|Sort-Object -Unique)
        $origin=@($build.actions|ForEach-Object {$_.parameters}|Where-Object name -eq 'PUBLIC_API_ORIGIN')
        if($revisions.Count -ne 1 -or $revisions[0] -ne $record.sourceRevision -or $origin.Count -ne 1 -or $origin[0].value -ne $cluster.publicOrigin -or $record.publicOrigin -ne $cluster.publicOrigin){throw 'Frontend CI source or compiled Gateway origin mismatch'}
        # Stream the actual archive to an incremental hash; never write another
        # full APK/web/JAR copy to the agent disk for this binding check.
        $handler=[Net.Http.HttpClientHandler]::new();$handler.AllowAutoRedirect=$false;$handler.UseProxy=$false;$handler.Credentials=$credential.GetNetworkCredential()
        $client=[Net.Http.HttpClient]::new($handler);$client.Timeout=[TimeSpan]::FromSeconds(120)
        # Jenkins can reject anonymous artifact requests403 without a401 challenge.
        # Send Basic on this already identified loopback origin, never on redirects.
        $auth=$credential.GetNetworkCredential()
        $client.DefaultRequestHeaders.Authorization=[Net.Http.Headers.AuthenticationHeaderValue]::new('Basic',[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($auth.UserName+':'+$auth.Password)))
        $auth=$null
        try{
            $response=$client.GetAsync(($base.AbsoluteUri+$route+'artifact/build/web.tar.gz'),[Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
            if([int]$response.StatusCode -ne 200){throw 'Actual immutable web artifact unavailable'}
            $stream=$response.Content.ReadAsStream();$sha=[Security.Cryptography.SHA256]::Create()
            try{$hash=[Convert]::ToHexString($sha.ComputeHash($stream)).ToLowerInvariant()}finally{$sha.Dispose();$stream.Dispose();$response.Dispose()}
            if($hash -ne $record.artifactSha256){throw 'OCI web provenance does not match actual CI archive'}
        }finally{$client.Dispose()}
    }
}
'JENKINS_IMAGE_BINDING PASS builds=11 immutable_sources_artifacts=matched frontend_origin=matched temporary_artifact_copies=0'
