#Requires -Version 7.4
[CmdletBinding()]
param([switch]$SkipBuild,[switch]$TestTailnet,[string]$HostProxyIpv6Cidr='')
. (Join-Path $PSScriptRoot 'Team.Common.ps1')
if($HostProxyIpv6Cidr){Assert-HostProxyIpv6 $HostProxyIpv6Cidr}
$root=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if(Invoke-TeamDocker -Arguments @('ps','-aq','--filter','label=com.docker.compose.project=baotin-staging')){throw 'Existing staging project must not be touched by tests'}
if(Get-NetTCPConnection -State Listen -LocalPort 8090 -ErrorAction SilentlyContinue){throw 'Test loopback port is already occupied'}
$temp=Join-Path $env:TEMP ('baotin-release-test-'+[Guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $temp|Out-Null
$registry='baotin-release-registry-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$envFile=Join-Path $temp 'team.env';$state=Join-Path $temp 'state';$secretRoot=Join-Path $temp 'secrets'
$allowedOrigins='https://localhost:9444'
if($TestTailnet){$status=tailscale status --json|ConvertFrom-Json;if($status.BackendState -ne 'Running'){throw 'Live Tailnet test requires Running Tailscale'};$allowedOrigins="https://$($status.Self.DNSName.TrimEnd('.')):9444"}
function New-TestPassword {[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).TrimEnd('=').Replace('+','-').Replace('/','_')}
function Write-Secret([string]$Rel,[string]$Value){$p=Join-Path $secretRoot $Rel;New-Item -ItemType Directory -Path (Split-Path $p -Parent) -Force|Out-Null;[IO.File]::WriteAllText($p,$Value)}
function Push-TestImage([string]$Tag,[string]$Repo){
    Invoke-TeamDocker -Arguments @('tag',$Tag,$Repo)|Out-Null;Invoke-TeamDocker -Arguments @('push',$Repo)|Out-Null
    $prefix=$Repo.Substring(0,$Repo.LastIndexOf(':'))+'@sha256:'
    $refs=(Invoke-TeamDocker -Arguments @('inspect',$Repo,'--format','{{json .RepoDigests}}'))|ConvertFrom-Json
    $ref=$refs|Where-Object {$_.StartsWith($prefix)}|Select-Object -First 1
    if(-not $ref){throw 'Current fixture registry did not receive the image digest'}
    return $ref
}
try{
    if(-not $SkipBuild){foreach($service in @('Auth-Service','API-Gateway')){Invoke-TeamDocker -Arguments @('build','-t',"baotin/$($service.ToLower()):release-check",(Join-Path $root $service))|Out-Null}}
    foreach($image in @('registry:2','postgres:17-alpine','redis:7-alpine','nginxinc/nginx-unprivileged:1.28-alpine')){Invoke-TeamDocker -Arguments @('pull',$image)|Out-Null}
    # Registry is bound only to Linux-engine loopback, not exposed on Windows/LAN.
    # Docker Desktop daemon cannot reach Windows-only published 127.0.0.1 mappings.
    $port=Get-Random -Minimum 45000 -Maximum 55000
    Invoke-TeamDocker -Arguments @('run','-d','--name',$registry,'--network','host','-e',"REGISTRY_HTTP_ADDR=127.0.0.1:$port",'registry:2')|Out-Null
    $repo="127.0.0.1:$port";Start-Sleep -Seconds 2
    $auth=Push-TestImage 'baotin/auth-service:release-check' "$repo/auth-service:a";$gateway=Push-TestImage 'baotin/api-gateway:release-check' "$repo/api-gateway:a"
    # Different compatible image digest, same tested application + declared capabilities.
    $buildDir=Join-Path $temp 'image-b';New-Item -ItemType Directory -Path $buildDir|Out-Null
    [IO.File]::WriteAllText((Join-Path $buildDir 'Dockerfile'),"FROM baotin/auth-service:release-check`nLABEL baotin.test.revision=b`n")
    Invoke-TeamDocker -Arguments @('build','-t','baotin/auth-service:release-b',$buildDir)|Out-Null;$authB=Push-TestImage 'baotin/auth-service:release-b' "$repo/auth-service:b"
    [IO.File]::WriteAllText((Join-Path $buildDir 'Dockerfile'),"FROM baotin/auth-service:release-check`nLABEL baotin.test.revision=bad`nENTRYPOINT [`"sh`",`"-c`",`"exit 1`"]`n")
    Invoke-TeamDocker -Arguments @('build','-t','baotin/auth-service:release-bad',$buildDir)|Out-Null;$authBad=Push-TestImage 'baotin/auth-service:release-bad' "$repo/auth-service:bad"
    $runtime=New-TestPassword;$redisPassword=New-TestPassword
    Write-Secret 'postgres/password' (New-TestPassword);Write-Secret 'migration/password' (New-TestPassword);Write-Secret 'operator/password' (New-TestPassword)
    Write-Secret 'auth-config/spring.datasource.password' $runtime;Write-Secret 'gateway-config/spring.data.redis.password' $redisPassword;Write-Secret 'redis/password' $redisPassword
    Write-Secret 'redis/redis.conf' "bind 0.0.0.0`nprotected-mode yes`nrequirepass $redisPassword`nappendonly no`n"
    $rsa=[Security.Cryptography.RSA]::Create(3072);try{Write-Secret 'auth-keys/private.pem' $rsa.ExportPkcs8PrivateKeyPem()}finally{$rsa.Dispose()}
    [IO.File]::WriteAllBytes((Join-Path $secretRoot 'auth-keys/encryption.bin'),[Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $pg=(Invoke-TeamDocker -Arguments @('inspect','postgres:17-alpine','--format','{{index .RepoDigests 0}}')).Trim();$red=(Invoke-TeamDocker -Arguments @('inspect','redis:7-alpine','--format','{{index .RepoDigests 0}}')).Trim();$nginx=(Invoke-TeamDocker -Arguments @('inspect','nginxinc/nginx-unprivileged:1.28-alpine','--format','{{index .RepoDigests 0}}')).Trim()
    [IO.File]::WriteAllLines($envFile,@('COMPOSE_PROJECT_NAME=baotin-staging',"BAOTIN_SECRETS_DIR=$($secretRoot.Replace('\','/'))","AUTH_IMAGE=$auth","GATEWAY_IMAGE=$gateway","POSTGRES_IMAGE=$pg","REDIS_IMAGE=$red","NGINX_IMAGE=$nginx",'EDGE_PORT=8090','TEAM_SUBNET=172.29.47.0/24','TAILSCALE_API_PORT=9443',"AUTH_ALLOWED_ORIGINS=$allowedOrigins",'AUTH_EVENTS_ENABLED=false'))
    if($HostProxyIpv6Cidr){[IO.File]::AppendAllText($envFile,"GATEWAY_HOST_PROXY_IPV6_CIDR=$HostProxyIpv6Cidr`n")}
    $releaseA=Join-Path $temp 'a.json';Write-AtomicJson $releaseA @{authImage=$auth;gatewayImage=$gateway}
    & (Join-Path $PSScriptRoot 'Deploy-Team.ps1') -EnvFile $envFile -ReleaseFile $releaseA -StateRoot $state -SkipTailscaleCheck
    & (Join-Path $PSScriptRoot 'Test-TeamIngress.ps1') -EnvFile $envFile
    if($TestTailnet){& (Join-Path $PSScriptRoot 'Test-TeamTailnet.ps1') -EnvFile $envFile}
    $id=[Guid]::NewGuid().ToString('N').Substring(0,12);$username="release$id";$password='release-fixture-long-password'
    $profile=Invoke-RestMethod http://127.0.0.1:8090/api/v1/auth/register -Method Post -ContentType application/json -Body (@{username=$username;email="$username@example.test";password=$password;displayName='Release fixture'}|ConvertTo-Json)
    $login=Invoke-RestMethod http://127.0.0.1:8090/api/v1/auth/login -Method Post -ContentType application/json -Body (@{identifier=$username;password=$password}|ConvertTo-Json)
    $dbArgs=@('compose','--env-file',$envFile,'-f',(Join-Path $PSScriptRoot 'docker-compose.yml'),'exec','-T','postgres','psql','-v','ON_ERROR_STOP=1','-U','postgres','-d','baotin','-At','-c')
    Invoke-TeamDocker -Arguments ($dbArgs+@("UPDATE auth.users SET email_verified=true WHERE id='$($profile.id)'"))|Out-Null
    $enrollment=Invoke-RestMethod http://127.0.0.1:8090/api/v1/auth/mfa/enrollments -Method Post -ContentType application/json -Headers @{Authorization="Bearer $($login.tokens.accessToken)"} -Body (@{password=$password}|ConvertTo-Json)
    $releaseB=Join-Path $temp 'b.json';Write-AtomicJson $releaseB @{authImage=$authB;gatewayImage=$gateway}
    & (Join-Path $PSScriptRoot 'Deploy-Team.ps1') -EnvFile $envFile -ReleaseFile $releaseB -StateRoot $state -SkipTailscaleCheck
    $me=Invoke-RestMethod http://127.0.0.1:8090/api/v1/auth/me -Headers @{Authorization="Bearer $($login.tokens.accessToken)"};if($me.id -ne $profile.id){throw 'Data/session lost after release'}
    # Incompatible populated DB must reject rollback before any container/state mutation.
    foreach($gate in @(@('readerFloor','2','1','reader below database floor'),@('cipherFloor','future-v2','BK1','cannot read stored cipher format'))){
        $container=Invoke-TeamDocker -Arguments @('inspect','baotin-staging-auth-service-1','--format','{{.Id}}')
        $stateHash=(Get-FileHash -LiteralPath (Join-Path $state 'baotin-staging.json')).Hash
        try{
            Invoke-TeamDocker -Arguments ($dbArgs+@("UPDATE auth.runtime_metadata SET value='$($gate[1])' WHERE name='$($gate[0])'"))|Out-Null
            $denied=$false
            try{& (Join-Path $PSScriptRoot 'Rollback-Team.ps1') -Project baotin-staging -StateRoot $state}catch{if($_.Exception.Message -notlike "*$($gate[3])*"){throw};$denied=$true}
            if(-not $denied){throw 'Incompatible rollback was accepted'}
            if((Invoke-TeamDocker -Arguments @('inspect','baotin-staging-auth-service-1','--format','{{.Id}}')) -ne $container -or (Get-FileHash -LiteralPath (Join-Path $state 'baotin-staging.json')).Hash -ne $stateHash){throw 'Rejected rollback changed live container or release state'}
        }finally{Invoke-TeamDocker -Arguments ($dbArgs+@("UPDATE auth.runtime_metadata SET value='$($gate[2])' WHERE name='$($gate[0])'"))|Out-Null}
    }
    $bad=Join-Path $temp 'bad.json';Write-AtomicJson $bad @{authImage=$authBad;gatewayImage=$gateway}
    $failed=$false;try{& (Join-Path $PSScriptRoot 'Deploy-Team.ps1') -EnvFile $envFile -ReleaseFile $bad -StateRoot $state -SkipTailscaleCheck}catch{$failed=$true}
    if(-not $failed){throw 'Unhealthy release unexpectedly succeeded'}
    Test-TeamSmoke
    & (Join-Path $PSScriptRoot 'Rollback-Team.ps1') -Project baotin-staging -StateRoot $state
    $me=Invoke-RestMethod http://127.0.0.1:8090/api/v1/auth/me -Headers @{Authorization="Bearer $($login.tokens.accessToken)"};if($me.id -ne $profile.id){throw 'Data/session lost after compatible rollback'}
    $saved=Get-Content -Raw (Join-Path $state 'baotin-staging.json')|ConvertFrom-Json -AsHashtable;if($saved['authImage'] -ne $auth){throw 'Manual rollback did not restore prior digest'}
    # Confirm an enrollment encrypted before upgrade using the rolled-back reader.
    $alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';$bits=0;$buffer=0;$secret=[Collections.Generic.List[byte]]::new()
    foreach($character in $enrollment.secret.ToCharArray()){$buffer=($buffer -shl 5) -bor $alphabet.IndexOf($character);$bits+=5;if($bits -ge 8){$bits-=8;$secret.Add([byte](($buffer -shr $bits)-band 255));$buffer=$buffer-band ((1-shl $bits)-1)}}
    $counter=[BitConverter]::GetBytes([long][Math]::Floor([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()/30));[Array]::Reverse($counter)
    $hmac=[Security.Cryptography.HMACSHA1]::new($secret.ToArray());try{$digest=$hmac.ComputeHash($counter)}finally{$hmac.Dispose()}
    $offset=$digest[19]-band 15;$number=([long]($digest[$offset]-band 127)-shl 24)-bor ([long]$digest[$offset+1]-shl 16)-bor ([long]$digest[$offset+2]-shl 8)-bor [long]$digest[$offset+3]
    $codes=Invoke-RestMethod http://127.0.0.1:8090/api/v1/auth/mfa/enrollments/confirmation -Method Post -ContentType application/json -Headers @{Authorization="Bearer $($login.tokens.accessToken)"} -Body (@{code=($number%1000000).ToString('D6')}|ConvertTo-Json)
    if($codes.recoveryCodes.Count -ne 10){throw 'Encrypted enrollment failed after rollback'}
    Write-Output 'TEAM_RUNTIME PASS releaseA=healthy releaseB=healthy populated_db=preserved failed_release=rolled_back manual_rollback=healthy session=preserved images=digest reader_floor=denied cipher_floor=denied rejected_rollback=no_mutation encrypted_mfa=preserved'
}finally{
    if(Test-Path -LiteralPath $envFile){try{Invoke-TeamDocker -Arguments @('compose','--env-file',$envFile,'-f',(Join-Path $PSScriptRoot 'docker-compose.yml'),'down','--volumes','--remove-orphans')|Out-Null}catch{}}
    try{Invoke-TeamDocker -Arguments @('rm','-f',$registry)|Out-Null}catch{}
    $resolved=(Resolve-Path -LiteralPath $temp).Path;if(-not $resolved.StartsWith([IO.Path]::GetFullPath($env:TEMP)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe test cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force
}
