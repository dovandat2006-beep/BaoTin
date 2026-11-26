#Requires -Version 7.4
[CmdletBinding()]
param([string]$Python='C:\Users\ngoan\AppData\Roaming\uv\tools\graphifyy\Scripts\python.exe',
    [string]$DeployScript=(Join-Path $PSScriptRoot 'Deploy-Jars.ps1'))
. (Join-Path $PSScriptRoot '../team/Team.Common.ps1')
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$id=[guid]::NewGuid().ToString('N').Substring(0,8)
$testRoot="M:\BaoTin-direct-check-$id";New-Item -ItemType Directory $testRoot|Out-Null
$acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetAccessRuleProtection($true,$false)
foreach($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')){$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid),'FullControl','ContainerInherit,ObjectInherit','None','Allow'))}
Set-Acl -LiteralPath $testRoot -AclObject $acl
$pg="baotin-jar-pg-$id";$redis="baotin-jar-redis-$id";$server=$null
$env:TEMP=$testRoot;$env:TMP=$testRoot
$javaHome='C:\Users\ngoan\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2';$java=Join-Path $javaHome 'bin/java.exe'
$services=@('API-Gateway','Auth-Service','Threat-Service','Report-Service','Reputation-Service','Model-Inference-Service','Notification-Service','Search-Service','Campaign-Service','Analytics-Service','Frontend')
foreach($port in [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()){if($port.Port -in @(8090,8091)+@(8181..8189)){throw 'Direct staging ports occupied; existing listeners preserved'}}
function Password {[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))}
try{
    $runtime=Password;$migrator=Password;$bootstrap=Password;$redisPassword=Password;$token=Password
    Invoke-TeamDocker -Arguments @('run','-d','--name',$pg,'-p','127.0.0.1::5432','-e',"POSTGRES_PASSWORD=$bootstrap",'-e','POSTGRES_DB=baotin','postgres:17-alpine') -SecretBearing|Out-Null
    for($i=0;$i -lt 60;$i++){docker --context desktop-linux exec $pg pg_isready -h 127.0.0.1 -U postgres -d baotin 2>$null|Out-Null;if($LASTEXITCODE -eq 0){break};Start-Sleep -Milliseconds 500}
    $dbPort=(Invoke-TeamDocker -Arguments @('port',$pg,'5432/tcp')).Trim().Split(':')[-1];$dbUrl="jdbc:postgresql://127.0.0.1:$dbPort/baotin"
    $sql=[IO.File]::ReadAllText((Join-Path $root 'Auth-Service/src/main/resources/db/provision/before-migrate.sql'))+"`nALTER ROLE baotin_auth PASSWORD '$runtime'; ALTER ROLE baotin_auth_migrator PASSWORD '$migrator';"
    $sql|docker --context desktop-linux exec -i $pg psql -v ON_ERROR_STOP=1 -U postgres -d baotin 2>$null|Out-Null;if($LASTEXITCODE -ne 0){throw 'Direct fixture provisioning failed'}
    $env:DB_URL=$dbUrl;$env:DB_MIGRATION_USER='baotin_auth_migrator';$env:DB_MIGRATION_PASSWORD=$migrator
    $authJar=Join-Path $root 'Auth-Service/build/libs/Auth-Service-0.0.1-SNAPSHOT.jar'
    $out=& $java '-Dloader.main=com.baotin.auth.operator.DatabaseMigration' -cp $authJar org.springframework.boot.loader.launch.PropertiesLauncher 2>&1
    if($LASTEXITCODE -ne 0){throw 'Direct fixture Java migration failed; diagnostics withheld'}
    Remove-Item Env:DB_MIGRATION_PASSWORD,Env:DB_MIGRATION_USER
    [IO.File]::ReadAllText((Join-Path $root 'Auth-Service/src/main/resources/db/provision/after-migrate.sql'))|docker --context desktop-linux exec -i $pg psql -v ON_ERROR_STOP=1 -U postgres -d baotin 2>$null|Out-Null;if($LASTEXITCODE -ne 0){throw 'Direct fixture grants failed'}
    Invoke-TeamDocker -Arguments @('run','-d','--name',$redis,'-p','127.0.0.1::6379','redis:7-alpine','redis-server','--requirepass',$redisPassword) -SecretBearing|Out-Null
    $redisPort=(Invoke-TeamDocker -Arguments @('port',$redis,'6379/tcp')).Trim().Split(':')[-1]
    $authConfig=Join-Path $testRoot 'auth-config';$gatewayConfig=Join-Path $testRoot 'gateway-config'
    New-Item -ItemType Directory $authConfig,$gatewayConfig|Out-Null
    [IO.File]::WriteAllText((Join-Path $authConfig 'spring.datasource.password'),$runtime)
    [IO.File]::WriteAllText((Join-Path $authConfig 'auth.mail.enabled'),'false')
    [IO.File]::WriteAllText((Join-Path $gatewayConfig 'spring.data.redis.password'),$redisPassword)
    $rsa=[Security.Cryptography.RSA]::Create(3072);try{[IO.File]::WriteAllText((Join-Path $testRoot 'private.pem'),$rsa.ExportPkcs8PrivateKeyPem())}finally{$rsa.Dispose()}
    [IO.File]::WriteAllBytes((Join-Path $testRoot 'encryption.bin'),[Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $tokenFile=Join-Path $testRoot 'synthetic.token';[IO.File]::WriteAllText($tokenFile,$token)
    $artifacts=@{};foreach($service in $services){if($service -ne 'Frontend'){$artifacts[$service]=Join-Path $root "$service/build/libs/$service-0.0.1-SNAPSHOT.jar"}}
    $webTar=Join-Path $testRoot 'web.tar.gz';& tar -czf $webTar -C (Join-Path $root 'Frontend/dist') .;if($LASTEXITCODE -ne 0){throw 'Static artifact packaging failed'};$artifacts['Frontend']=$webTar
    $portFile=Join-Path $testRoot 'fixture-port';$configFile=Join-Path $testRoot 'artifact-fixture.json'
    @{token=$token;portFile=$portFile;artifacts=$artifacts}|ConvertTo-Json|Set-Content -LiteralPath $configFile
    $server=Start-Process -FilePath $Python -ArgumentList @((Join-Path $PSScriptRoot 'artifact-fixture.py'),$configFile) -WindowStyle Hidden -PassThru
    for($i=0;$i -lt 40 -and -not(Test-Path $portFile);$i++){Start-Sleep -Milliseconds 100}
    $fixture='http://127.0.0.1:'+([IO.File]::ReadAllText($portFile))
    function Invoke-WebRequest {
        [CmdletBinding()]param($Uri,$Method='GET',$Headers,$Body,$Credential,$Authentication,[switch]$AllowUnencryptedAuthentication,[switch]$NoProxy,$MaximumRedirection,[switch]$SkipHttpErrorCheck,$TimeoutSec,$OutFile)
        $p=@{};foreach($key in $PSBoundParameters.Keys){$p[$key]=$PSBoundParameters[$key]};if($Uri.StartsWith('http://[::1]:8080/')){$p.Uri=$Uri.Replace('http://[::1]:8080/',$fixture+'/')}
        try{Microsoft.PowerShell.Utility\Invoke-WebRequest @p}catch{
            if($OutFile){
                $status=0;if($_.Exception.PSObject.Properties['Response'] -and $_.Exception.Response){$status=[int]$_.Exception.Response.StatusCode}
                $kinds=[Collections.Generic.List[string]]::new();$errorObject=$_.Exception
                while($errorObject){$kinds.Add($errorObject.GetType().Name+':'+$errorObject.HResult);$errorObject=$errorObject.InnerException}
                Write-Host "DIRECT_FIXTURE_HTTP_FAILURE artifact=$([IO.Path]::GetFileName($OutFile)) status=$status kinds=$($kinds -join ',') free_bytes=$((Get-PSDrive M).Free)"
            }
            throw
        }
    }
    function Invoke-RestMethod {
        [CmdletBinding()]param($Uri,$Method='GET',$Headers,$Body,$ContentType,$Credential,$Authentication,[switch]$AllowUnencryptedAuthentication,[switch]$NoProxy,$MaximumRedirection,$TimeoutSec)
        $p=@{};foreach($key in $PSBoundParameters.Keys){$p[$key]=$PSBoundParameters[$key]};if($Uri.StartsWith('http://[::1]:8080/')){$p.Uri=$Uri.Replace('http://[::1]:8080/',$fixture+'/')}
        Microsoft.PowerShell.Utility\Invoke-RestMethod @p
    }
    $envFile=Join-Path $testRoot 'host.env';$stateRoot=Join-Path $testRoot 'runtime'
    [IO.File]::WriteAllLines($envFile,@("RUNTIME_ROOT=$stateRoot","JAVA_HOME=$javaHome","NODE_EXE=$((Get-Command node).Source)",'JENKINS_URL=http://[::1]:8080/','JENKINS_USERNAME=fixture-user',"JENKINS_API_TOKEN_FILE=$tokenFile","AUTH_CONFIG_DIR=$authConfig","GATEWAY_CONFIG_DIR=$gatewayConfig","JWT_PRIVATE_KEY_PATH=$(Join-Path $testRoot 'private.pem')","AUTH_ENCRYPTION_KEY_PATH=$(Join-Path $testRoot 'encryption.bin')","DB_URL=$dbUrl","DB_PASSWORD_FILE=$(Join-Path $authConfig 'spring.datasource.password')","DB_MIGRATION_PASSWORD_FILE=$tokenFile",'REDIS_HOST=127.0.0.1',"REDIS_PORT=$redisPort",'AUTH_ALLOWED_ORIGINS=https://localhost:9444','JWT_KEY_ID=jar-fixture','API_PORT=8090','WEB_PORT=8091'))
    $buildsFile=Join-Path $testRoot 'builds.json';$builds=@{};foreach($name in $services){$builds[$name]=1};$builds|ConvertTo-Json|Set-Content $buildsFile
    & $DeployScript -EnvFile $envFile -BuildsFile $buildsFile
    $password=Password;$name="jar$id"
    $profile=Microsoft.PowerShell.Utility\Invoke-RestMethod 'http://127.0.0.1:8090/api/v1/auth/register' -NoProxy -Method Post -ContentType application/json -Body (@{username=$name;email="$name@example.test";password=$password;displayName='Jar fixture'}|ConvertTo-Json)
    $login=Microsoft.PowerShell.Utility\Invoke-RestMethod 'http://127.0.0.1:8090/api/v1/auth/login' -NoProxy -Method Post -ContentType application/json -Body (@{identifier=$name;password=$password}|ConvertTo-Json)
    $me=Microsoft.PowerShell.Utility\Invoke-RestMethod 'http://127.0.0.1:8090/api/v1/auth/me' -NoProxy -Headers @{Authorization="Bearer $($login.tokens.accessToken)"}
    if($me.id -ne $profile.id -or $me.role -ne 'USER'){throw 'Direct JWT/profile/role failed'}
    $denied=Microsoft.PowerShell.Utility\Invoke-WebRequest 'http://127.0.0.1:8090/api/v1/auth/admin/users' -NoProxy -SkipHttpErrorCheck -Headers @{Authorization="Bearer $($login.tokens.accessToken)";'X-Roles'='ADMIN'}
    if($denied.StatusCode -ne 403){throw 'Direct forged ADMIN header accepted'}
    "UPDATE auth.users SET email_verified=true WHERE id='$($profile.id)'"|docker --context desktop-linux exec -i $pg psql -v ON_ERROR_STOP=1 -U postgres -d baotin 2>$null|Out-Null
    if($LASTEXITCODE -ne 0){throw 'Fixture verified-contact setup failed'}
    $enrollment=Microsoft.PowerShell.Utility\Invoke-RestMethod 'http://127.0.0.1:8090/api/v1/auth/mfa/enrollments' -NoProxy -Method Post -ContentType application/json -Headers @{Authorization="Bearer $($login.tokens.accessToken)"} -Body (@{password=$password}|ConvertTo-Json)
    foreach($name in $services){$builds[$name]=2};$builds|ConvertTo-Json|Set-Content $buildsFile
    & $DeployScript -EnvFile $envFile -BuildsFile $buildsFile
    $me=Microsoft.PowerShell.Utility\Invoke-RestMethod 'http://127.0.0.1:8090/api/v1/auth/me' -NoProxy -Headers @{Authorization="Bearer $($login.tokens.accessToken)"}
    if($me.id -ne $profile.id){throw 'Direct restart lost data/JWT session'}
    $origin='https://localhost:9444'
    $web=Microsoft.PowerShell.Utility\Invoke-WebRequest 'http://127.0.0.1:8090/api/v1/auth/web/login' -NoProxy -Method Post -Headers @{Origin=$origin} -ContentType application/json -Body (@{identifier=$me.username;password=$password}|ConvertTo-Json)
    $webBody=$web.Content|ConvertFrom-Json;$cookie=@($web.Headers['Set-Cookie']|Where-Object {$_ -like '__Host-baotin-refresh=*'})[0].Split(';')[0]
    if($web.StatusCode -ne 200 -or ($web.Headers['Set-Cookie'] -join '') -notmatch ';\s*Secure' -or $webBody.PSObject.Properties.Name -contains 'refreshToken'){throw 'Direct Web cookie transport failed'}
    $missing=Microsoft.PowerShell.Utility\Invoke-WebRequest 'http://127.0.0.1:8090/api/v1/auth/web/refresh' -NoProxy -Method Post -Headers @{Origin=$origin;Cookie=$cookie} -SkipHttpErrorCheck
    if($missing.StatusCode -ne 403){throw 'Direct Web CSRF requirement bypassed'}
    $refresh=Microsoft.PowerShell.Utility\Invoke-WebRequest 'http://127.0.0.1:8090/api/v1/auth/web/refresh' -NoProxy -Method Post -Headers @{Origin=$origin;Cookie=$cookie;'X-CSRF-TOKEN'=$webBody.csrfToken}
    $refreshed=$refresh.Content|ConvertFrom-Json;$nextCookie=@($refresh.Headers['Set-Cookie']|Where-Object {$_ -like '__Host-baotin-refresh=*'})[0].Split(';')[0]
    if($refresh.StatusCode -ne 200 -or $nextCookie -eq $cookie -or $refreshed.csrfToken -eq $webBody.csrfToken){throw 'Direct cookie/CSRF rotation failed'}
    $logout=Microsoft.PowerShell.Utility\Invoke-WebRequest 'http://127.0.0.1:8090/api/v1/auth/web/logout' -NoProxy -Method Post -Headers @{Origin=$origin;Cookie=$nextCookie;'X-CSRF-TOKEN'=$refreshed.csrfToken}
    if($logout.StatusCode -ne 204){throw 'Direct Web logout failed'}
    $stateFile=Join-Path $stateRoot 'active.json';$stateHash=(Get-FileHash $stateFile).Hash
    try{
        "UPDATE auth.runtime_metadata SET value='2' WHERE name='readerFloor'"|docker --context desktop-linux exec -i $pg psql -v ON_ERROR_STOP=1 -U postgres -d baotin 2>$null|Out-Null
        $failed=$false;try{& $DeployScript -EnvFile $envFile -BuildsFile $buildsFile}catch{if($_.Exception.Message -ne 'Runtime JAR is incompatible with database reader/schema/cipher floors'){throw};$failed=$true}
        if(-not $failed -or (Get-FileHash $stateFile).Hash -ne $stateHash){throw 'Direct reader-floor rejection mutated active state'}
    }finally{"UPDATE auth.runtime_metadata SET value='1' WHERE name='readerFloor'"|docker --context desktop-linux exec -i $pg psql -v ON_ERROR_STOP=1 -U postgres -d baotin 2>$null|Out-Null}
    try{
        "UPDATE auth.runtime_metadata SET value='future-v2' WHERE name='cipherFloor'"|docker --context desktop-linux exec -i $pg psql -v ON_ERROR_STOP=1 -U postgres -d baotin 2>$null|Out-Null
        $failed=$false;try{& $DeployScript -EnvFile $envFile -BuildsFile $buildsFile}catch{if($_.Exception.Message -ne 'Runtime JAR is incompatible with database reader/schema/cipher floors'){throw};$failed=$true}
        if(-not $failed -or (Get-FileHash $stateFile).Hash -ne $stateHash){throw 'Direct cipher-floor rejection mutated active state'}
    }finally{"UPDATE auth.runtime_metadata SET value='BK1' WHERE name='cipherFloor'"|docker --context desktop-linux exec -i $pg psql -v ON_ERROR_STOP=1 -U postgres -d baotin 2>$null|Out-Null}
    if(@(Get-ChildItem (Join-Path $stateRoot 'releases') -Directory).Count -ne 2){throw 'Rejected compatibility releases leaked downloaded artifacts'}
    foreach($name in $services){$builds[$name]=3};$builds|ConvertTo-Json|Set-Content $buildsFile
    $failed=$false;try{& $DeployScript -EnvFile $envFile -BuildsFile $buildsFile}catch{if($_.Exception.Message -ne 'JAR/runtime database compatibility probe failed; raw diagnostics withheld'){throw};$failed=$true}
    if(-not $failed -or (Get-FileHash $stateFile).Hash -ne $stateHash){throw 'Invalid artifact rejection mutated active state'}
    foreach($name in $services){$builds[$name]=4};$builds|ConvertTo-Json|Set-Content $buildsFile
    $failed=$false;try{& $DeployScript -EnvFile $envFile -BuildsFile $buildsFile}catch{if($_.Exception.Message -ne 'Deployment failed; previous runtime restored when present'){throw};$failed=$true}
    if(-not $failed){throw 'Broken Gateway startup unexpectedly succeeded'}
    if(@(Get-ChildItem (Join-Path $stateRoot 'releases') -Directory).Count -ne 2){throw 'Failed startup retained rejected release artifacts'}
    $me=Microsoft.PowerShell.Utility\Invoke-RestMethod 'http://127.0.0.1:8090/api/v1/auth/me' -NoProxy -Headers @{Authorization="Bearer $($login.tokens.accessToken)"}
    if($me.id -ne $profile.id){throw 'Rollback lost direct JWT session'}
    # Confirm an enrollment encrypted before restart using the restored actual JAR.
    $alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';$bits=0;$buffer=0;$secret=[Collections.Generic.List[byte]]::new()
    foreach($c in $enrollment.secret.ToCharArray()){$buffer=($buffer -shl 5)-bor $alphabet.IndexOf($c);$bits+=5;if($bits -ge 8){$bits-=8;$secret.Add([byte](($buffer -shr $bits)-band 255));$buffer=$buffer-band ((1-shl $bits)-1)}}
    $counter=[BitConverter]::GetBytes([long][Math]::Floor([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()/30));[Array]::Reverse($counter)
    $hmac=[Security.Cryptography.HMACSHA1]::new($secret.ToArray());try{$digest=$hmac.ComputeHash($counter)}finally{$hmac.Dispose()}
    $offset=$digest[19]-band 15;$number=([long]($digest[$offset]-band 127)-shl 24)-bor ([long]$digest[$offset+1]-shl 16)-bor ([long]$digest[$offset+2]-shl 8)-bor [long]$digest[$offset+3]
    $codes=Microsoft.PowerShell.Utility\Invoke-RestMethod 'http://127.0.0.1:8090/api/v1/auth/mfa/enrollments/confirmation' -NoProxy -Method Post -ContentType application/json -Headers @{Authorization="Bearer $($login.tokens.accessToken)"} -Body (@{code=($number%1000000).ToString('D6')}|ConvertTo-Json)
    if($codes.recoveryCodes.Count -ne 10){throw 'Encrypted MFA enrollment did not survive rollback'}
    $revoked=Microsoft.PowerShell.Utility\Invoke-WebRequest 'http://127.0.0.1:8090/api/v1/auth/me' -NoProxy -SkipHttpErrorCheck -Headers @{Authorization="Bearer $($login.tokens.accessToken)"}
    if($revoked.StatusCode -ne 401){throw 'MFA enrollment did not revoke old session'}
    Write-Output 'DIRECT_RUNTIME PASS backend=10 web=static jwt=USER admin_headers=403 restart=healthy reader_floor=denied cipher_floor=denied invalid_artifact=no_mutation failed_startup=rolled_back session=preserved encrypted_mfa=preserved old_session=401 web_csrf=403 cookie_refresh=rotated web_logout=204 artifact_transport=synthetic database=real runtime=real_jars https_live=false'
}finally{
    $state=Join-Path $testRoot 'runtime/active.json'
    if(Test-Path $state){$saved=Get-Content $state -Raw|ConvertFrom-Json;foreach($item in $saved.processes){$p=Get-Process -Id $item.pid -ErrorAction SilentlyContinue;if($p -and $p.StartTime.ToUniversalTime().Ticks -eq [long]$item.startTicks){Stop-Process -Id $item.pid -Force}}}
    if($server -and -not $server.HasExited){Stop-Process -Id $server.Id -Force}
    foreach($container in $redis,$pg){try{Invoke-TeamDocker -Arguments @('rm','-f','-v',$container)|Out-Null}catch{}}
    $resolved=(Resolve-Path -LiteralPath $testRoot).Path
    if($resolved -ne "M:\BaoTin-direct-check-$id"){throw 'Unexpected direct fixture cleanup target'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
    Write-Output 'DIRECT_RUNTIME_CLEANUP PASS owned_processes=stopped own_containers_volumes=removed fixture_secrets=removed'
}
