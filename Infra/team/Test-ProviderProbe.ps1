#Requires -Version 7.4
[CmdletBinding()]
param([string]$ProbeScript=(Join-Path $PSScriptRoot 'Test-LiveProviderCredentials.ps1'))
$ErrorActionPreference='Stop'
$temp=Join-Path $env:TEMP ('baotin-provider-probe-'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory (Join-Path $temp 'secrets/auth-config') -Force|Out-Null
$rsa=[Security.Cryptography.RSA]::Create(2048);$count=0;$mode='success'
try{
    foreach($pair in @(@('auth.oauth.google.client-id','fixture-google'),@('auth.oauth.google.client-secret','fixture-secret'),@('auth.oauth.google.callback-uri','https://fixture.example/google'),@('auth.oauth.facebook.client-id','fixture-facebook'),@('auth.oauth.facebook.client-secret','fixture-facebook-secret'))){[IO.File]::WriteAllText((Join-Path $temp "secrets/auth-config/$($pair[0])"),$pair[1])}
    [IO.File]::WriteAllText((Join-Path $temp 'firebase-dev-service-account.json'),(@{project_id='fixture-project';client_email='fixture@example.test';private_key=$rsa.ExportPkcs8PrivateKeyPem()}|ConvertTo-Json))
    [IO.File]::WriteAllText((Join-Path $temp 'google-services.json'),(@{project_info=@{project_id='fixture-project';project_number='123'};client=@(@{api_key=@(@{current_key='fixture-public-key'})})}|ConvertTo-Json -Depth 6))
    function Invoke-WebRequest {
        [CmdletBinding()]
        param($Uri,$Body,$Method,$Headers,$ContentType,[switch]$NoProxy,$MaximumRedirection,[switch]$SkipHttpErrorCheck,$TimeoutSec)
        $url=[uri]$Uri;$data=$null;$status=200
        if($url.Host -notin @('oauth2.googleapis.com','graph.facebook.com','identitytoolkit.googleapis.com') -or -not $NoProxy -or $MaximumRedirection -ne 0){throw 'Transport options not bounded'}
        if($url.Host -eq 'oauth2.googleapis.com'){
            if($Body.grant_type -eq 'urn:ietf:params:oauth:grant-type:jwt-bearer'){$data=@{access_token='synthetic-service-token'}}
            elseif($Body.client_secret -eq 'fixture-secret'){$status=400;$data=@{error='invalid_grant'}}else{$status=401;$data=@{error='invalid_client'}}
        }elseif($url.Host -eq 'graph.facebook.com'){$data=@{access_token='synthetic-app-token'}}
        elseif($url.AbsolutePath.EndsWith('/config')){$data=@{signIn=@{phoneNumber=@{enabled=$true;testPhoneNumbers=@{'+84900000001'='123456'}}}}}
        elseif($url.AbsolutePath -eq '/v1/projects'){$data=@{projectId='123'}}
        elseif($url.AbsolutePath.EndsWith('sendVerificationCode')){
            $payload=$Body|ConvertFrom-Json;if($payload.phoneNumber -ne '+84900000001'){throw 'Non-test number requested'}
            if($mode -eq 'region'){$status=400;$data=@{error=@{message='OPERATION_NOT_ALLOWED: SMS region policy fixture'}}}else{$data=@{sessionInfo='synthetic-session'}}
        }elseif($url.AbsolutePath.EndsWith('signInWithPhoneNumber')){$data=@{idToken='synthetic-id-token'}}
        else{throw 'Unexpected provider route'}
        [pscustomobject]@{StatusCode=$status;Content=($data|ConvertTo-Json -Depth 8 -Compress)}
    }
    $output=Join-Path $env:TEMP ('baotin-provider-result-'+[guid]::NewGuid().ToString('N')+'.json')
    $text=& $ProbeScript -ConfigRoot $temp -OutputFile $output -RequirePhoneProof
    if(($text -join '') -notmatch 'phone_signin=True' -or ($text -join '').Contains('synthetic-id-token')){throw 'Positive probe fixture failed or leaked token'};$count++
    $report=Get-Content -Raw $output|ConvertFrom-Json
    if($report.firebase.fictionalNumbers -ne 1 -or -not $report.firebase.phoneSignInTokenIssued -or $report.firebase.baotinPhoneBindingVerified -or [IO.File]::ReadAllText($output).Contains('synthetic-service-token')){throw 'Probe report leak or single-entry parsing failed'};$count++
    $proof=Join-Path $temp 'proof'; & $ProbeScript -ConfigRoot $temp -ProofDirectory $proof -RequirePhoneProof|Out-Null
    if([IO.File]::ReadAllText((Join-Path $proof 'id-token')) -ne 'synthetic-id-token' -or [IO.File]::ReadAllText((Join-Path $proof 'phone')) -ne '+84900000001'){throw 'Transient proof material incorrect'};$count++
    $allowed=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')
    foreach($rule in (Get-Acl (Join-Path $proof 'id-token')).Access){if($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin $allowed){throw 'Transient proof ACL too broad'}};$count++
    $mode='region';$failed=$false;try{& $ProbeScript -ConfigRoot $temp -OutputFile $output -RequirePhoneProof|Out-Null}catch{$failed=$true}
    if(-not $failed){throw 'Region-blocked phone proof accepted'};$count++
    $report=Get-Content -Raw $output|ConvertFrom-Json;if(-not $report.firebase.regionBlocked -or $report.firebase.phoneSignInTokenIssued -or $report.firebase.billingRequired){throw 'Region/billing classification incorrect'};$count++
    $failed=$false;try{& $ProbeScript -ConfigRoot $temp -OutputFile (Join-Path $temp 'firebase-dev-service-account.json')|Out-Null}catch{$failed=$true}
    if(-not $failed){throw 'Report could overwrite credentials'};$count++
    Write-Output "PROVIDER_PROBE_TESTS PASS checks=$count single_test_number=true secrets_logged=false region_failure=denied report_overwrite=denied"
}finally{
    $rsa.Dispose();if($output -and (Test-Path $output)){Remove-Item -LiteralPath $output -Force}
    $resolved=(Resolve-Path $temp).Path;if(-not $resolved.StartsWith([IO.Path]::GetFullPath($env:TEMP)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe probe cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force
}
