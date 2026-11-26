#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$EnvFile,[Parameter(Mandatory)][string]$JarEnvFile,
      [string]$AuthJar=(Join-Path $PSScriptRoot '../../../Auth-Service/build/libs/Auth-Service-0.0.1-SNAPSHOT.jar'))
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../../team/Team.Common.ps1')
$deps=Read-TeamConfig $EnvFile;$app=Read-TeamConfig $JarEnvFile
if($deps.POSTGRES_PORT -ne '15432' -or $deps.REDIS_PORT -ne '16379' -or $app.DB_URL -ne 'jdbc:postgresql://127.0.0.1:15432/baotin'){throw 'Reviewed Docker loopback endpoints required'}
$root=[IO.Path]::GetFullPath($deps.BAOTIN_SECRETS_DIR)
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
if($root.StartsWith($workspace+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'External secret directory required'}
$roles=@{'baotin_auth'='auth-config/spring.datasource.password';'baotin_auth_migrator'='db/migrator.password';'baotin_auth_operator'='db/operator.password'}
$allow=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')
$passwords=@{}
foreach($role in $roles.Keys){
    $file=Join-Path $root $roles[$role]
    if((Get-Item -LiteralPath $file).LinkType){throw 'Regular password file required'}
    foreach($rule in (Get-Acl -LiteralPath $file).Access){if($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin $allow){throw 'Restrict DB password ACL'}}
    $passwords[$role]=[IO.File]::ReadAllText($file)
    # The operator-approved generated role passwords are 256-bit random hex, not SQL input.
    if($passwords[$role] -notmatch '^[A-Fa-f0-9]{64}$'){throw 'Generated 256-bit role password file required'}
}
if(@($passwords.Values|Select-Object -Unique).Count -ne 3){throw 'Separate DB role passwords required'}
if([IO.Path]::GetFullPath($app.DB_PASSWORD_FILE) -ne [IO.Path]::GetFullPath((Join-Path $root $roles.baotin_auth))){throw 'Runtime password path mismatch'}
$argsBase=@('compose','--project-name','baotin-deps','--env-file',[IO.Path]::GetFullPath($EnvFile),'-f',(Join-Path $PSScriptRoot 'docker-compose.yml'))
function Invoke-ProvisionSql([string]$Sql){
    $out=$Sql | & docker --context desktop-linux @argsBase exec -T postgres sh -c 'PGPASSWORD="$(cat /run/secrets/postgres-password)" psql -h 127.0.0.1 -U postgres -d baotin -v ON_ERROR_STOP=1 -At' 2>&1
    if($LASTEXITCODE){throw 'Database provisioning failed; raw SQL/diagnostics withheld'}
}
$old=@{};foreach($key in 'BAOTIN_SECRETS_DIR','POSTGRES_PORT','REDIS_PORT','DB_URL','DB_MIGRATION_USER','DB_MIGRATION_PASSWORD','DB_MIGRATION_PASSWORD_FILE','DB_USER','DB_PASSWORD','DB_PASSWORD_FILE'){$old[$key]=[Environment]::GetEnvironmentVariable($key)}
try {
    foreach($key in 'BAOTIN_SECRETS_DIR','POSTGRES_PORT','REDIS_PORT'){[Environment]::SetEnvironmentVariable($key,$deps[$key],'Process')}
    $id=(Invoke-TeamDocker -Arguments ($argsBase+@('ps','-q','postgres'))).Trim()
    $meta=(Invoke-TeamDocker -Arguments @('inspect',$id))|ConvertFrom-Json
    if($meta.State.Health.Status -ne 'healthy' -or $meta.Config.Labels.'com.docker.compose.project' -ne 'baotin-deps' -or $meta.Config.Labels.'com.docker.compose.service' -ne 'postgres'){throw 'Identified healthy dependency PostgreSQL required'}
    $before=[IO.File]::ReadAllText((Join-Path $workspace 'Auth-Service/src/main/resources/db/provision/before-migrate.sql'))
    $changes=($roles.Keys|ForEach-Object {"ALTER ROLE $_ PASSWORD '$($passwords[$_])';"}) -join "`n"
    Invoke-ProvisionSql ("BEGIN;`n"+$before+"`n"+$changes+"`nCOMMIT;")
    $passwords.Clear();$changes=$null
    $java=Join-Path $app.JAVA_HOME 'bin/java.exe'
    if(-not(Test-Path -LiteralPath $java) -or -not(Test-Path -LiteralPath $AuthJar)){throw 'Current JDK21/Auth JAR required'}
    $env:DB_URL=$app.DB_URL+'?connectTimeout=5&socketTimeout=15';$env:DB_MIGRATION_USER='baotin_auth_migrator';$env:DB_MIGRATION_PASSWORD=$null;$env:DB_MIGRATION_PASSWORD_FILE=Join-Path $root $roles.baotin_auth_migrator
    $out=& $java '-Dloader.main=com.baotin.auth.operator.DatabaseMigration' -cp $AuthJar org.springframework.boot.loader.launch.PropertiesLauncher 2>&1
    if($LASTEXITCODE){throw 'Dedicated migration failed; raw diagnostics withheld'}
    Invoke-ProvisionSql ([IO.File]::ReadAllText((Join-Path $workspace 'Auth-Service/src/main/resources/db/provision/after-migrate.sql')))
    $env:DB_USER='baotin_auth';$env:DB_PASSWORD=$null;$env:DB_PASSWORD_FILE=$app.DB_PASSWORD_FILE
    $out=& $java '-Dloader.main=com.baotin.auth.operator.RuntimeDatabaseState' -cp $AuthJar org.springframework.boot.loader.launch.PropertiesLauncher 2>&1
    if($LASTEXITCODE){throw 'Runtime DB compatibility probe failed; raw diagnostics withheld'}
    $state=($out|Where-Object {"$_" -match '^\{'}|Select-Object -Last 1)|ConvertFrom-Json
    if($state.schemaRevision -ne '9' -or $state.readerFloor -ne '1' -or $state.cipherFloor -ne 'BK1'){throw 'Unexpected current Auth schema compatibility state'}
    'DOCKER_DATABASE_PROVISION PASS database=baotin schema=auth revision=9 runtime=baotin_auth migrator=separate operator=separate passwords=distinct runtime_probe=connected'
} finally {
    $passwords.Clear()
    foreach($key in $old.Keys){[Environment]::SetEnvironmentVariable($key,$old[$key],'Process')}
}
