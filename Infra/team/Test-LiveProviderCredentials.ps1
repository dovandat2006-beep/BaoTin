#Requires -Version 7.4
[CmdletBinding()]
param([string]$ConfigRoot=(Join-Path $env:USERPROFILE '.baotin'),[string]$OutputFile,[switch]$RequirePhoneProof,[string]$ProofDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$workspace=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$root=(Resolve-Path -LiteralPath $ConfigRoot).Path
if($root -eq $workspace -or $root.StartsWith($workspace+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Provider credentials must be external'}
if($OutputFile){$reportPath=[IO.Path]::GetFullPath($OutputFile);if([IO.Path]::GetExtension($reportPath) -ne '.json' -or $reportPath.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Provider report must be a JSON file outside credential root'}}
if($ProofDirectory){$proofPath=[IO.Path]::GetFullPath($ProofDirectory);if(-not $proofPath.StartsWith([IO.Path]::GetFullPath($env:TEMP)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Transient phone proof must be under operator TEMP'}}
function Field([string]$Name){$path=Join-Path $root "secrets/auth-config/$Name";if(-not(Test-Path -LiteralPath $path -PathType Leaf)){throw "Provider field missing: $Name"};return [IO.File]::ReadAllText($path).Trim()}
function Call([string]$Url,[hashtable]$Body,[hashtable]$Headers,[switch]$Json){
    $uri=[uri]$Url
    if($uri.Scheme -ne 'https' -or $uri.Host -notin @('oauth2.googleapis.com','graph.facebook.com','identitytoolkit.googleapis.com')){throw 'Provider endpoint must be official HTTPS'}
    $args=@{Uri=$Url;NoProxy=$true;MaximumRedirection=0;SkipHttpErrorCheck=$true;TimeoutSec=15}
    if($Body){$args.Method='POST';$args.Body=if($Json){$Body|ConvertTo-Json -Compress}else{$Body};if($Json){$args.ContentType='application/json'}}
    if($Headers){$args.Headers=$Headers}
    try{$response=Invoke-WebRequest @args -Verbose:$false -Debug:$false}catch{throw 'Provider transport failed; diagnostics withheld'}
    try{$content=if($response.Content -is [byte[]]){[Text.Encoding]::UTF8.GetString($response.Content)}else{$response.Content};$data=$content|ConvertFrom-Json}catch{throw 'Invalid provider response; diagnostics withheld'}
    return @{status=[int]$response.StatusCode;data=$data}
}
function Has($Object,[string]$Name){return $Object.PSObject.Properties.Name -contains $Name}
function B64([byte[]]$Bytes){[Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+','-').Replace('/','_')}
$id=Field 'auth.oauth.google.client-id';$secret=Field 'auth.oauth.google.client-secret';$callback=Field 'auth.oauth.google.callback-uri'
$form=@{grant_type='authorization_code';client_id=$id;client_secret=$secret;redirect_uri=$callback;code='baotin-deliberately-invalid-code-probe';code_verifier=('A'*43)}
$google=Call 'https://oauth2.googleapis.com/token' $form
$form.client_secret='baotin-deliberately-invalid-secret-probe';$negative=Call 'https://oauth2.googleapis.com/token' $form
$googleValid=$google.status -eq 400 -and (Has $google.data 'error') -and $google.data.error -eq 'invalid_grant' -and $negative.status -eq 401 -and $negative.data.error -eq 'invalid_client'
$facebook=Call 'https://graph.facebook.com/v25.0/oauth/access_token' @{grant_type='client_credentials';client_id=(Field 'auth.oauth.facebook.client-id');client_secret=(Field 'auth.oauth.facebook.client-secret')}
$facebookValid=$facebook.status -eq 200 -and (Has $facebook.data 'access_token') -and -not [string]::IsNullOrWhiteSpace($facebook.data.access_token)
$sa=Get-Content -Raw -LiteralPath (Join-Path $root 'firebase-dev-service-account.json')|ConvertFrom-Json
$client=Get-Content -Raw -LiteralPath (Join-Path $root 'google-services.json')|ConvertFrom-Json
if($client.project_info.project_id -ne $sa.project_id){throw 'Firebase client/server project mismatch'}
$rsa=[Security.Cryptography.RSA]::Create()
try{
    $rsa.ImportFromPem($sa.private_key);$now=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $header=B64 ([Text.Encoding]::UTF8.GetBytes('{"alg":"RS256","typ":"JWT"}'))
    $payload=B64 ([Text.Encoding]::UTF8.GetBytes((@{iss=$sa.client_email;scope='https://www.googleapis.com/auth/identitytoolkit https://www.googleapis.com/auth/cloud-platform';aud='https://oauth2.googleapis.com/token';iat=$now;exp=$now+300}|ConvertTo-Json -Compress)))
    $signed="$header.$payload";$signature=B64 ($rsa.SignData([Text.Encoding]::ASCII.GetBytes($signed),[Security.Cryptography.HashAlgorithmName]::SHA256,[Security.Cryptography.RSASignaturePadding]::Pkcs1))
    $access=Call 'https://oauth2.googleapis.com/token' @{grant_type='urn:ietf:params:oauth:grant-type:jwt-bearer';assertion="$signed.$signature"}
}finally{$rsa.Dispose()}
if($access.status -ne 200 -or -not(Has $access.data 'access_token')){throw 'Firebase service-account authentication failed'}
$config=Call ('https://identitytoolkit.googleapis.com/admin/v2/projects/'+$sa.project_id+'/config') $null @{Authorization="Bearer $($access.data.access_token)"}
if($config.status -ne 200){throw 'Firebase project configuration read failed'}
$phoneConfig=$config.data.signIn.phoneNumber
$entries=@(if(Has $phoneConfig 'testPhoneNumbers'){$phoneConfig.testPhoneNumbers.PSObject.Properties})
$phoneVerified=$false;$phoneStatus='not_attempted';$phoneError='';$billingRequired=$false;$regionBlocked=$false
if($phoneConfig.enabled -and $entries.Count -gt 0){
    $entry=$entries[0]
    if($entry.Name -notmatch '^\+[1-9][0-9]{7,14}$' -or [string]$entry.Value -notmatch '^[0-9]{6}$'){throw 'Invalid fictional phone test configuration'}
    $key=$client.client[0].api_key[0].current_key
    $clientConfig=Call ('https://identitytoolkit.googleapis.com/v1/projects?key='+$key)
    if($clientConfig.status -ne 200 -or [string]$clientConfig.data.projectId -ne [string]$client.project_info.project_number){throw 'Firebase client API key project mismatch'}
    # Only a number from the server-side fictional-number map may be requested. No real SMS.
    $send=Call ('https://identitytoolkit.googleapis.com/v1/accounts:sendVerificationCode?key='+$key) @{phoneNumber=$entry.Name;recaptchaToken='fake-token'} $null -Json
    $phoneStatus=[string]$send.status
    if($send.status -eq 200 -and (Has $send.data 'sessionInfo')){
        $login=Call ('https://identitytoolkit.googleapis.com/v1/accounts:signInWithPhoneNumber?key='+$key) @{sessionInfo=$send.data.sessionInfo;code=$entry.Value} $null -Json
        $phoneVerified=$login.status -eq 200 -and (Has $login.data 'idToken')
        $phoneStatus=[string]$login.status
        if($phoneVerified -and $ProofDirectory){
            New-Item -ItemType Directory -Path $proofPath -Force|Out-Null
            $acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetAccessRuleProtection($true,$false)
            foreach($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')){$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid),'FullControl','ContainerInherit,ObjectInherit','None','Allow'))}
            Set-Acl -LiteralPath $proofPath -AclObject $acl
            foreach($pair in @(@('id-token',$login.data.idToken),@('phone',$entry.Name),@('project',$sa.project_id))){[IO.File]::WriteAllText((Join-Path $proofPath $pair[0]),$pair[1])}
        }
    }elseif(Has $send.data 'error'){
        $message=[string]$send.data.error.message
        if($message -match '^([A-Z0-9_]+)'){$phoneError=$Matches[1]}
        $billingRequired=$message -match 'billing';$regionBlocked=$message -match 'region'
    }
}
$report=@{checkedAt=[DateTime]::UtcNow.ToString('o');google=@{credentialsRecognized=$googleValid;configuredStatus=$google.status;negativeSecretStatus=$negative.status;userConsentVerified=$false};facebook=@{appCredentialsRecognized=$facebookValid;status=$facebook.status;userConsentVerified=$false};firebase=@{serviceAccountRecognized=$true;projectRead=$true;clientProjectMatches=$true;fictionalNumbers=$entries.Count;phoneSignInTokenIssued=$phoneVerified;baotinPhoneBindingVerified=$false;phoneStatus=$phoneStatus;phoneError=$phoneError;billingRequired=$billingRequired;regionBlocked=$regionBlocked};secretsLogged=$false}
if($OutputFile){[IO.File]::WriteAllText($reportPath,($report|ConvertTo-Json -Depth 6))}
Write-Output "LIVE_PROVIDER_CREDENTIALS google=$googleValid facebook_app=$facebookValid firebase_service_account=true fictional_numbers=$($entries.Count) phone_signin=$phoneVerified phone_status=$phoneStatus phone_error=$phoneError billing_required=$billingRequired region_blocked=$regionBlocked secrets_logged=false"
if(-not $googleValid -or -not $facebookValid -or ($RequirePhoneProof -and -not $phoneVerified)){throw 'Live provider smoke incomplete; inspect only sanitized report'}
