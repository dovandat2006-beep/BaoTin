#Requires -Version 7.4
[CmdletBinding()]
param([string]$ConfigRoot=(Join-Path $env:USERPROFILE '.baotin'))
. (Join-Path $PSScriptRoot 'Team.Common.ps1')
$root=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$id=[guid]::NewGuid().ToString('N').Substring(0,8)
$network="baotin-firebase-check-$id";$pg="baotin-firebase-pg-$id";$auth="baotin-firebase-auth-$id";$gateway="baotin-firebase-gateway-$id";$redis="baotin-firebase-redis-$id"
$temp=Join-Path $env:TEMP "baotin-firebase-check-$id";New-Item -ItemType Directory $temp|Out-Null
$acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetAccessRuleProtection($true,$false)
foreach($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')){$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid),'FullControl','ContainerInherit,ObjectInherit','None','Allow'))}
Set-Acl -LiteralPath $temp -AclObject $acl
$config=Join-Path $temp 'phone';New-Item -ItemType Directory $config|Out-Null
try{
    $proof=Join-Path $temp 'proof'
    & (Join-Path $PSScriptRoot 'Test-LiveProviderCredentials.ps1') -ConfigRoot $ConfigRoot -ProofDirectory $proof -RequirePhoneProof|Out-Null
    $phone=[IO.File]::ReadAllText((Join-Path $proof 'phone'));$project=[IO.File]::ReadAllText((Join-Path $proof 'project'))
    foreach($pair in @(@('auth.firebase.enabled','true'),@('auth.firebase.project-id',$project),@('auth.firebase.test-phones',$phone),@('auth.firebase.credentials-path','/run/firebase/service-account.json'))){[IO.File]::WriteAllText((Join-Path $config $pair[0]),$pair[1])}
    $rsa=[Security.Cryptography.RSA]::Create(3072);try{[IO.File]::WriteAllText((Join-Path $temp 'private.pem'),$rsa.ExportPkcs8PrivateKeyPem())}finally{$rsa.Dispose()}
    [IO.File]::WriteAllBytes((Join-Path $temp 'encryption.bin'),[Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $bootstrap=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32));$runtime=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32));$migrator=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    Invoke-TeamDocker -Arguments @('network','create',$network)|Out-Null
    Invoke-TeamDocker -Arguments @('run','-d','--name',$pg,'--network',$network,'-e',"POSTGRES_PASSWORD=$bootstrap",'-e','POSTGRES_DB=baotin','postgres:17-alpine') -SecretBearing|Out-Null
    for($i=0;$i -lt 60;$i++){docker --context desktop-linux exec $pg pg_isready -U postgres -d baotin 2>$null|Out-Null;if($LASTEXITCODE -eq 0){break};Start-Sleep -Milliseconds 500}
    $sql=[IO.File]::ReadAllText((Join-Path $root 'Auth-Service/src/main/resources/db/provision/before-migrate.sql'))+"`nALTER ROLE baotin_auth PASSWORD '$runtime'; ALTER ROLE baotin_auth_migrator PASSWORD '$migrator';"
    $sql|docker --context desktop-linux exec -i $pg psql -v ON_ERROR_STOP=1 -U postgres -d baotin 2>$null|Out-Null;if($LASTEXITCODE -ne 0){throw 'Firebase fixture provisioning failed'}
    Invoke-TeamDocker -Arguments @('run','--rm','--network',$network,'--entrypoint','java','-e',"DB_URL=jdbc:postgresql://${pg}:5432/baotin",'-e','DB_MIGRATION_USER=baotin_auth_migrator','-e',"DB_MIGRATION_PASSWORD=$migrator",'baotin/auth-service:release-check','-Dloader.main=com.baotin.auth.operator.DatabaseMigration','-cp','/app/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher') -SecretBearing|Out-Null
    [IO.File]::ReadAllText((Join-Path $root 'Auth-Service/src/main/resources/db/provision/after-migrate.sql'))|docker --context desktop-linux exec -i $pg psql -v ON_ERROR_STOP=1 -U postgres -d baotin 2>$null|Out-Null;if($LASTEXITCODE -ne 0){throw 'Firebase fixture grants failed'}
    Invoke-TeamDocker -Arguments @('run','-d','--name',$redis,'--network',$network,'redis:7-alpine')|Out-Null
    $serviceAccount=(Resolve-Path -LiteralPath (Join-Path $ConfigRoot 'firebase-dev-service-account.json')).Path
    Invoke-TeamDocker -Arguments @('run','-d','--name',$auth,'--network',$network,'--mount',"type=bind,source=$temp,target=/run/fixture,readonly",'--mount',"type=bind,source=$serviceAccount,target=/run/firebase/service-account.json,readonly",
        '-e','SPRING_PROFILES_ACTIVE=dev','-e','SPRING_CONFIG_IMPORT=configtree:/run/fixture/phone/',
        '-e',"DB_URL=jdbc:postgresql://${pg}:5432/baotin",'-e','DB_USER=baotin_auth','-e',"DB_PASSWORD=$runtime",'-e','DB_MIGRATION_ENABLED=false',
        '-e','JWT_PRIVATE_KEY_PATH=/run/fixture/private.pem','-e','JWT_KEY_ID=firebase-fixture','-e','AUTH_ENCRYPTION_KEY_PATH=/run/fixture/encryption.bin','baotin/auth-service:release-check') -SecretBearing|Out-Null
    Invoke-TeamDocker -Arguments @('run','-d','--name',$gateway,'--network',$network,'-p','127.0.0.1::8080','-e',"AUTH_SERVICE_URI=http://${auth}:8080",'-e',"AUTH_JWKS_URI=http://${auth}:8080/.well-known/jwks.json",'-e',"REDIS_HOST=$redis",'baotin/api-gateway:release-check')|Out-Null
    $port=(Invoke-TeamDocker -Arguments @('port',$gateway,'8080/tcp')).Trim().Split(':')[-1];$url="http://127.0.0.1:$port";$health=$null
    for($i=0;$i -lt 90;$i++){try{$health=Invoke-RestMethod "$url/actuator/health" -NoProxy -TimeoutSec 2;if($health.status -eq 'UP'){break}}catch{};Start-Sleep -Seconds 1}
    if(-not $health -or $health.status -ne 'UP'){throw 'Firebase fixture Gateway health failed'}
    function Request([string]$Path,[string]$Method,[hashtable]$Body,[string]$Access){
        $args=@{Uri=($url+$Path);Method=$Method;NoProxy=$true;SkipHttpErrorCheck=$true;TimeoutSec=20}
        if($Body){$args.ContentType='application/json';$args.Body=$Body|ConvertTo-Json -Compress}
        if($Access){$args.Headers=@{Authorization="Bearer $Access"}}
        try{return (Invoke-WebRequest @args)}catch{throw 'Firebase fixture HTTP transport failed; diagnostics withheld'}
    }
    function Json($Response){$text=if($Response.Content -is [byte[]]){[Text.Encoding]::UTF8.GetString($Response.Content)}else{$Response.Content};return ($text|ConvertFrom-Json)}
    $anonymous=Request '/api/v1/auth/me' 'GET';if([int]$anonymous.StatusCode -ne 401){throw 'Firebase fixture anonymous authorization failed'}
    $password=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24));$user="phone$id"
    $registration=Request '/api/v1/auth/register' 'POST' @{username=$user;phone=$phone;password=$password;displayName='Phone fixture'}
    if([int]$registration.StatusCode -ne 201){throw 'Live phone registration failed'};$profile=Json $registration
    $before=Request '/api/v1/auth/login' 'POST' @{identifier=$phone;password=$password};if([int]$before.StatusCode -ne 401){throw 'Unverified phone identifier authenticated'}
    $response=Request '/api/v1/auth/login' 'POST' @{identifier=$user;password=$password};if([int]$response.StatusCode -ne 200){throw 'Limited username login failed'};$login=Json $response
    $response=Request '/api/v1/auth/phone-verifications' 'POST' @{phone=$phone;password=$password} $login.tokens.accessToken
    if([int]$response.StatusCode -ne 200){throw 'Live phone challenge failed'};$challenge=(Json $response).challenge
    & (Join-Path $PSScriptRoot 'Test-LiveProviderCredentials.ps1') -ConfigRoot $ConfigRoot -ProofDirectory $proof -RequirePhoneProof|Out-Null
    $firebaseToken=[IO.File]::ReadAllText((Join-Path $proof 'id-token'))
    $other="other$id";$response=Request '/api/v1/auth/register' 'POST' @{username=$other;email="$other@example.test";password=$password;displayName='Other fixture'}
    if([int]$response.StatusCode -ne 201){throw 'Other-owner fixture registration failed'}
    $response=Request '/api/v1/auth/login' 'POST' @{identifier=$other;password=$password};$otherToken=(Json $response).tokens.accessToken
    $wrong=Request '/api/v1/auth/verify-phone' 'POST' @{challenge=$challenge;idToken=$firebaseToken} $otherToken
    if([int]$wrong.StatusCode -ne 401){throw 'Live Firebase proof bound to wrong owner'}
    $confirmed=Request '/api/v1/auth/verify-phone' 'POST' @{challenge=$challenge;idToken=$firebaseToken} $login.tokens.accessToken
    if([int]$confirmed.StatusCode -ne 204){throw 'Actual Firebase Admin SDK/BảoTin phone proof failed'}
    $revoked=Request '/api/v1/auth/me' 'GET' $null $login.tokens.accessToken;if([int]$revoked.StatusCode -ne 401){throw 'Phone verification did not revoke old session'}
    $response=Request '/api/v1/auth/login' 'POST' @{identifier=$phone;password=$password};if([int]$response.StatusCode -ne 200){throw 'Verified phone login failed'};$verified=Json $response
    $response=Request '/api/v1/auth/me' 'GET' $null $verified.tokens.accessToken;$me=Json $response
    if(-not $me.phoneVerified -or $me.role -ne 'USER' -or $me.id -ne $profile.id){throw 'Live phone profile/role mismatch'}
    $response=Request '/api/v1/auth/phone-verifications' 'POST' @{phone=$phone;password=$password} $verified.tokens.accessToken
    $replay=Request '/api/v1/auth/verify-phone' 'POST' @{challenge=(Json $response).challenge;idToken=$firebaseToken} $verified.tokens.accessToken
    if([int]$replay.StatusCode -ne 401 -or (Json $replay).code -ne 'PHONE_PROOF_REUSED'){throw 'Firebase auth event replay accepted'}
    Write-Output 'FIREBASE_BINDING PASS real_admin_sdk=true signature_project_revocation=verified fictional_phone=true wrong_owner=401 verify=204 old_session=401 phone_login=200 role=USER event_replay=401 real_sms_sent=false secrets_logged=false'
}finally{
    foreach($container in @($gateway,$auth,$redis,$pg)){try{Invoke-TeamDocker -Arguments @('rm','-f','-v',$container)|Out-Null}catch{}}
    try{Invoke-TeamDocker -Arguments @('network','rm',$network)|Out-Null}catch{}
    $resolved=(Resolve-Path $temp).Path;if(-not $resolved.StartsWith([IO.Path]::GetFullPath($env:TEMP)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe Firebase fixture cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force
}
