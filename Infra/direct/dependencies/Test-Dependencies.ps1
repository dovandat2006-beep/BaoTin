#Requires -Version 7.4
[CmdletBinding()]
param([string]$ComposeFile=(Join-Path $PSScriptRoot 'docker-compose.yml'),
      [string]$JavaHome='C:\Users\ngoan\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2')
$ErrorActionPreference='Stop'
if(-not(Test-Path -LiteralPath $ComposeFile -PathType Leaf)){throw 'Dependency Compose file missing'}
. (Join-Path $PSScriptRoot '../../team/Team.Common.ps1')
$workspace=(Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$id=[guid]::NewGuid().ToString('N');$project='baotin-deps-check-'+$id
$root=Join-Path $env:TEMP $project
New-Item -ItemType Directory -Path $root|Out-Null
$acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetAccessRuleProtection($true,$false)
foreach($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')){$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid),'FullControl','ContainerInherit,ObjectInherit','None','Allow'))}
Set-Acl -LiteralPath $root -AclObject $acl
$old=@{};foreach($key in 'BAOTIN_SECRETS_DIR','POSTGRES_PORT','REDIS_PORT','DB_URL','DB_USER','DB_PASSWORD_FILE','DB_MIGRATION_USER','DB_MIGRATION_PASSWORD','DB_MIGRATION_PASSWORD_FILE','DB_PASSWORD','SERVER_ADDRESS','SERVER_PORT','SPRING_CONFIG_IMPORT','AUTH_SERVICE_URI','AUTH_JWKS_URI','SPRING_PROFILES_ACTIVE'){$old[$key]=[Environment]::GetEnvironmentVariable($key)}
function Free-Port {$listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$listener.Start();try{$listener.LocalEndpoint.Port}finally{$listener.Stop()}}
$pgPort=Free-Port;$redisPort=Free-Port;while($redisPort -eq $pgPort){$redisPort=Free-Port}
$envFile=Join-Path $root 'deps.env'
$argsBase=@('compose','--project-name',$project,'--env-file',$envFile,'-f',[IO.Path]::GetFullPath($ComposeFile))
function C([string[]]$Arguments,[switch]$Sensitive){Invoke-TeamDocker -Arguments ($argsBase+$Arguments) -SecretBearing:$Sensitive}
function PG([string]$Sql){
    $Sql | & docker --context desktop-linux @argsBase exec -T postgres sh -c 'PGPASSWORD="$(cat /run/secrets/postgres-password)" psql -h 127.0.0.1 -U postgres -d baotin -v ON_ERROR_STOP=1 -At' 2>$null
    if($LASTEXITCODE){throw 'Fixture PostgreSQL statement failed; raw diagnostics withheld'}
}
function Invoke-FixtureRedis([string]$Command){C -Arguments @('exec','-T','redis','sh','-c',('export REDISCLI_AUTH="$(cat /run/secrets/redis-password)"; redis-cli --raw '+$Command)) -Sensitive}
$started=$false;$gateway=$null;$gatewayTicks=$null
try {
    New-Item -ItemType Directory (Join-Path $root 'postgres'),(Join-Path $root 'gateway-config')|Out-Null
    $bootstrap=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $redisPassword=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $runtime=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $migrator=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    [IO.File]::WriteAllText((Join-Path $root 'postgres/password'),$bootstrap)
    [IO.File]::WriteAllText((Join-Path $root 'gateway-config/spring.data.redis.password'),$redisPassword)
    [IO.File]::WriteAllText((Join-Path $root 'runtime.password'),$runtime)
    [IO.File]::WriteAllText((Join-Path $root 'migrator.password'),$migrator)
    [IO.File]::WriteAllLines($envFile,@("BAOTIN_SECRETS_DIR=$($root.Replace('\','/'))","POSTGRES_PORT=$pgPort","REDIS_PORT=$redisPort"))
    $env:BAOTIN_SECRETS_DIR=$root.Replace('\','/');$env:POSTGRES_PORT="$pgPort";$env:REDIS_PORT="$redisPort"
    $cfg=(C -Arguments @('config','--format','json'))|ConvertFrom-Json -AsHashtable
    if($cfg.services.Count -ne 2){throw 'Unexpected service count'}
    foreach($name in 'postgres','redis'){
        if($cfg.services[$name].image -notmatch '@sha256:[a-f0-9]{64}$' -or $cfg.services[$name].ports[0].host_ip -ne '127.0.0.1'){throw 'Digest/loopback boundary failed'}
    }
    $started=$true;C -Arguments @('up','-d','--pull','never','--wait','--wait-timeout','90')|Out-Null
    foreach($name in 'postgres','redis'){
        $container=(C -Arguments @('ps','-q',$name)).Trim();$meta=(Invoke-TeamDocker -Arguments @('inspect',$container))|ConvertFrom-Json
        if($meta.State.Health.Status -ne 'healthy'){throw 'Dependency health failed'}
        foreach($secret in @($bootstrap,$redisPassword,$runtime,$migrator)){if(($meta.Config|ConvertTo-Json -Depth 10 -Compress).Contains($secret)){throw 'Inspect leaked a fixture password'}}
        foreach($property in $meta.HostConfig.PortBindings.PSObject.Properties){
            foreach($binding in @($property.Value)){if($binding -and $binding.HostIp -ne '127.0.0.1'){throw 'Wildcard host bind'}}
        }
    }
    if((C -Arguments @('exec','-T','redis','redis-cli','ping')) -notmatch 'NOAUTH'){throw 'Unauthenticated Redis accepted'}
    if((Invoke-FixtureRedis 'CONFIG GET dir') -notmatch 'NOPERM' -or (Invoke-FixtureRedis 'FLUSHALL') -notmatch 'NOPERM' -or (Invoke-FixtureRedis 'SET unrelated:key value') -notmatch 'NOPERM'){throw 'Redis privilege boundary failed'}
    if((Invoke-FixtureRedis 'SET gateway:fixture persisted').Trim() -ne 'OK'){throw 'Gateway Redis write failed'}
    if((Invoke-FixtureRedis 'EVAL "local n=redis.call(\"INCR\",KEYS[1]);if n==1 then redis.call(\"EXPIRE\",KEYS[1],ARGV[1]) end;return n" 1 gateway:rate:fixture 60').Trim() -ne '1'){throw 'Gateway Lua rate-limit script failed'}
    PG 'CREATE TABLE public.deps_marker(value TEXT);INSERT INTO public.deps_marker VALUES (''persisted'');'|Out-Null
    PG ([IO.File]::ReadAllText((Join-Path $workspace 'Auth-Service/src/main/resources/db/provision/before-migrate.sql'))+"`nALTER ROLE baotin_auth PASSWORD '$runtime';ALTER ROLE baotin_auth_migrator PASSWORD '$migrator';")|Out-Null
    $tcp=[Net.Sockets.TcpClient]::new()
    try{if(-not $tcp.ConnectAsync('127.0.0.1',$pgPort).Wait(5000) -or -not $tcp.Connected){throw 'PostgreSQL host TCP connection failed'}}finally{$tcp.Dispose()}
    $env:DB_URL="jdbc:postgresql://127.0.0.1:$pgPort/baotin?connectTimeout=5&socketTimeout=15";$env:DB_MIGRATION_USER='baotin_auth_migrator';$env:DB_MIGRATION_PASSWORD=$null;$env:DB_MIGRATION_PASSWORD_FILE=Join-Path $root 'migrator.password'
    $jar=Join-Path $workspace 'Auth-Service/build/libs/Auth-Service-0.0.1-SNAPSHOT.jar'
    $out=& (Join-Path $JavaHome 'bin/java.exe') '-Dloader.main=com.baotin.auth.operator.DatabaseMigration' -cp $jar org.springframework.boot.loader.launch.PropertiesLauncher 2>&1
    if($LASTEXITCODE){
        $kind='other'
        foreach($pattern in @('Connection refused','timed out','password authentication failed','UnknownHost','Permission denied')){if(($out -join "`n").Contains($pattern)){$kind=$pattern;break}}
        throw "Host JAR migration against Docker PostgreSQL failed; kind=$kind; raw diagnostics withheld"
    }
    PG ([IO.File]::ReadAllText((Join-Path $workspace 'Auth-Service/src/main/resources/db/provision/after-migrate.sql')))|Out-Null
    $env:DB_USER='baotin_auth';$env:DB_PASSWORD=$null;$env:DB_PASSWORD_FILE=Join-Path $root 'runtime.password'
    $out=& (Join-Path $JavaHome 'bin/java.exe') '-Dloader.main=com.baotin.auth.operator.RuntimeDatabaseState' -cp $jar org.springframework.boot.loader.launch.PropertiesLauncher 2>&1
    if($LASTEXITCODE -or ($out -join "`n") -notmatch '"schemaRevision":"9"'){throw 'Host runtime-role compatibility probe failed'}
    # Boot's real Redis health indicator uses INFO; test the current JAR, not only redis-cli PING.
    $webPort=Free-Port;$env:SERVER_ADDRESS='127.0.0.1';$env:SERVER_PORT="$webPort";$env:SPRING_PROFILES_ACTIVE='team'
    $env:SPRING_CONFIG_IMPORT='configtree:'+($root.Replace('\','/')+'/gateway-config/')
    $env:AUTH_SERVICE_URI='http://127.0.0.1:1';$env:AUTH_JWKS_URI='http://127.0.0.1:1/.well-known/jwks.json'
    $gatewayJar=Join-Path $workspace 'API-Gateway/build/libs/API-Gateway-0.0.1-SNAPSHOT.jar'
    $gateway=Start-Process -FilePath (Join-Path $JavaHome 'bin/java.exe') -ArgumentList @('-Xmx256m','-jar',('"'+$gatewayJar+'"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root 'gateway.out') -RedirectStandardError (Join-Path $root 'gateway.err')
    $gatewayTicks=$gateway.StartTime.ToUniversalTime().Ticks;$healthy=$false
    for($i=0;$i -lt 45;$i++){
        $gateway.Refresh();if($gateway.HasExited){throw 'Fixture Gateway JAR exited; raw diagnostics withheld'}
        try{$health=Invoke-RestMethod "http://127.0.0.1:$webPort/actuator/health" -NoProxy -TimeoutSec 2;if($health.status -eq 'UP'){$healthy=$true;break}}catch{}
        Start-Sleep -Seconds 1
    }
    if(-not $healthy){throw 'Real Gateway Redis health indicator failed'}
    $denied=$false
    try{C -Arguments @('exec','-T','postgres','sh','-c','PGPASSWORD=deliberately-wrong psql -h 127.0.0.1 -U postgres -d baotin -Atc "SELECT 1"') -Sensitive|Out-Null}
    catch{if($_.Exception.Message -ne 'Docker operation failed (compose); secret-bearing diagnostics withheld'){throw};$denied=$true}
    if(-not $denied){throw 'Wrong PostgreSQL password unexpectedly accepted'}
    C -Arguments @('down')|Out-Null
    C -Arguments @('up','-d','--pull','never','--wait','--wait-timeout','90')|Out-Null
    if((PG 'SELECT value FROM public.deps_marker').Trim() -ne 'persisted' -or (Invoke-FixtureRedis 'GET gateway:fixture').Trim() -ne 'persisted'){throw 'Dependency persistence failed'}
    'DOCKER_DEPENDENCIES PASS postgres=healthy redis=healthy gateway_jar_health=UP host_jdbc=connected schema=9 redis_lua=working unauthenticated=denied admin_commands=denied loopback=true persisted_after_recreate=true inspect_secrets=0'
} finally {
    if($gateway -and $gatewayTicks){$p=Get-Process -Id $gateway.Id -ErrorAction SilentlyContinue;if($p -and $p.StartTime.ToUniversalTime().Ticks -eq $gatewayTicks){Stop-Process -Id $p.Id -Force}}
    if($started){C -Arguments @('down','--volumes')|Out-Null}
    foreach($key in $old.Keys){[Environment]::SetEnvironmentVariable($key,$old[$key],'Process')}
    $resolved=[IO.Path]::GetFullPath($root)
    if(-not $resolved.StartsWith([IO.Path]::GetFullPath($env:TEMP).TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^baotin-deps-check-[a-f0-9]{32}$'){throw 'Unexpected fixture cleanup target'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
