#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Username,[Parameter(Mandatory)][string]$TokenFile,
      [string]$JenkinsUrl='http://[::1]:8080/',[string[]]$PipelineFiles,
      [string]$OutputFile)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if($OutputFile){
    $report=[IO.Path]::GetFullPath($OutputFile)
    if($report -eq [IO.Path]::GetFullPath($TokenFile) -or [IO.Path]::GetExtension($report) -ne '.json' -or ((Test-Path -LiteralPath $report) -and (Get-Item -LiteralPath $report).LinkType)){throw 'Validation report must be a distinct regular JSON file'}
    [IO.File]::WriteAllText($report,(@{checkedAt=[DateTime]::UtcNow.ToString('o');validated=$false;pipelines=@()}|ConvertTo-Json -Depth 4))
}
$uri=$null
if(-not [uri]::TryCreate($JenkinsUrl,[UriKind]::Absolute,[ref]$uri) -or $uri.Scheme -notin @('http','https') -or $uri.DnsSafeHost -notin @('127.0.0.1','localhost','::1') -or $uri.Port -ne 8080 -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/'){throw 'Jenkins controller must be the existing local loopback endpoint'}
if($Username -notmatch '^[A-Za-z0-9_.@-]{1,128}$'){throw 'Invalid Jenkins username'}
try{$probe=Invoke-WebRequest ($JenkinsUrl+'login') -NoProxy -MaximumRedirection 0 -SkipHttpErrorCheck -TimeoutSec 5 -Verbose:$false -Debug:$false}catch{throw 'Jenkins endpoint identification failed; no credentials sent'}
if([int]$probe.StatusCode -ne 200 -or ($probe.Headers['X-Jenkins'] -join '') -notmatch '^\d+\.\d+(\.\d+)?$'){throw 'Jenkins endpoint not identified; no credentials read or sent'}
$path=(Resolve-Path -LiteralPath $TokenFile).Path
if($path.StartsWith($workspace+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Get-Item -LiteralPath $path).LinkType){throw 'Jenkins token file must be a regular external file'}
if((Get-Item -LiteralPath $path).Length -gt 1024){throw 'Token file exceeds bounded credential size'}
$allowed=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')
foreach($rule in (Get-Acl -LiteralPath $path).Access){if($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin $allowed){throw 'Restrict Jenkins token-file ACL before validation'}}
$token=[IO.File]::ReadAllText($path).Trim()
if($token -notmatch '^[A-Za-z0-9_-]{24,256}$'){throw 'Invalid token-file content'}
$credential=[pscredential]::new($Username,(ConvertTo-SecureString $token -AsPlainText -Force));$token=$null
$session=[Microsoft.PowerShell.Commands.WebRequestSession]::new()
$http=@{Credential=$credential;Authentication='Basic';AllowUnencryptedAuthentication=$true;WebSession=$session;NoProxy=$true;MaximumRedirection=0;SkipHttpErrorCheck=$true;TimeoutSec=10}

function Request([string]$Route,[string]$Method='GET',[hashtable]$Body,[hashtable]$Headers){
    $args=@{Uri=($JenkinsUrl.TrimEnd('/')+'/'+$Route);Method=$Method}
    if($Body){$args.Body=$Body};if($Headers){$args.Headers=$Headers}
    try{$response=Invoke-WebRequest @http @args -Verbose:$false -Debug:$false}catch{throw 'Jenkins transport failed; diagnostics withheld'}
    if([int]$response.StatusCode -ge 300 -and [int]$response.StatusCode -lt 400){throw 'Jenkins redirect rejected'}
    return $response
}
$identity=Request 'whoAmI/api/json'
if([int]$identity.StatusCode -ne 200){throw 'Jenkins authentication failed'}
try{$who=$identity.Content|ConvertFrom-Json}catch{throw 'Invalid Jenkins identity response'}
if(-not $who.authenticated -or $who.name -ne $Username){throw 'Jenkins authenticated identity mismatch'}
$headers=@{};$crumb=Request 'crumbIssuer/api/json'
if([int]$crumb.StatusCode -eq 200){
    try{$proof=$crumb.Content|ConvertFrom-Json}catch{throw 'Invalid Jenkins crumb response'}
    if($proof.crumbRequestField -notmatch '^[A-Za-z][A-Za-z0-9-]{0,63}$' -or -not $proof.crumb){throw 'Invalid Jenkins crumb response'}
    $headers[$proof.crumbRequestField]=[string]$proof.crumb
}elseif([int]$crumb.StatusCode -ne 404){throw 'Jenkins crumb check failed'}
if(-not $PipelineFiles){$PipelineFiles=@((Join-Path $workspace 'Auth-Service/Jenkinsfile'),(Join-Path $workspace 'API-Gateway/Jenkinsfile'),(Join-Path $workspace 'Infra/team/Jenkinsfile'))}
$results=@()
foreach($file in $PipelineFiles){
    $source=(Resolve-Path -LiteralPath $file).Path
    if(-not $source.StartsWith($workspace+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Get-Item -LiteralPath $source).Length -gt 131072){throw 'Pipeline must be a bounded workspace file'}
    $response=Request 'pipeline-model-converter/validate' 'POST' @{jenkinsfile=[IO.File]::ReadAllText($source)} $headers
    $body=if($response.Content -is [byte[]]){[Text.Encoding]::UTF8.GetString($response.Content)}else{$response.Content}
    if([int]$response.StatusCode -ne 200 -or $body.Trim() -ne 'Jenkinsfile successfully validated.'){throw ('Jenkins pipeline validation failed: '+[IO.Path]::GetFileName($source))}
    $relative=[IO.Path]::GetRelativePath($workspace,$source).Replace([IO.Path]::DirectorySeparatorChar,[char]'/')
    $results+=@{pipeline=$relative;sha256=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant();validated=$true}
    Write-Output "JENKINS_PIPELINE PASS file=$relative authenticated=true"
}
if($OutputFile){[IO.File]::WriteAllText($report,(@{checkedAt=[DateTime]::UtcNow.ToString('o');validated=$true;pipelines=$results}|ConvertTo-Json -Depth 5))}
Write-Output "JENKINS_VALIDATION PASS pipelines=$($results.Count) controller_changed=false token_logged=false"
