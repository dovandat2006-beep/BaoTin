#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$AgentJar,[Parameter(Mandatory)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$AgentJarSha256,[Parameter(Mandatory)][string]$SecretFile,[Parameter(Mandatory)][string]$WorkDir,[Parameter(Mandatory)][string]$JavaHome,[string]$JenkinsUrl='http://[::1]:8080/',[string]$TempRoot,[string]$CacheRoot,[switch]$ValidateOnly)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'AgentTemporaryPaths.ps1')
$temporaryPaths=Resolve-AgentTemporaryPaths -TempRoot $TempRoot -CacheRoot $CacheRoot
if(-not $ValidateOnly -and (& (Join-Path $PSScriptRoot 'Get-ProcessElevation.ps1') -ProcessId $PID)){throw 'Start agent from a normal non-elevated user session'}
$uri=[uri]$JenkinsUrl;if($uri.Scheme -notin @('http','https') -or $uri.DnsSafeHost -notin @('127.0.0.1','localhost','::1') -or $uri.Port -ne 8080 -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/'){throw 'Local existing controller URL only'}
if((Get-FileHash -Algorithm SHA256 -LiteralPath $AgentJar).Hash -ne $AgentJarSha256.ToUpperInvariant()){throw 'agent.jar hash mismatch'}
if(-not(Test-Path -LiteralPath $SecretFile -PathType Leaf)){throw 'Node secret file missing'}
$acl=Get-Acl -LiteralPath $SecretFile
$currentSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$allowedSids=@($currentSid,'S-1-5-18','S-1-5-32-544') # Current operator, SYSTEM, local Administrators; independent of OS language.
foreach($rule in $acl.Access){$sid=$rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value;if($rule.AccessControlType -eq 'Allow' -and $sid -notin $allowedSids){throw 'Restrict agent-secret ACL before starting'}}
$java=Join-Path $JavaHome 'bin/java.exe';if(-not(Test-Path -LiteralPath $java)){throw 'JDK21 Java executable required'}
$version=& $java -version 2>&1;if(($version -join ' ') -notmatch 'version "21\.'){throw 'Deploy agent requires JDK21'}
$endpoint=& docker --context desktop-linux context inspect desktop-linux --format '{{.Endpoints.docker.Host}}'
if($endpoint.Trim() -ne 'npipe:////./pipe/dockerDesktopLinuxEngine'){throw 'Local Docker Desktop pipe required'}
try{$probe=Invoke-WebRequest ($JenkinsUrl+'login') -NoProxy -MaximumRedirection 0 -SkipHttpErrorCheck -TimeoutSec 5 -Verbose:$false -Debug:$false}catch{throw 'Jenkins endpoint identification failed; no agent started'}
if([int]$probe.StatusCode -ne 200 -or ($probe.Headers['X-Jenkins'] -join '') -notmatch '^\d+\.\d+(\.\d+)?$'){throw 'Jenkins endpoint not identified; no agent started'}
if($ValidateOnly){Write-Output 'AGENT_PREFLIGHT PASS java=21 jar_hash=verified secret_acl=restricted docker=local_pipe';return}
New-Item -ItemType Directory -Path $WorkDir -Force|Out-Null
foreach($entry in $temporaryPaths.GetEnumerator()){
    New-Item -ItemType Directory -Path $entry.Value -Force|Out-Null
    [Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')
}
$env:DOCKER_HOST=$null;$env:DOCKER_TLS_VERIFY=$null;$env:DOCKER_CERT_PATH=$null
& $java -jar $AgentJar -url $JenkinsUrl -name baotin-deploy-desktop -secret "@$SecretFile" -webSocket -workDir $WorkDir
exit $LASTEXITCODE
