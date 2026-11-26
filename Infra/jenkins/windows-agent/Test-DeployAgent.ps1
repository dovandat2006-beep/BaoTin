#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$AgentJar,[Parameter(Mandatory)][string]$JavaHome)
$ErrorActionPreference='Stop'
$temp=Join-Path $env:TEMP ('baotin-agent-check-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp|Out-Null
try{
    $secret=Join-Path $temp 'synthetic.secret';[IO.File]::WriteAllText($secret,'synthetic-test-only-not-a-node-secret')
    $acl=[Security.AccessControl.FileSecurity]::new();$acl.SetAccessRuleProtection($true,$false)
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','Allow'));Set-Acl -LiteralPath $secret -AclObject $acl
    $args=@{AgentJar=$AgentJar;AgentJarSha256=(Get-FileHash $AgentJar -Algorithm SHA256).Hash;SecretFile=$secret;WorkDir=(Join-Path $temp 'work');JavaHome=$JavaHome;ValidateOnly=$true}
    & (Join-Path $PSScriptRoot 'Start-DeployAgent.ps1') @args
    & (Join-Path $JavaHome 'bin/java.exe') --class-path $AgentJar (Join-Path $PSScriptRoot 'AgentSecretFileCheck.java') $secret
    if($LASTEXITCODE -ne 0){throw 'Actual agent.jar did not expand the secret file argument'}
    foreach($url in @('http://example.test:8080/','http://127.0.0.1:8080/?x=1','http://user@localhost:8080/','ftp://localhost:8080/')){
        $denied=$false;try{& (Join-Path $PSScriptRoot 'Start-DeployAgent.ps1') @args -JenkinsUrl $url}catch{if($_.Exception.Message -ne 'Local existing controller URL only'){throw};$denied=$true};if(-not $denied){throw 'Invalid controller URL accepted'}
    }
    $bad=$args.Clone();$bad.AgentJarSha256='0'*64;$denied=$false;try{& (Join-Path $PSScriptRoot 'Start-DeployAgent.ps1') @bad}catch{if($_.Exception.Message -ne 'agent.jar hash mismatch'){throw};$denied=$true};if(-not $denied){throw 'Wrong jar hash accepted'}
    foreach($broad in @('S-1-1-0','S-1-5-11','S-1-5-32-545','S-1-5-32-546')){
        $rule=[Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($broad),'Read','Allow');$acl.AddAccessRule($rule);Set-Acl -LiteralPath $secret -AclObject $acl
        $denied=$false;try{& (Join-Path $PSScriptRoot 'Start-DeployAgent.ps1') @args}catch{if($_.Exception.Message -ne 'Restrict agent-secret ACL before starting'){throw};$denied=$true};if(-not $denied){throw 'Broad SID could read node secret'}
        $acl.RemoveAccessRuleSpecific($rule);Set-Acl -LiteralPath $secret -AclObject $acl
    }
    if(Test-Path -LiteralPath $args.WorkDir){throw 'Validation started or provisioned an agent'}
    function Invoke-WebRequest {
        [CmdletBinding()]
        param($Uri,[switch]$NoProxy,$MaximumRedirection,[switch]$SkipHttpErrorCheck,$TimeoutSec)
        [pscustomobject]@{StatusCode=200;Headers=@{}}
    }
    $denied=$false;try{& (Join-Path $PSScriptRoot 'Start-DeployAgent.ps1') @args}catch{if($_.Exception.Message -ne 'Jenkins endpoint not identified; no agent started'){throw};$denied=$true}
    if(-not $denied -or (Test-Path -LiteralPath $args.WorkDir)){throw 'Agent trusted an unidentified local endpoint'}
    Write-Output 'AGENT_TESTS PASS checks=12 urls=restricted tampered_jar=denied broad_sids=denied secret_file=actual_parser endpoint=identified agent_started=false'
}finally{
    $resolved=(Resolve-Path -LiteralPath $temp).Path;if(-not $resolved.StartsWith([IO.Path]::GetFullPath($env:TEMP)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe test cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force
}
