#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$EnvFile,[Parameter(Mandatory)][string]$OutputFile,
      [switch]$TestRollback,
      [string]$DockerContext=((& docker context show).Trim()))
$ErrorActionPreference='Stop'
$cfg=@{};foreach($line in Get-Content -LiteralPath $EnvFile){if($line -match '^([A-Z][A-Z0-9_]*)=(.*)$'){$cfg[$Matches[1]]=$Matches[2]}}
$base='http://127.0.0.1:'+$cfg.WEB_PORT
$userId=$null;$username='deploy'+[guid]::NewGuid().ToString('N').Substring(0,16)
function Request-Api($Path,$Body,$Token,$Extra=@{}){
    $script:lastRequestPath=$Path
    $headers=@{};foreach($key in $Extra.Keys){$headers[$key]=$Extra[$key]};if($Token){$headers.Authorization='Bearer '+$Token}
    $args=@{Uri=$base+$Path;NoProxy=$true;TimeoutSec=20;SkipHttpErrorCheck=$true;MaximumRedirection=0;Headers=$headers}
    if($null -ne $Body){$args.Method='POST';$args.ContentType='application/json';$args.Body=$Body|ConvertTo-Json -Compress}else{$args.Method='GET'}
    $response=Invoke-WebRequest @args
    $content=if($response.Content -is [byte[]]){[Text.Encoding]::UTF8.GetString($response.Content)}else{[string]$response.Content}
    return [pscustomobject]@{StatusCode=$response.StatusCode;Headers=$response.Headers;Content=$content}
}
function Fixture-Sql([string]$Sql){
    $out=$Sql | & docker --context $DockerContext exec -i baotin-deps-postgres-1 sh -c 'PGPASSWORD="$(cat /run/secrets/postgres-password)" psql -h 127.0.0.1 -U postgres -d baotin -At -v ON_ERROR_STOP=1' 2>&1
    if($LASTEXITCODE){throw 'Owned fixture SQL failed; raw diagnostics withheld'}
    return (($out|ForEach-Object{"$_"}) -join "`n").Trim()
}
function Require($Response,[int]$Status){if($Response.StatusCode -ne $Status){
    $code='unknown';try{$problem=$Response.Content|ConvertFrom-Json;if($problem.code -match '^[A-Z0-9_]+$'){$code=$problem.code}}catch{}
    throw "Runtime assertion failed path=$script:lastRequestPath expected_status=$Status observed_status=$($Response.StatusCode) code=$code"
}}
function Decode-Envelope([string]$Hex){
    $bytes=[Convert]::FromHexString($Hex);$key=[IO.File]::ReadAllBytes((Join-Path $cfg.BAOTIN_SECRETS_DIR 'auth-keys/encryption.bin'))
    if([Text.Encoding]::ASCII.GetString($bytes,0,4) -ne 'BK1:'){throw 'Expected authenticated envelope'}
    $offset=5+[int]$bytes[4];$header=$bytes[0..($offset-1)];$nonce=$bytes[$offset..($offset+11)]
    $count=$bytes.Length-$offset-12-16;$cipher=$bytes[($offset+12)..($bytes.Length-17)];$tag=$bytes[($bytes.Length-16)..($bytes.Length-1)]
    $plain=[byte[]]::new($count);$aes=[Security.Cryptography.AesGcm]::new($key,16)
    try{$aes.Decrypt([byte[]]$nonce,[byte[]]$cipher,[byte[]]$tag,$plain,[byte[]]$header);return [Text.Encoding]::UTF8.GetString($plain)}finally{$aes.Dispose()}
}
function Totp([string]$Base32,[long]$Step){
    $alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';$result=[Collections.Generic.List[byte]]::new();$bits=0;$acc=0
    foreach($c in $Base32.ToCharArray()){$n=$alphabet.IndexOf($c);if($n -lt 0){throw 'Invalid factor encoding'};$acc=($acc -shl 5) -bor $n;$bits+=5;if($bits -ge 8){$bits-=8;$result.Add([byte](($acc -shr $bits) -band 255))}}
    $counter=[BitConverter]::GetBytes($Step);[Array]::Reverse($counter)
    $mac=[Security.Cryptography.HMACSHA1]::new([byte[]]$result.ToArray());try{$digest=$mac.ComputeHash($counter)}finally{$mac.Dispose()}
    $p=$digest[$digest.Length-1] -band 15
    $value=((([int]$digest[$p]) -band 127) -shl 24) -bor (([int]$digest[$p+1]) -shl 16) -bor (([int]$digest[$p+2]) -shl 8) -bor ([int]$digest[$p+3])
    return ($value%1000000).ToString('D6',[Globalization.CultureInfo]::InvariantCulture)
}
try {
    $web=Request-Api '/' $null $null;Require $web 200
    if($web.Headers['X-Frame-Options'] -ne 'DENY'){throw 'Missing frame isolation'}
    Require (Request-Api '/internal/v1/inference' $null $null) 404
    Require (Request-Api '/actuator/env' $null $null) 404
    $email=[IO.File]::ReadAllText((Join-Path $cfg.BAOTIN_SECRETS_DIR 'auth-config/spring.mail.username')).Trim()
    if($email -notmatch '^[^\s@]+@[^\s@]+$'){throw 'Use the configured sender-self mailbox for this owned fixture'}
    if((Fixture-Sql "SELECT count(*) FROM auth.users WHERE email='$(($email.ToLowerInvariant()).Replace("'","''"))'") -ne '0'){throw 'Sender mailbox already has an account; preserve it and use a separately configured test identity'}
    $password=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $signup=Request-Api '/api/v1/auth/register' @{username=$username;email=$email;password=$password;displayName='Deployment verification'} $null;Require $signup 201
    $profile=$signup.Content|ConvertFrom-Json;$userId=[guid]::Parse($profile.id).ToString()
    # Read only the newly created fixture's own latest encrypted mail; not another user's proof.
    # This is operator-controlled outbox proof, not a claim of mailbox/browser consumption.
    $hex=Fixture-Sql 'SELECT encode(encrypted_payload,''hex'') FROM auth.mail_outbox WHERE encrypted_payload IS NOT NULL ORDER BY created_at DESC LIMIT 1'
    if(-not $hex){throw 'Fixture proof already delivered; consume it through the sender mailbox rather than bypass verification'}
    $payload=Decode-Envelope $hex
    if(-not $payload.StartsWith($email+"`nBaoTin VERIFY_EMAIL`n") -or $payload -notmatch ': ([A-Za-z0-9_-]{43})$'){throw 'Outbox proof does not belong to this fixture'}
    Require (Request-Api '/api/v1/auth/verify-email' @{token=$Matches[1]} $null) 204;$payload=$null;$hex=$null
    $login=Request-Api '/api/v1/auth/login' @{identifier=$username;password=$password} $null;Require $login 200
    $tokens=($login.Content|ConvertFrom-Json).tokens
    Require (Request-Api '/api/v1/auth/me' $null $tokens.accessToken) 200
    Require (Request-Api '/api/v1/auth/admin/users' $null $tokens.accessToken @{'X-Roles'='ADMIN';'X-User-Id'='admin'}) 403
    $enroll=Request-Api '/api/v1/auth/mfa/enrollments' @{password=$password} $tokens.accessToken;Require $enroll 200
    $secret=($enroll.Content|ConvertFrom-Json).secret;$step=[long][math]::Floor([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()/30)
    $confirm=Request-Api '/api/v1/auth/mfa/enrollments/confirmation' @{code=(Totp $secret $step)} $tokens.accessToken;Require $confirm 200
    $recovery=($confirm.Content|ConvertFrom-Json).recoveryCodes
    Require (Request-Api '/api/v1/auth/me' $null $tokens.accessToken) 401
    $challenge=Request-Api '/api/v1/auth/login' @{identifier=$username;password=$password} $null;Require $challenge 200
    $proof=$challenge.Content|ConvertFrom-Json;if(-not $proof.mfaRequired -or $proof.tokens){throw 'MFA requirement bypassed'}
    $factor=Request-Api '/api/v1/auth/mfa/challenges' @{challenge=$proof.challenge;code=$recovery[0]} $null;Require $factor 200
    $tokens=$factor.Content|ConvertFrom-Json
    Require (Request-Api '/api/v1/auth/mfa/challenges' @{challenge=$proof.challenge;code=$recovery[1]} $null) 401
    $rollbackPassed=$false
    if($TestRollback){
        $overlay=Join-Path $env:TEMP ('baotin-alt-fault-'+[guid]::NewGuid().ToString('N')+'.yml')
        [IO.File]::WriteAllText($overlay,"services:`n  api-gateway:`n    environment:`n      GATEWAY_ALLOWED_ORIGINS: invalid-origin`n")
        $compose=(Join-Path $PSScriptRoot 'docker-compose.yml')
        try{
            $out=& docker --context $DockerContext compose --project-name baotin-alt --env-file $EnvFile -f $compose -f $overlay up -d --pull never --wait --wait-timeout 20 api-gateway 2>&1
            $candidateExit=$global:LASTEXITCODE
            $candidate=(& docker --context $DockerContext inspect baotin-alt-api-gateway-1)|ConvertFrom-Json
            if('GATEWAY_ALLOWED_ORIGINS=invalid-origin' -notin $candidate.Config.Env){throw 'Fault overlay was not applied to the owned candidate'}
            if($candidateExit -eq 0 -and $candidate.State.Health.Status -eq 'healthy'){throw 'Invalid candidate unexpectedly became healthy'}
            $failure=Request-Api '/api/v1/auth/me' $null $tokens.accessToken;Require $failure 503
            $problem=$failure.Content|ConvertFrom-Json
            $correlationGuid=[guid]::Empty
            if($problem.code -ne 'UPSTREAM_UNAVAILABLE' -or -not [guid]::TryParse($problem.correlationId,[ref]$correlationGuid)){throw 'Ingress failure must be generic correlated Problem Details'}
        }finally{
            $out=& docker --context $DockerContext compose --project-name baotin-alt --env-file $EnvFile -f $compose up -d --pull never --wait --wait-timeout 120 api-gateway 2>&1
            if($LASTEXITCODE){throw 'Restored Gateway did not become healthy'}
            Remove-Item -LiteralPath $overlay -ErrorAction SilentlyContinue
        }
        Require (Request-Api '/api/v1/auth/me' $null $tokens.accessToken) 200
        $rollbackPassed=$true
        'ALT_RUNTIME_ROLLBACK PASS invalid_candidate=unhealthy ingress=503 previous_config=restored session=preserved database=unchanged keys=unchanged'
    }
    $refresh=Request-Api '/api/v1/auth/refresh' @{refreshToken=$tokens.refreshToken} $null;Require $refresh 200
    $rotated=$refresh.Content|ConvertFrom-Json
    if($rotated.refreshToken -eq $tokens.refreshToken){throw 'Refresh did not rotate'}
    Require (Request-Api '/api/v1/auth/refresh' @{refreshToken=$tokens.refreshToken} $null) 401
    Require (Request-Api '/api/v1/auth/me' $null $rotated.accessToken) 401
    $result=@{web=200;profile=200;forgedAdmin=403;emailProof='owned_outbox';mfaConfirmed=$true;oldSession=401;factorReplay=401;refreshRotated=$true;reuseRevoked=$true;rollbackTested=$rollbackPassed;tokensLogged=$false;userDeactivated=$false}
    [IO.File]::WriteAllText($OutputFile,($result|ConvertTo-Json))
    'ALT_LIVE_RUNTIME PASS web=200 jwt=USER admin_headers=403 mfa=confirmed old_session=401 factor_replay=401 refresh=rotated reuse=revoked secrets_logged=false'
} finally {
    if($userId){
        Fixture-Sql "BEGIN;UPDATE auth.refresh_sessions SET revoked_at=now() WHERE user_id='$userId';UPDATE auth.users SET status='DISABLED',email='$username@fixture.invalid',email_verified=false,password_hash=NULL,mfa_secret=NULL,mfa_enabled=false,security_version=security_version+1 WHERE id='$userId' AND username='$username';COMMIT;"|Out-Null
        if(Test-Path -LiteralPath $OutputFile){$r=Get-Content $OutputFile -Raw|ConvertFrom-Json -AsHashtable;$r.userDeactivated=$true;[IO.File]::WriteAllText($OutputFile,($r|ConvertTo-Json))}
        'ALT_OWNED_ACCOUNT_CLEANUP PASS generated_identity=disabled sender_contact=removed sessions=revoked security_audit=retained'
    }
}
