#Requires -Version 7.4
[CmdletBinding()]
param([string]$MailConfig=(Join-Path $env:USERPROFILE '.baotin\secrets\auth-config'),[switch]$TestRetry)
. (Join-Path $PSScriptRoot 'Team.Common.ps1')
$root=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$source=(Resolve-Path -LiteralPath $MailConfig).Path
if($source.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Live SMTP configuration must be external'}
$fields=@('spring.mail.username','spring.mail.password','auth.mail.from','spring.mail.properties.mail.from','auth.mail.enabled')
foreach($field in $fields){if(-not(Test-Path -LiteralPath (Join-Path $source $field) -PathType Leaf)){throw "SMTP configuration missing field: $field"}}
$recipient=[IO.File]::ReadAllText((Join-Path $source 'spring.mail.username')).Trim()
if($recipient -notmatch '^[^\s@]+@[^\s@]+\.[^\s@]+$' -or [IO.File]::ReadAllText((Join-Path $source 'auth.mail.from')).Trim() -ne $recipient -or [IO.File]::ReadAllText((Join-Path $source 'spring.mail.properties.mail.from')).Trim() -ne $recipient -or [IO.File]::ReadAllText((Join-Path $source 'auth.mail.enabled')).Trim() -ne 'true'){throw 'SMTP live smoke requires enabled sender-self mailbox configuration'}
$id=[guid]::NewGuid().ToString('N').Substring(0,8);$network="baotin-gmail-check-$id";$pg="baotin-gmail-pg-$id";$auth="baotin-gmail-auth-$id";$gateway="baotin-gmail-gateway-$id";$redis="baotin-gmail-redis-$id"
$temp=Join-Path $env:TEMP "baotin-gmail-check-$id";New-Item -ItemType Directory $temp|Out-Null
$acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetAccessRuleProtection($true,$false)
foreach($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')){
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid),'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
}
Set-Acl -LiteralPath $temp -AclObject $acl
$config=Join-Path $temp 'smtp';New-Item -ItemType Directory $config|Out-Null
try{
    foreach($field in $fields){Copy-Item -LiteralPath (Join-Path $source $field) -Destination (Join-Path $config $field)}
    $rsa=[Security.Cryptography.RSA]::Create(3072);try{[IO.File]::WriteAllText((Join-Path $temp 'private.pem'),$rsa.ExportPkcs8PrivateKeyPem())}finally{$rsa.Dispose()}
    [IO.File]::WriteAllBytes((Join-Path $temp 'encryption.bin'),[Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $bootstrap=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32));$runtime=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32));$migrator=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    Invoke-TeamDocker -Arguments @('network','create',$network)|Out-Null
    Invoke-TeamDocker -Arguments @('run','-d','--name',$pg,'--network',$network,'-e',"POSTGRES_PASSWORD=$bootstrap",'-e','POSTGRES_DB=baotin','postgres:17-alpine') -SecretBearing|Out-Null
    for($i=0;$i -lt 60;$i++){docker --context desktop-linux exec $pg pg_isready -U postgres -d baotin 2>$null|Out-Null;if($LASTEXITCODE -eq 0){break};Start-Sleep -Milliseconds 500}
    $sql=[IO.File]::ReadAllText((Join-Path $root 'Auth-Service/src/main/resources/db/provision/before-migrate.sql'))+"`nALTER ROLE baotin_auth PASSWORD '$runtime'; ALTER ROLE baotin_auth_migrator PASSWORD '$migrator';"
    $sql|docker --context desktop-linux exec -i $pg psql -v ON_ERROR_STOP=1 -U postgres -d baotin 2>$null|Out-Null;if($LASTEXITCODE -ne 0){throw 'SMTP fixture provisioning failed'}
    Invoke-TeamDocker -Arguments @('run','--rm','--network',$network,'--entrypoint','java','-e',"DB_URL=jdbc:postgresql://${pg}:5432/baotin",'-e','DB_MIGRATION_USER=baotin_auth_migrator','-e',"DB_MIGRATION_PASSWORD=$migrator",'baotin/auth-service:release-check','-Dloader.main=com.baotin.auth.operator.DatabaseMigration','-cp','/app/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher') -SecretBearing|Out-Null
    [IO.File]::ReadAllText((Join-Path $root 'Auth-Service/src/main/resources/db/provision/after-migrate.sql'))|docker --context desktop-linux exec -i $pg psql -v ON_ERROR_STOP=1 -U postgres -d baotin 2>$null|Out-Null;if($LASTEXITCODE -ne 0){throw 'SMTP fixture grants failed'}
    Invoke-TeamDocker -Arguments @('run','-d','--name',$redis,'--network',$network,'redis:7-alpine')|Out-Null
    function Start-Auth([int]$SmtpPort){
        $smtpHost=if($SmtpPort -eq 1){'127.0.0.1'}else{'smtp.gmail.com'}
        Invoke-TeamDocker -Arguments @('run','-d','--name',$auth,'--network',$network,'--mount',"type=bind,source=$temp,target=/run/fixture,readonly",'-e','SPRING_CONFIG_IMPORT=configtree:/run/fixture/smtp/',
            '-e',"DB_URL=jdbc:postgresql://${pg}:5432/baotin",'-e','DB_USER=baotin_auth','-e',"DB_PASSWORD=$runtime",'-e','DB_MIGRATION_ENABLED=false',
            '-e',"SMTP_HOST=$smtpHost",'-e',"SMTP_PORT=$SmtpPort",'-e','JWT_PRIVATE_KEY_PATH=/run/fixture/private.pem','-e','JWT_KEY_ID=gmail-fixture','-e','AUTH_ENCRYPTION_KEY_PATH=/run/fixture/encryption.bin','baotin/auth-service:release-check') -SecretBearing|Out-Null
    }
    # A fixture-local unreachable port fails connection before transmitting credentials.
    Start-Auth $(if($TestRetry){1}else{587})
    Invoke-TeamDocker -Arguments @('run','-d','--name',$gateway,'--network',$network,'-p','127.0.0.1::8080','-e',"AUTH_SERVICE_URI=http://${auth}:8080",'-e',"AUTH_JWKS_URI=http://${auth}:8080/.well-known/jwks.json",'-e',"REDIS_HOST=$redis",'baotin/api-gateway:release-check')|Out-Null
    $port=(Invoke-TeamDocker -Arguments @('port',$gateway,'8080/tcp')).Trim().Split(':')[-1];$url="http://127.0.0.1:$port";$health=$null
    for($i=0;$i -lt 90;$i++){try{$health=Invoke-RestMethod "$url/actuator/health" -NoProxy -TimeoutSec 2;if($health.status -eq 'UP'){break}}catch{};Start-Sleep -Seconds 1}
    if(-not $health -or $health.status -ne 'UP'){throw 'Gmail fixture Gateway health failed'}
    $anonymous=Invoke-WebRequest "$url/api/v1/auth/me" -NoProxy -SkipHttpErrorCheck;if([int]$anonymous.StatusCode -ne 401){throw 'SMTP fixture anonymous authorization failed'}
    $user="gmail$id";$password=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    try{$profile=Invoke-RestMethod "$url/api/v1/auth/register" -NoProxy -Method Post -ContentType application/json -Body (@{username=$user;email=$recipient;password=$password;displayName='SMTP delivery fixture'}|ConvertTo-Json)}catch{throw 'Gmail fixture registration failed; diagnostics withheld'}
    $query="SELECT json_build_object('sent',sent_at IS NOT NULL,'attempts',attempts,'cleared',octet_length(encrypted_payload)=0) FROM auth.mail_outbox ORDER BY created_at LIMIT 1"
    if($TestRetry){
        $failed=$null
        for($i=0;$i -lt 30;$i++){
            $raw=Invoke-TeamDocker -Arguments @('exec',$pg,'psql','-U','postgres','-d','baotin','-At','-c',"SELECT json_build_object('sent',sent_at IS NOT NULL,'attempts',attempts,'leaseReleased',lease_id IS NULL,'backoff',next_attempt_at>now()) FROM auth.mail_outbox ORDER BY created_at LIMIT 1")
            if($raw.Trim()){$failed=$raw.Trim()|ConvertFrom-Json;if($failed.attempts -ge 1 -and $failed.leaseReleased){break}};Start-Sleep -Seconds 1
        }
        if(-not $failed -or $failed.sent -or $failed.attempts -ne 1 -or -not $failed.leaseReleased -or -not $failed.backoff){throw 'SMTP connection failure did not preserve/backoff the leased outbox'}
        Invoke-TeamDocker -Arguments @('rm','-f',$auth)|Out-Null
        Start-Auth 587
    }
    $row=$null
    for($i=0;$i -lt 60;$i++){
        $raw=Invoke-TeamDocker -Arguments @('exec',$pg,'psql','-U','postgres','-d','baotin','-At','-c',$query)
        if($raw.Trim()){$row=$raw.Trim()|ConvertFrom-Json;if($row.sent){break}}
        Start-Sleep -Seconds 1
    }
    if(-not $row -or -not $row.sent -or -not $row.cleared){throw 'Live Gmail outbox delivery not confirmed'}
    $expectedAttempts=if($TestRetry){2}else{1}
    if([int]$row.attempts -ne $expectedAttempts){throw 'Unexpected live Gmail delivery attempts; inspect redacted diagnostics'}
    if((Invoke-TeamDocker -Arguments @('exec',$pg,'psql','-U','postgres','-d','baotin','-At','-c','SELECT count(*) FROM auth.mail_outbox')).Trim() -ne '1'){throw 'SMTP fixture produced unexpected additional messages'}
    $authHealth=Invoke-TeamDocker -Arguments @('exec',$auth,'curl','-fsS','http://127.0.0.1:8080/actuator/health') -SecretBearing
    if(($authHealth|ConvertFrom-Json).status -ne 'UP'){throw 'Live Gmail Auth health failed'}
    Write-Output "GMAIL_DELIVERY PASS runtime_worker=true postgres_outbox=sent_at_set encrypted_payload=cleared attempts=$expectedAttempts retry_tested=$TestRetry recipient=sender_self tls_hostname_validation=true anonymous=401 secrets_logged=false"
}finally{
    foreach($container in @($gateway,$auth,$redis,$pg)){try{Invoke-TeamDocker -Arguments @('rm','-f','-v',$container)|Out-Null}catch{}}
    try{Invoke-TeamDocker -Arguments @('network','rm',$network)|Out-Null}catch{}
    $resolved=(Resolve-Path $temp).Path;if(-not $resolved.StartsWith([IO.Path]::GetFullPath($env:TEMP)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe SMTP fixture cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force
}
