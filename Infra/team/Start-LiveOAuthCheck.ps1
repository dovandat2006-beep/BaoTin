#Requires -Version 7.4
[CmdletBinding()]
param(
    [string]$ConfigRoot=(Join-Path $env:USERPROFILE '.baotin'),
    [string]$Python='C:\Users\ngoan\AppData\Roaming\uv\tools\graphifyy\Scripts\python.exe',
    [string]$HostProxyIpv6Cidr='fd5e:a313:f28:2::6/128',
    [string]$OutputFile=(Join-Path $PSScriptRoot '../../.changes/auth-live-oauth/live.json'),
    [ValidateRange(1,30)][int]$Minutes=20,
    [ValidateRange(1024,65535)][int]$GatewayPort=8092,
    [string]$ImageManifest='',
    [ValidateSet('GOOGLE','FACEBOOK')][string[]]$Providers=@('GOOGLE','FACEBOOK'),
    [switch]$EnableTailnetReceiver,
    [switch]$LocalPreflightOnly
)
. (Join-Path $PSScriptRoot 'Team.Common.ps1')
if(-not $EnableTailnetReceiver -and -not $LocalPreflightOnly){throw 'Select local preflight or the approved temporary 9443/9444 OAuth test route explicitly'}
if($EnableTailnetReceiver -and $LocalPreflightOnly){throw 'Choose local preflight or live routes, not both'}
Assert-HostProxyIpv6 $HostProxyIpv6Cidr
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$config=(Resolve-Path -LiteralPath (Join-Path $ConfigRoot 'secrets/auth-config')).Path
if($config.StartsWith($root+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Provider configuration must be external'}
$state=tailscale status --json|ConvertFrom-Json
if($LASTEXITCODE -ne 0 -or $state.BackendState -ne 'Running'){throw 'Running Tailscale required'}
$hostname=$state.Self.DNSName.TrimEnd('.');$api="https://${hostname}:9443";$origin="https://${hostname}:9444"
$before=tailscale serve status --json|ConvertFrom-Json -AsHashtable
foreach($port in '9443','9444'){
    foreach($section in 'TCP','Web','AllowFunnel'){
        if($before.ContainsKey($section)){
            foreach($key in $before[$section].Keys){if($key -eq $port -or $key.EndsWith(':'+$port)){throw 'Reserved OAuth port is already configured; existing handler preserved'}}
        }
    }
}
if([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()|Where-Object Port -eq $GatewayPort){throw 'OAuth staging loopback port is occupied'}
$providerFiles=@('auth.oauth.google.client-id','auth.oauth.google.client-secret','auth.oauth.google.callback-uri',
    'auth.oauth.facebook.client-id','auth.oauth.facebook.client-secret','auth.oauth.facebook.callback-uri','auth.oauth.web-return-uris')
foreach($name in $providerFiles){if(-not(Test-Path -LiteralPath (Join-Path $config $name) -PathType Leaf)){throw 'Provider configuration incomplete'}}
foreach($provider in 'google','facebook'){
    if([IO.File]::ReadAllText((Join-Path $config "auth.oauth.$provider.callback-uri")).Trim() -ne "$api/api/v1/auth/oauth2/callback/$provider"){throw 'Configured provider callback does not match current HTTPS Gateway'}
}
if([IO.File]::ReadAllText((Join-Path $config 'auth.oauth.web-return-uris')).Trim() -ne "$origin/auth/callback"){throw 'Configured frontend return must exactly match the reviewed receiver'}
$output=[IO.Path]::GetFullPath($OutputFile)
if(-not $output.StartsWith((Join-Path $root '.changes')+'\',[StringComparison]::OrdinalIgnoreCase) -or (Test-Path $output)){throw 'Select a fresh report under workspace .changes'}
foreach($networkId in @((Invoke-TeamDocker -Arguments @('network','ls','-q')).Split("`n")|Where-Object {$_})){
    $existing=Invoke-TeamDocker -Arguments @('network','inspect',$networkId,'--format','{{json .IPAM.Config}}')|ConvertFrom-Json -AsHashtable
    foreach($subnet in $existing){if($subnet -and $subnet['Subnet'] -eq '172.29.47.0/24'){throw 'Private staging subnet is already in use'}}
}
$images=@{}
$tags=@('postgres:17-alpine','redis:7-alpine')
if($ImageManifest){
    $manifest=Get-Content -LiteralPath $ImageManifest -Raw|ConvertFrom-Json -AsHashtable
    foreach($key in 'AUTH_IMAGE','GATEWAY_IMAGE','WEB_IMAGE'){
        if($manifest.images[$key] -cnotmatch '^sha256:[a-f0-9]{64}$'){throw 'Reviewed immutable OAuth image IDs required'}
    }
    $images['baotin/auth-service:release-check']=$manifest.images.AUTH_IMAGE
    $images['baotin/api-gateway:release-check']=$manifest.images.GATEWAY_IMAGE
    $images['nginxinc/nginx-unprivileged:1.28-alpine']=$manifest.images.WEB_IMAGE
}else{$tags+=@('baotin/auth-service:release-check','baotin/api-gateway:release-check','nginxinc/nginx-unprivileged:1.28-alpine')}
foreach($tag in $tags){
    $images[$tag]=(Invoke-TeamDocker -Arguments @('image','inspect',$tag,'--format','{{.Id}}')).Trim()
}
$id=[guid]::NewGuid().ToString('N').Substring(0,8)
$network="baotin-oauth-check-$id";$pg="baotin-oauth-pg-$id";$auth="baotin-oauth-auth-$id"
$gateway="baotin-oauth-gateway-$id";$redis="baotin-oauth-redis-$id";$nginx="baotin-oauth-nginx-$id"
$tempRoot=[IO.Path]::GetTempPath()
$temp=Join-Path $tempRoot "baotin-oauth-check-$id";New-Item -ItemType Directory $temp|Out-Null
$acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetAccessRuleProtection($true,$false)
foreach($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')){
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid),'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
}
Set-Acl -LiteralPath $temp -AclObject $acl
$process=$null;$routes=[Collections.Generic.List[string]]::new();$created=[Collections.Generic.List[string]]::new();$networkCreated=$false
function Password {[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))}
function Run-Container([string]$Name,[string[]]$Arguments,[switch]$Secret){
    $created.Add($Name);Invoke-TeamDocker -Arguments (@('run','-d','--name',$Name,'--network',$network)+$Arguments) -SecretBearing:$Secret|Out-Null
}
try{
    $fixtureConfig=Join-Path $temp 'config';New-Item -ItemType Directory $fixtureConfig|Out-Null
    foreach($name in $providerFiles){Copy-Item -LiteralPath (Join-Path $config $name) -Destination $fixtureConfig}
    # This fixture never enables SMTP/Firebase/events or changes the user's configuration.
    foreach($pair in @(@('auth.mail.enabled','false'),@('auth.firebase.enabled','false'),@('auth.events.enabled','false'))){[IO.File]::WriteAllText((Join-Path $fixtureConfig $pair[0]),$pair[1])}
    $rsa=[Security.Cryptography.RSA]::Create(3072);try{[IO.File]::WriteAllText((Join-Path $temp 'private.pem'),$rsa.ExportPkcs8PrivateKeyPem())}finally{$rsa.Dispose()}
    [IO.File]::WriteAllBytes((Join-Path $temp 'encryption.bin'),[Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $bootstrap=Password;$runtime=Password;$migrator=Password;$redisPassword=Password
    $networkCreated=$true;Invoke-TeamDocker -Arguments @('network','create','--subnet','172.29.47.0/24',$network)|Out-Null
    Run-Container $pg @('-e',"POSTGRES_PASSWORD=$bootstrap",'-e','POSTGRES_DB=baotin',$images['postgres:17-alpine']) -Secret
    $ready=$false
    for($i=0;$i -lt 60;$i++){docker --context desktop-linux exec $pg pg_isready -h 127.0.0.1 -U postgres -d baotin 2>$null|Out-Null;if($LASTEXITCODE -eq 0){$ready=$true;break};Start-Sleep -Milliseconds 500}
    if(-not $ready){throw 'OAuth fixture PostgreSQL readiness failed'}
    $sql=[IO.File]::ReadAllText((Join-Path $root 'Auth-Service/src/main/resources/db/provision/before-migrate.sql'))+"`nALTER ROLE baotin_auth PASSWORD '$runtime'; ALTER ROLE baotin_auth_migrator PASSWORD '$migrator';"
    $sql|docker --context desktop-linux exec -i $pg psql -v ON_ERROR_STOP=1 -U postgres -d baotin 2>$null|Out-Null
    if($LASTEXITCODE -ne 0){throw 'OAuth fixture provisioning failed'}
    Invoke-TeamDocker -Arguments @('run','--rm','--network',$network,'--entrypoint','java','-e',"DB_URL=jdbc:postgresql://${pg}:5432/baotin",'-e','DB_MIGRATION_USER=baotin_auth_migrator','-e',"DB_MIGRATION_PASSWORD=$migrator",$images['baotin/auth-service:release-check'],'-Dloader.main=com.baotin.auth.operator.DatabaseMigration','-cp','/app/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher') -SecretBearing|Out-Null
    [IO.File]::ReadAllText((Join-Path $root 'Auth-Service/src/main/resources/db/provision/after-migrate.sql'))|docker --context desktop-linux exec -i $pg psql -v ON_ERROR_STOP=1 -U postgres -d baotin 2>$null|Out-Null
    if($LASTEXITCODE -ne 0){throw 'OAuth fixture runtime grants failed'}
    Run-Container $redis @($images['redis:7-alpine'],'redis-server','--requirepass',$redisPassword) -Secret
    Run-Container $auth @('--ip','172.29.47.11','--mount',"type=bind,source=$temp,target=/run/fixture,readonly",
        '-e','SPRING_PROFILES_ACTIVE=team','-e','SPRING_CONFIG_IMPORT=configtree:/run/fixture/config/',
        '-e',"DB_URL=jdbc:postgresql://${pg}:5432/baotin",'-e','DB_USER=baotin_auth','-e',"DB_PASSWORD=$runtime",'-e','DB_MIGRATION_ENABLED=false',
        '-e','JWT_PRIVATE_KEY_PATH=/run/fixture/private.pem','-e','JWT_KEY_ID=oauth-fixture','-e','AUTH_ENCRYPTION_KEY_PATH=/run/fixture/encryption.bin',
        '-e',"AUTH_ALLOWED_ORIGINS=$origin",'-e','AUTH_TRUSTED_GATEWAY_CIDRS=172.29.47.10/32',$images['baotin/auth-service:release-check']) -Secret
    Run-Container $gateway @('--ip','172.29.47.10','--network-alias','api-gateway',
        '-e',"AUTH_SERVICE_URI=http://${auth}:8080",'-e',"AUTH_JWKS_URI=http://${auth}:8080/.well-known/jwks.json",'-e',"REDIS_HOST=$redis",'-e',"REDIS_PASSWORD=$redisPassword",
        '-e',"GATEWAY_ALLOWED_ORIGINS=$origin",'-e',"GATEWAY_TRUSTED_PROXY_CIDRS=172.29.47.12/32,172.29.47.1/32,$HostProxyIpv6Cidr",$images['baotin/api-gateway:release-check']) -Secret
    $nginxConfig=(Resolve-Path (Join-Path $PSScriptRoot 'nginx.conf')).Path
    Run-Container $nginx @('--ip','172.29.47.12','-p',"127.0.0.1:${GatewayPort}:8080",'--health-cmd','wget -q -O /dev/null http://127.0.0.1:8080/actuator/health','--mount',"type=bind,source=$nginxConfig,target=/etc/nginx/conf.d/default.conf,readonly",$images['nginxinc/nginx-unprivileged:1.28-alpine'])
    $ready=$false
    for($i=0;$i -lt 90;$i++){try{$health=Invoke-RestMethod "http://127.0.0.1:${GatewayPort}/actuator/health" -NoProxy -TimeoutSec 2;if($health.status -eq 'UP'){$ready=$true;break}}catch{};Start-Sleep -Seconds 1}
    if(-not $ready){throw 'OAuth fixture health failed; raw request/container diagnostics withheld'}
    $reportFile=Join-Path $temp 'result.json';$portFile=Join-Path $temp 'port'
    $receiverConfig=Join-Path $temp 'receiver.json'
    @{template=(Join-Path $PSScriptRoot 'oauth-check.html');api=$api;origin=$origin;nonce=(Password);report=$reportFile;portFile=$portFile}|ConvertTo-Json|Set-Content -LiteralPath $receiverConfig
    $process=Start-Process -FilePath $Python -ArgumentList @((Join-Path $PSScriptRoot 'oauth-check-server.py'),'--config',$receiverConfig) -WindowStyle Hidden -PassThru
    for($i=0;$i -lt 50 -and -not(Test-Path $portFile);$i++){if($process.HasExited){throw 'OAuth receiver stopped early'};Start-Sleep -Milliseconds 100}
    $port=[int][IO.File]::ReadAllText($portFile)
    if($LocalPreflightOnly){
        $receiver=Invoke-RestMethod "http://127.0.0.1:$port/health" -NoProxy -TimeoutSec 5
        if($receiver.status -ne 'UP'){throw 'Local OAuth receiver health failed'}
        foreach($provider in 'GOOGLE','FACEBOOK'){
            $body=@{provider=$provider;transport='WEB';returnUri="$origin/auth/callback";codeChallenge=('A'*43)}|ConvertTo-Json
            $response=Invoke-WebRequest "http://127.0.0.1:${GatewayPort}/api/v1/auth/oauth2/requests" -NoProxy -Method Post -Headers @{Origin=$origin} -ContentType application/json -Body $body -SkipHttpErrorCheck
            if($response.StatusCode -ne 200 -or ($response.Headers['Set-Cookie'] -join '') -notmatch '__Host-baotin-oauth-binding=.*;.*Secure;.*HttpOnly'){throw 'Configured provider request/binding preflight failed'}
            $wrong=Invoke-WebRequest "http://127.0.0.1:${GatewayPort}/api/v1/auth/oauth2/requests" -NoProxy -Method Post -Headers @{Origin='https://evil.example'} -ContentType application/json -Body $body -SkipHttpErrorCheck
            if($wrong.StatusCode -ne 403){throw 'Unapproved OAuth Origin accepted'}
        }
        Write-Output 'OAUTH_PREFLIGHT PASS containers=healthy receiver=UP provider_requests=2 secure_binding=true wrong_origin=403 routes_added=0 user_consent=false'
        return
    }
    foreach($pair in @(@('9443',"$GatewayPort"),@('9444',"$port"))){
        $routes.Add($pair[0]);tailscale serve --bg --https=$($pair[0]) "http://127.0.0.1:$($pair[1])" 2>&1|Out-Null
        if($LASTEXITCODE -ne 0){throw 'Temporary OAuth HTTPS route setup failed'}
    }
    $receiver=Invoke-RestMethod "$origin/health" -NoProxy -TimeoutSec 10
    $edge=Invoke-RestMethod "$api/actuator/health" -NoProxy -TimeoutSec 10
    if($receiver.status -ne 'UP' -or $edge.status -ne 'UP'){throw 'Actual OAuth HTTPS readiness failed'}
    Write-Output "OAUTH_READY url=$origin providers=GOOGLE,FACEBOOK private_staging=true logs=redacted timeout_minutes=$Minutes"
    $deadline=[DateTimeOffset]::UtcNow.AddMinutes($Minutes);$results=@{}
    while([DateTimeOffset]::UtcNow -lt $deadline){
        if($process.HasExited){throw 'OAuth receiver stopped unexpectedly'}
        if(Test-Path $reportFile){$results=Get-Content -LiteralPath $reportFile -Raw|ConvertFrom-Json -AsHashtable
            if(@($Providers|Where-Object {-not $results.ContainsKey($_) -or -not $results[$_]['passed']}).Count -eq 0){break}}
        Start-Sleep -Seconds 2
    }
    # Direct backend invariants supplement browser-generated HTTP status reports.
    $invariants=(Invoke-TeamDocker -Arguments @('exec',$pg,'psql','-v','ON_ERROR_STOP=1','-U','postgres','-d','baotin','-At','-c',
        "SELECT (SELECT count(*) FROM auth.oauth_accounts), (SELECT count(*) FROM auth.users WHERE role<>'USER'), (SELECT count(*) FROM auth.refresh_sessions WHERE revoked_at IS NULL);" )).Trim().Split('|')
    if($invariants.Count -ne 3 -or [int]$invariants[1] -ne 0 -or [int]$invariants[2] -ne 0){throw 'OAuth fixture backend role/session invariant failed'}
    $passed=@($Providers|Where-Object {-not $results.ContainsKey($_) -or -not $results[$_]['passed']}).Count -eq 0 -and [int]$invariants[0] -ge $Providers.Count
    New-Item -ItemType Directory -Force (Split-Path $output)|Out-Null
    Write-AtomicJson $output @{passed=$passed;transport='WEB';results=$results;providerAccounts=[int]$invariants[0];privilegedRoles=0;activeSessions=0;secretsLogged=$false;checkedAt=[DateTimeOffset]::UtcNow.ToString('o')}
    if(-not $passed){throw 'OAuth check remains incomplete; sanitized partial report saved'}
    Write-Output "OAUTH_LIVE PASS providers=$($Providers -join ',') transport=WEB pkce=verified cookie_binding=verified role=USER admin=403 replay=401 csrf=403 refresh=200 logout=204 revoked=401 backend_roles=USER backend_sessions=revoked"
}finally{
    $cleanupFailed=$false
    foreach($port in $routes){tailscale serve --https=$port off 2>&1|Out-Null;if($LASTEXITCODE -ne 0){$cleanupFailed=$true}}
    $after=tailscale serve status --json|ConvertFrom-Json -AsHashtable
    foreach($section in 'TCP','Web','AllowFunnel'){
        if($before.ContainsKey($section)){foreach($key in $before[$section].Keys){
            if(-not $after.ContainsKey($section) -or -not $after[$section].ContainsKey($key) -or ($before[$section][$key]|ConvertTo-Json -Depth 12 -Compress) -ne ($after[$section][$key]|ConvertTo-Json -Depth 12 -Compress)){$cleanupFailed=$true}
        }}
    }
    foreach($port in $routes){if($after.ContainsKey('TCP') -and $after['TCP'].ContainsKey($port)){$cleanupFailed=$true}}
    if($process -and -not $process.HasExited){Stop-Process -Id $process.Id -Force}
    foreach($container in @($nginx,$gateway,$auth,$redis,$pg)){
        if($created.Contains($container)){try{Invoke-TeamDocker -Arguments @('rm','-f','-v',$container)|Out-Null}catch{$cleanupFailed=$true}}
    }
    if($networkCreated){try{Invoke-TeamDocker -Arguments @('network','rm',$network)|Out-Null}catch{$cleanupFailed=$true}}
    $resolved=(Resolve-Path -LiteralPath $temp).Path
    if(-not $resolved.StartsWith([IO.Path]::GetFullPath($tempRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^baotin-oauth-check-[a-f0-9]{8}$'){throw 'Unsafe OAuth fixture cleanup'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
    if($cleanupFailed){throw 'OAuth fixture cleanup needs operator inspection'}
    Write-Output 'OAUTH_CLEANUP PASS temporary_routes=removed existing_handlers=preserved fixture_containers_volumes=removed secrets=removed'
}
