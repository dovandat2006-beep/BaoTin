#Requires -Version 7.4
[CmdletBinding()]
param([string]$Python='C:\Users\ngoan\AppData\Roaming\uv\tools\graphifyy\Scripts\python.exe',
      [string]$ValidatorScript=(Join-Path $PSScriptRoot 'Test-JenkinsPipelines.ps1'))
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$temp=Join-Path $env:TEMP ('baotin-jenkins-validation-'+[guid]::NewGuid().ToString('N'))
$fixtureDir=Join-Path $root ('.changes\jenkins-validation-fixture-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp,$fixtureDir|Out-Null
$process=$null;$count=0
try{
    $token=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    $secret=Join-Path $temp 'synthetic.token';[IO.File]::WriteAllText($secret,$token)
    $acl=[Security.AccessControl.FileSecurity]::new();$acl.SetAccessRuleProtection($true,$false)
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','Allow'));Set-Acl -LiteralPath $secret -AclObject $acl
    $portFile=Join-Path $temp 'port.txt'
    $process=Start-Process -FilePath $Python -ArgumentList @((Join-Path $PSScriptRoot 'validation-fixture.py'),$secret,$portFile) -WindowStyle Hidden -PassThru
    for($i=0;$i -lt 40 -and -not(Test-Path $portFile);$i++){if($process.HasExited){throw 'Fixture stopped early'};Start-Sleep -Milliseconds 100}
    $fixtureBase='http://127.0.0.1:'+([IO.File]::ReadAllText($portFile))
    # Preserve core URL validation and HTTP options, but route synthetic credentials to
    # our ephemeral HTTP fixture rather than the user's actual localhost controller.
    function Invoke-WebRequest {
        [CmdletBinding()]
        param($Uri,$Method='GET',$Body,$Headers,$Credential,$Authentication,$WebSession,
            [switch]$AllowUnencryptedAuthentication,[switch]$NoProxy,$MaximumRedirection,
            [switch]$SkipHttpErrorCheck,$TimeoutSec)
        if(-not $Uri.StartsWith('http://127.0.0.1:8080/') -and -not $Uri.StartsWith('http://[::1]:8080/')){throw 'Fixture routing boundary mismatch'}
        $args=@{};foreach($key in $PSBoundParameters.Keys){$args[$key]=$PSBoundParameters[$key]}
        $args.Uri=$Uri.Replace('http://127.0.0.1:8080/',$fixtureBase+'/').Replace('http://[::1]:8080/',$fixtureBase+'/')
        Microsoft.PowerShell.Utility\Invoke-WebRequest @args
    }
    $args=@{Username='fixture-user';TokenFile=$secret;OutputFile=(Join-Path $temp 'result.json')}
    $output=& $ValidatorScript @args
    if(($output -join '') -notmatch 'JENKINS_VALIDATION PASS pipelines=3' -or ($output -join '').Contains($token)){throw 'Positive authenticated fixture failed or logged token'};$count++
    $result=Get-Content -Raw $args.OutputFile|ConvertFrom-Json
    if(-not $result.validated -or $result.pipelines.Count -ne 3 -or [IO.File]::ReadAllText($args.OutputFile).Contains($token)){throw 'Validation artifact leaked token or missed a pipeline'};$count++
    if(($result.pipelines.pipeline -join ',') -ne 'Auth-Service/Jenkinsfile,API-Gateway/Jenkinsfile,Infra/team/Jenkinsfile'){throw 'Pipeline artifact paths are not canonical'}
    foreach($mode in @('invalid','unauthorized','wrong-identity','redirect')){
        $null=Microsoft.PowerShell.Utility\Invoke-WebRequest "$fixtureBase/fixture-mode?value=$mode"
        $failed=$false;try{& $ValidatorScript @args|Out-Null}catch{if($_.Exception.Message.Contains($token)){throw 'Server diagnostics leaked token'};$failed=$true}
        if(-not $failed){throw "Failure fixture accepted: $mode"};$count++
        if((Get-Content -Raw $args.OutputFile|ConvertFrom-Json).validated){throw 'Failed validation retained stale success artifact'}
    }
    $null=Microsoft.PowerShell.Utility\Invoke-WebRequest "$fixtureBase/fixture-mode?value=no-crumb"
    & $ValidatorScript @args|Out-Null;$count++
    $null=Microsoft.PowerShell.Utility\Invoke-WebRequest "$fixtureBase/fixture-mode?value=success"
    foreach($url in @('http://example.test:8080/','http://user@127.0.0.1:8080/','http://127.0.0.1:8080/?x=1','http://127.0.0.1:8081/')){
        $failed=$false;try{& $ValidatorScript @args -JenkinsUrl $url|Out-Null}catch{$failed=$true};if(-not $failed){throw 'Noncanonical controller URL accepted'};$count++
    }
    $rule=[Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new('S-1-1-0'),'Read','Allow');$acl.AddAccessRule($rule);Set-Acl $secret $acl
    $failed=$false;try{& $ValidatorScript @args|Out-Null}catch{$failed=$true};if(-not $failed){throw 'Broad token ACL accepted'};$count++
    $acl.RemoveAccessRuleSpecific($rule);Set-Acl $secret $acl
    $inside=Join-Path $fixtureDir 'synthetic.token';[IO.File]::WriteAllText($inside,$token);Set-Acl $inside $acl
    $bad=$args.Clone();$bad.TokenFile=$inside;$failed=$false;try{& $ValidatorScript @bad|Out-Null}catch{$failed=$true};if(-not $failed){throw 'Workspace token accepted'};$count++
    $stats=Microsoft.PowerShell.Utility\Invoke-RestMethod "$fixtureBase/fixture-stats"
    if($stats.tokenInPipeline -or -not $stats.crumbValid -or $stats.validated -ne 6){throw 'Fixture request assertions failed'};$count++
    $bad=$args.Clone();$bad.OutputFile=$secret;$failed=$false;try{& $ValidatorScript @bad|Out-Null}catch{$failed=$true};if(-not $failed -or [IO.File]::ReadAllText($secret) -ne $token){throw 'Validation report could overwrite token file'};$count++
    $null=Microsoft.PowerShell.Utility\Invoke-WebRequest "$fixtureBase/fixture-mode?value=not-jenkins"
    $failed=$false;try{& $ValidatorScript @args|Out-Null}catch{$failed=$true}
    if(-not $failed){throw 'Unidentified local service accepted as Jenkins'};$count++
    $stats=Microsoft.PowerShell.Utility\Invoke-RestMethod "$fixtureBase/fixture-stats"
    if($stats.credentialsToUnidentifiedService){throw 'Credentials sent before Jenkins endpoint identification'};$count++
    Write-Output "JENKINS_VALIDATION_TESTS PASS checks=$count http_fixture=true token_logged=false invalid_pipeline=denied redirects=denied external_acl=required controller_changed=false"
}finally{
    if($process -and -not $process.HasExited){Stop-Process -Id $process.Id -Force}
    foreach($path in @($temp,$fixtureDir)){
        $resolved=(Resolve-Path $path).Path;$boundary=if($path -eq $temp){[IO.Path]::GetFullPath($env:TEMP)}else{Join-Path $root '.changes'}
        if(-not $resolved.StartsWith($boundary+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe fixture cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
