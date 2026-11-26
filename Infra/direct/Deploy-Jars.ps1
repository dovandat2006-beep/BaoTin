#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$EnvFile,[Parameter(Mandatory)][string]$BuildsFile)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'DetachedProcess.ps1')
. (Join-Path $PSScriptRoot 'JarArtifacts.ps1')
$cfg=@{}
foreach($line in Get-Content -LiteralPath $EnvFile){$line=$line.Trim();if(-not $line -or $line.StartsWith('#')){continue}
    if($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$' -or $cfg.ContainsKey($Matches[1])){throw 'Invalid/duplicate deployment config'}
    if($Matches[1] -match '(PASSWORD|TOKEN|SECRET)$'){throw 'Secret values belong in external files'};$cfg[$Matches[1]]=$Matches[2]
}
function Assert-WebPublicOrigin([string]$Value){
    if(-not $Value){return}
    $uri=$null
    if($Value -match '[^\x21-\x7e]' -or -not [uri]::TryCreate($Value,[UriKind]::Absolute,[ref]$uri) -or $uri.Scheme -ne 'https' -or $uri.UserInfo -or
       $Value -cne $uri.GetLeftPart([UriPartial]::Authority) -or $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.Fragment){throw 'Web public origin must be an exact HTTPS origin'}
}
Assert-WebPublicOrigin $cfg['WEB_PUBLIC_ORIGIN']
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$root=[IO.Path]::GetFullPath($cfg.RUNTIME_ROOT)
if($root.Length -lt 8 -or $root -eq [IO.Path]::GetPathRoot($root) -or $root.StartsWith($workspace+'\',[StringComparison]::OrdinalIgnoreCase) -or $root -eq $workspace){throw 'Runtime root must be an explicit directory outside source workspace'}
$java=Join-Path $cfg.JAVA_HOME 'bin/java.exe';$node=$cfg.NODE_EXE
foreach($key in 'JAVA_HOME','NODE_EXE','AUTH_CONFIG_DIR','GATEWAY_CONFIG_DIR','THREAT_CONFIG_DIR','REPORT_CONFIG_DIR','NOTIFICATION_CONFIG_DIR','REPUTATION_CONFIG_DIR','MODEL_CONFIG_DIR','SEARCH_CONFIG_DIR','MODEL_TEXT_PATH','MODEL_URL_PATH','JWT_PRIVATE_KEY_PATH','AUTH_ENCRYPTION_KEY_PATH','DB_PASSWORD_FILE','JENKINS_API_TOKEN_FILE'){
    if(-not $cfg[$key] -or -not(Test-Path -LiteralPath $cfg[$key])){throw "External deployment path missing: $key"}
    if([IO.Path]::GetFullPath($cfg[$key]).StartsWith($workspace+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Deployment configuration/secrets must be outside source workspace'}
}
if(-not(Test-Path -LiteralPath $java)){throw 'Configured Java executable missing'}
function Assert-BusinessRuntimeConfig([hashtable]$Config,[string]$WorkspaceRoot){
    $pairs=@(@('THREAT_CONFIG_DIR','baotin_threat'),@('REPORT_CONFIG_DIR','baotin_report'),@('NOTIFICATION_CONFIG_DIR','baotin_notification'),@('SEARCH_CONFIG_DIR','baotin_search'))
    if($Config['ANALYTICS_CONFIG_DIR']){$pairs+=,@('ANALYTICS_CONFIG_DIR','baotin_analytics')}
    foreach($pair in $pairs){
        $directory=[IO.Path]::GetFullPath([string]$Config[$pair[0]])
        if(-not(Test-Path -LiteralPath $directory -PathType Container) -or (Get-Item -LiteralPath $directory).LinkType -or
           $directory.StartsWith($WorkspaceRoot+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Regular external business config-tree required'}
        $userFile=Join-Path $directory 'spring.datasource.username'
        $passwordFile=Join-Path $directory 'spring.datasource.password'
        if(-not(Test-Path -LiteralPath $userFile -PathType Leaf) -or (Get-Item -LiteralPath $userFile).LinkType -or
           [IO.File]::ReadAllText($userFile).Trim() -ne $pair[1]){throw 'Business service must use its exact runtime database role'}
        if(-not(Test-Path -LiteralPath $passwordFile -PathType Leaf) -or (Get-Item -LiteralPath $passwordFile).LinkType -or
           (Get-Item -LiteralPath $passwordFile).Length -lt 32 -or (Get-Item -LiteralPath $passwordFile).Length -gt 4096){throw 'Bounded regular runtime database password file required'}
        $urlFile=Join-Path $directory 'spring.datasource.url'
        if(-not(Test-Path -LiteralPath $urlFile -PathType Leaf) -or (Get-Item -LiteralPath $urlFile).LinkType -or
           [IO.File]::ReadAllText($urlFile).Trim() -ne $Config.DB_URL){throw 'Business service must use the reviewed physical database'}
        foreach($forbidden in @('spring.flyway.user','spring.flyway.password','spring.flyway.url','DB_MIGRATION_USER','DB_MIGRATION_PASSWORD','DB_MIGRATION_PASSWORD_FILE')){
            if(Test-Path -LiteralPath (Join-Path $directory $forbidden)){throw 'Business runtime cannot mount migration credentials'}
        }
    }
    if($Config['ANALYTICS_CONFIG_DIR']){
        foreach($setting in @(@('spring.flyway.enabled','false'),@('analytics.security.jwks-uri','http://127.0.0.1:8181/.well-known/jwks.json'))){
            $file=Join-Path $Config.ANALYTICS_CONFIG_DIR $setting[0]
            if(-not(Test-Path -LiteralPath $file -PathType Leaf) -or (Get-Item -LiteralPath $file).LinkType -or [IO.File]::ReadAllText($file).Trim() -ne $setting[1]){throw 'Analytics requires operator-migrated schema and the private Auth JWKS'}
        }
    }
    $notification=[string]$Config.NOTIFICATION_CONFIG_DIR
    foreach($field in @('notification.threat-uri','notification.threat-token')){
        $file=Join-Path $notification $field
        if(-not(Test-Path -LiteralPath $file -PathType Leaf) -or (Get-Item -LiteralPath $file).LinkType -or
           [string]::IsNullOrWhiteSpace([IO.File]::ReadAllText($file))){throw 'Integrated Notification requires the private target bridge'}
    }
}
Assert-BusinessRuntimeConfig $cfg $workspace
if($cfg['GATEWAY_ROUTES_FILE']){
    $routesPath=[IO.Path]::GetFullPath($cfg['GATEWAY_ROUTES_FILE'])
    if($routesPath.StartsWith($workspace+'\',[StringComparison]::OrdinalIgnoreCase) -or
       -not(Test-Path -LiteralPath $routesPath -PathType Leaf) -or (Get-Item -LiteralPath $routesPath).LinkType -or
       (Get-Item -LiteralPath $routesPath).Length -gt 65536){throw 'Bounded regular external reviewed routes file required'}
}
foreach($key in 'API_PORT','WEB_PORT'){if($cfg[$key] -notmatch '^\d{4,5}$' -or [int]$cfg[$key] -gt 65535){throw 'Invalid loopback port'}}
if($cfg.API_PORT -eq $cfg.WEB_PORT){throw 'API and web ports must differ'}
$controller=[uri]$cfg.JENKINS_URL
if($controller.Scheme -ne 'http' -or $controller.DnsSafeHost -notin '::1','127.0.0.1','localhost' -or $controller.Port -ne 8080 -or $controller.UserInfo -or $controller.Query -or $controller.Fragment -or $controller.AbsolutePath -ne '/'){throw 'Identified local Jenkins required'}
$probe=Invoke-WebRequest ($controller.AbsoluteUri+'login') -NoProxy -SkipHttpErrorCheck -MaximumRedirection 0 -TimeoutSec 5
if($probe.StatusCode -ne 200 -or ($probe.Headers['X-Jenkins'] -join '') -notmatch '^\d+\.\d+(\.\d+)?$'){throw 'Jenkins endpoint identification failed'}
$allowed=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')
foreach($rule in (Get-Acl -LiteralPath $cfg.JENKINS_API_TOKEN_FILE).Access){if($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin $allowed){throw 'Restrict Jenkins token file ACL'}}
$credential=[pscredential]::new($cfg.JENKINS_USERNAME,(ConvertTo-SecureString ([IO.File]::ReadAllText($cfg.JENKINS_API_TOKEN_FILE).Trim()) -AsPlainText -Force))
$http=@{Credential=$credential;Authentication='Basic';AllowUnencryptedAuthentication=$true;NoProxy=$true;MaximumRedirection=0;TimeoutSec=120}
$identity=Invoke-RestMethod @http -Uri ($controller.AbsoluteUri+'whoAmI/api/json')
if(-not $identity.authenticated -or $identity.name -ne $cfg.JENKINS_USERNAME){throw 'Jenkins authenticated identity mismatch'}
$names=@('API-Gateway','Auth-Service','Threat-Service','Report-Service','Reputation-Service','Model-Inference-Service','Notification-Service','Search-Service','Campaign-Service','Analytics-Service','Frontend')
$builds=Get-Content -LiteralPath $BuildsFile -Raw|ConvertFrom-Json -AsHashtable
if($builds.Count -ne $names.Count){throw 'Full-stack immutable build manifest required'}
New-Item -ItemType Directory -Force $root|Out-Null
$lock=[IO.File]::Open((Join-Path $root '.deploy.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try {
$release=Join-Path $root ('releases/'+[guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Force $release|Out-Null
function Fetch([string]$Route,[string]$File){try{Invoke-WebRequest @http -Uri ($controller.AbsoluteUri+$Route) -OutFile $File -Verbose:$false -Debug:$false|Out-Null}catch{throw ('Artifact retrieval failed; error kind='+$_.CategoryInfo.Reason+'; raw diagnostics withheld')}}
$reuseDirectory=$null
if(Test-Path -LiteralPath (Join-Path $root 'active.json')){$reuseDirectory=(Get-Content -LiteralPath (Join-Path $root 'active.json') -Raw|ConvertFrom-Json).directory}
$reusedJars=0;$downloadedJars=0
foreach($name in $names){
    if($builds[$name].ToString() -notmatch '^[1-9][0-9]{0,8}$'){throw 'Invalid immutable build number'}
    $base="job/baotin/job/$name/$($builds[$name])/"
    try{$status=Invoke-RestMethod @http -Uri ($controller.AbsoluteUri+$base+'api/json?tree=result,building')}catch{throw 'Build status retrieval failed'}
    if($status.result -ne 'SUCCESS' -or $status.building){throw 'Only completed successful builds can deploy'}
    $file=if($name -eq 'Frontend'){'web.tar.gz'}else{"$name-0.0.1-SNAPSHOT.jar"}
    $route=if($name -eq 'Frontend'){'build/web.tar.gz'}else{"build/libs/$file"}
    $destination=Join-Path $release $file
    if($name -eq 'Frontend'){Fetch ($base+'artifact/'+$route) $destination;continue}
    try{$evidence=Invoke-RestMethod @http -Uri ($controller.AbsoluteUri+$base+'artifact/build/release-evidence.json')}catch{throw 'Immutable JAR evidence retrieval failed; no runtime stopped'}
    $expectedHash=[string]$evidence.jarSha256
    if($expectedHash -cnotmatch '^[a-f0-9]{64}$'){throw 'Immutable CI JAR SHA256 required'}
    if(Try-ReuseVerifiedJar $root $reuseDirectory $destination $file $expectedHash){$reusedJars++}
    else{Fetch ($base+'artifact/'+$route) $destination;$downloadedJars++}
    if((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -cne $expectedHash){throw 'Downloaded/reused JAR differs from immutable CI evidence'}
}
Write-Output "JAR_ARTIFACT_STAGE PASS reused=$reusedJars downloaded=$downloadedJars hashVerified=$($reusedJars+$downloadedJars) web=downloaded"
$web=Join-Path $release 'web';New-Item -ItemType Directory $web|Out-Null
$entries=& tar -tzf (Join-Path $release 'web.tar.gz')
if($LASTEXITCODE -ne 0 -or @($entries|Where-Object {$_ -match '(^/|^[A-Za-z]:|(^|/)\.\.(/|$))'}).Count){throw 'Invalid web archive paths'}
& tar -xzf (Join-Path $release 'web.tar.gz') -C $web;if($LASTEXITCODE -ne 0 -or -not(Test-Path "$web/index.html")){throw 'Invalid static web artifact'}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'web-server.cjs') -Destination $release
$ports=@{'API-Gateway'=[int]$cfg.API_PORT;'Auth-Service'=8181;'Threat-Service'=8182;'Report-Service'=8183;'Reputation-Service'=8184;'Model-Inference-Service'=8185;'Notification-Service'=8186;'Search-Service'=8187;'Campaign-Service'=8188;'Analytics-Service'=8189;'Frontend'=[int]$cfg.WEB_PORT}
if(@($ports.Values|Select-Object -Unique).Count -ne 11){throw 'Runtime ports must be unique'}
function Stop-Owned($State){foreach($p in $State.processes){$process=Get-Process -Id $p.pid -ErrorAction SilentlyContinue
    if($process){if($process.StartTime.ToUniversalTime().Ticks -ne [long]$p.startTicks){throw 'PID ownership changed; no unrelated process stopped'};Stop-Process -Id $p.pid -Force}
}}
function Launch([string]$Directory){
    $processes=[Collections.Generic.List[object]]::new()
    try{foreach($name in $names){
        $exe=if($name -eq 'Frontend'){$node}else{$java}
        $args=if($name -eq 'Frontend'){@((Join-Path $Directory 'web-server.cjs'),(Join-Path $Directory 'web'),"$($ports[$name])")}else{@('-Xms64m','-Xmx256m','-XX:ActiveProcessorCount=2','-jar',(Join-Path $Directory "$name-0.0.1-SNAPSHOT.jar"))}
        $psi=[Diagnostics.ProcessStartInfo]::new($exe);$psi.UseShellExecute=$false;$psi.CreateNoWindow=$true;$psi.WorkingDirectory=$Directory
        foreach($arg in $args){$psi.ArgumentList.Add($arg)}
        $psi.Environment.Clear()
        foreach($key in 'SystemRoot','WINDIR','PATH','USERPROFILE','APPDATA','LOCALAPPDATA','TEMP','TMP'){$value=[Environment]::GetEnvironmentVariable($key);if($value){$psi.Environment[$key]=$value}}
        if($name -eq 'Frontend' -and $cfg['WEB_PUBLIC_ORIGIN']){$psi.Environment['BAOTIN_WEB_PUBLIC_ORIGIN']=$cfg['WEB_PUBLIC_ORIGIN']}
        if($name -ne 'Frontend'){$psi.Environment['SERVER_ADDRESS']='127.0.0.1';$psi.Environment['SERVER_PORT']="$($ports[$name])";$psi.Environment['SPRING_PROFILES_ACTIVE']='team'}
        if($name -in @('Threat-Service','Report-Service','Notification-Service')){
            $configKey=$name.Split('-')[0].ToUpperInvariant()+'_CONFIG_DIR'
            $configDirectory=$cfg[$configKey]
            $psi.Environment['SPRING_CONFIG_IMPORT']='configtree:'+($configDirectory.Replace('\','/').TrimEnd('/')+'/')
            $psi.Environment['SPRING_FLYWAY_ENABLED']='false'
            $psi.Environment['DB_MIGRATION_ENABLED']='false'
            $psi.Environment['AUTH_JWKS_URI']='http://127.0.0.1:8181/.well-known/jwks.json'
            if($name -eq 'Notification-Service'){$psi.Environment['NOTIFICATION_ALLOWED_ORIGINS']=$cfg.AUTH_ALLOWED_ORIGINS}
        }
        if($name -eq 'Auth-Service'){
            $psi.Environment['SPRING_CONFIG_IMPORT']='configtree:'+($cfg.AUTH_CONFIG_DIR.Replace('\','/').TrimEnd('/')+'/')
            foreach($key in 'DB_URL','JWT_PRIVATE_KEY_PATH','AUTH_ENCRYPTION_KEY_PATH','JWT_KEY_ID','AUTH_ALLOWED_ORIGINS'){$psi.Environment[$key]=$cfg[$key]}
            $psi.Environment['DB_USER']='baotin_auth';$psi.Environment['DB_MIGRATION_ENABLED']='false';$psi.Environment['FIREBASE_PHONE_ENABLED']='false';$psi.Environment['AUTH_EVENTS_ENABLED']='false'
            $psi.Environment['AUTH_TRUSTED_GATEWAY_CIDRS']='127.0.0.1/32'
        }
        if($name -eq 'Reputation-Service'){
            $psi.Environment['SPRING_CONFIG_IMPORT']='configtree:'+($cfg.REPUTATION_CONFIG_DIR.Replace('\','/').TrimEnd('/')+'/')
            $psi.Environment['DB_URL']=$cfg.DB_URL
            $psi.Environment['DB_USER']='baotin_reputation'
            $psi.Environment['AUTH_JWKS_URI']='http://127.0.0.1:8181/.well-known/jwks.json'
        }
        if($name -eq 'Analytics-Service' -and $cfg['ANALYTICS_CONFIG_DIR']){
            $psi.Environment['SPRING_CONFIG_IMPORT']='configtree:'+($cfg.ANALYTICS_CONFIG_DIR.Replace('\','/').TrimEnd('/')+'/')
            $psi.Environment['DB_URL']=$cfg.DB_URL;$psi.Environment['DB_USER']='baotin_analytics'
            $psi.Environment['DB_MIGRATION_ENABLED']='false';$psi.Environment['SPRING_FLYWAY_ENABLED']='false'
            $psi.Environment['JWT_JWKS_URI']='http://127.0.0.1:8181/.well-known/jwks.json'
        }
        if($name -eq 'Search-Service'){
            $psi.Environment['SPRING_CONFIG_IMPORT']='configtree:'+($cfg.SEARCH_CONFIG_DIR.Replace('\','/').TrimEnd('/')+'/')
            $psi.Environment['SPRING_FLYWAY_ENABLED']='false'
            $psi.Environment['SEARCH_EVENTS_ENABLED']='true'
        }
        if($name -eq 'Model-Inference-Service'){
            $psi.Environment['SPRING_CONFIG_IMPORT']='configtree:'+($cfg.MODEL_CONFIG_DIR.Replace('\','/').TrimEnd('/')+'/')
            $psi.Environment['MODEL_TEXT_PATH']=$cfg.MODEL_TEXT_PATH
            $psi.Environment['MODEL_URL_PATH']=$cfg.MODEL_URL_PATH
        }
        if($name -eq 'API-Gateway'){
            $psi.Environment['SPRING_CONFIG_IMPORT']='configtree:'+($cfg.GATEWAY_CONFIG_DIR.Replace('\','/').TrimEnd('/')+'/')
            if($cfg['GATEWAY_ROUTES_FILE']){$psi.Environment['SPRING_CONFIG_IMPORT']+=',file:'+([IO.Path]::GetFullPath($cfg['GATEWAY_ROUTES_FILE']).Replace('\','/'))}
            foreach($key in 'THREAT_SERVICE_URI','REPORT_SERVICE_URI','REPUTATION_SERVICE_URI','MODEL_INFERENCE_SERVICE_URI','NOTIFICATION_SERVICE_URI','SEARCH_SERVICE_URI','CAMPAIGN_SERVICE_URI','ANALYTICS_SERVICE_URI'){if($cfg[$key]){$psi.Environment[$key]=$cfg[$key]}}
            $psi.Environment['AUTH_SERVICE_URI']='http://127.0.0.1:8181';$psi.Environment['AUTH_JWKS_URI']='http://127.0.0.1:8181/.well-known/jwks.json'
            $psi.Environment['REDIS_HOST']=$cfg.REDIS_HOST;$psi.Environment['REDIS_PORT']=$cfg.REDIS_PORT;$psi.Environment['GATEWAY_ALLOWED_ORIGINS']=$cfg.AUTH_ALLOWED_ORIGINS;$psi.Environment['GATEWAY_TRUSTED_PROXY_CIDRS']='127.0.0.1/32'
        }
        $process=Start-BaoTinDetachedProcess $psi (Join-Path $Directory $name);$processes.Add(@{pid=$process.Id;startTicks=$process.StartTime.ToUniversalTime().Ticks;service=$name})
        Wait-RuntimeService @{directory=$Directory;processes=$processes.ToArray();ports=$ports} $name
    };return @{directory=$Directory;processes=$processes.ToArray();ports=$ports}}catch{Stop-Owned @{processes=$processes.ToArray()};throw}
}
function Wait-RuntimeService($State,[string]$name){$path=if($name -eq 'Frontend'){'healthz'}else{'actuator/health/readiness'};$ready=$false
    for($i=0;$i -lt 90;$i++){
        $owned=@($State.processes|Where-Object service -eq $name);$process=Get-Process -Id $owned[0].pid -ErrorAction SilentlyContinue
        if(-not $process -or $process.StartTime.ToUniversalTime().Ticks -ne [long]$owned[0].startTicks){throw "Owned runtime process stopped: $name"}
        try{$r=Invoke-RestMethod "http://127.0.0.1:$($State.ports[$name])/$path" -NoProxy -TimeoutSec 2;if($r.status -eq 'UP'){$ready=$true;break}}catch{};Start-Sleep -Seconds 1
    };if(-not $ready){throw "Runtime readiness failed: $name"}
}
function Healthy($State){foreach($name in $names){Wait-RuntimeService $State $name}}
# Provision/migration is a distinct operator step: runtime never receives migrator credentials.
# Database must already be at the release's reviewed schema/cipher compatibility level.
$stateFile=Join-Path $root 'active.json';$previous=if(Test-Path $stateFile){Get-Content $stateFile -Raw|ConvertFrom-Json -AsHashtable}else{$null}
function Probe-Jar([string]$Jar,[string]$Class){
    $psi=[Diagnostics.ProcessStartInfo]::new($java);$psi.UseShellExecute=$false;$psi.CreateNoWindow=$true
    $psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
    foreach($arg in @("-Dloader.main=$Class",'-cp',$Jar,'org.springframework.boot.loader.launch.PropertiesLauncher')){$psi.ArgumentList.Add($arg)}
    $psi.Environment['DB_URL']=$cfg.DB_URL;$psi.Environment['DB_USER']='baotin_auth';$psi.Environment['DB_PASSWORD_FILE']=$cfg.DB_PASSWORD_FILE
    $p=[Diagnostics.Process]::Start($psi);$output=$p.StandardOutput.ReadToEnd();$null=$p.StandardError.ReadToEnd();$p.WaitForExit()
    if($p.ExitCode -ne 0){throw 'JAR/runtime database compatibility probe failed; raw diagnostics withheld'}
    $line=$output -split "`n"|Where-Object {$_.Trim().StartsWith('{')}|Select-Object -Last 1
    if(-not $line){throw 'Missing JAR/database compatibility metadata'};return ($line|ConvertFrom-Json -AsHashtable)
}
function Compatible($Caps,$Database){
    if([int]$Caps.readerVersion -lt [int]$Database.readerFloor -or [int]$Caps.minimumSchema -gt [int]$Database.schemaRevision -or $Database.cipherFloor -notin $Caps.cipherReaders){throw 'Runtime JAR is incompatible with database reader/schema/cipher floors'}
}
$authJar=Join-Path $release 'Auth-Service-0.0.1-SNAPSHOT.jar'
$database=Probe-Jar $authJar 'com.baotin.auth.operator.RuntimeDatabaseState'
$capabilities=Probe-Jar $authJar 'com.baotin.auth.operator.RuntimeCapabilities';Compatible $capabilities $database
$keyHashes=@{signing=(Get-FileHash -LiteralPath $cfg.JWT_PRIVATE_KEY_PATH).Hash;encryption=(Get-FileHash -LiteralPath $cfg.AUTH_ENCRYPTION_KEY_PATH).Hash}
if($previous){
    Compatible (Probe-Jar (Join-Path $previous.directory 'Auth-Service-0.0.1-SNAPSHOT.jar') 'com.baotin.auth.operator.RuntimeCapabilities') $database
    if(-not $previous.ContainsKey('keyHashes') -or $previous.keyHashes.signing -ne $keyHashes.signing -or $previous.keyHashes.encryption -ne $keyHashes.encryption){throw 'Normal release cannot overwrite signing/encryption keys; use the separate rotation procedure'}
}
foreach($field in @(@('spring.datasource.username','baotin_auth'),@('spring.flyway.enabled','false'))){
    $file=Join-Path $cfg.AUTH_CONFIG_DIR $field[0]
    if((Test-Path $file) -and [IO.File]::ReadAllText($file).Trim() -ne $field[1]){throw 'Auth runtime configuration must not acquire provisioning/migration identity'}
}
if(-not(Test-Path (Join-Path $cfg.AUTH_CONFIG_DIR 'spring.datasource.password'))){throw 'Auth runtime datasource password config-tree file required'}
foreach($port in [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()){
    if($port.Port -in $ports.Values){
        $name=@($ports.Keys|Where-Object {$ports[$_] -eq $port.Port})[0]
        $owned=@(if($previous){$previous.processes|Where-Object service -eq $name})
        if($owned.Count -ne 1){throw 'Runtime port belongs to an existing unrelated listener'}
        $process=Get-Process -Id $owned[0].pid -ErrorAction SilentlyContinue
        if(-not $process -or $process.StartTime.ToUniversalTime().Ticks -ne [long]$owned[0].startTicks){throw 'Runtime listener ownership is not current'}
    }
}
if($previous){Stop-Owned $previous}
$current=$null
try{
    $current=Launch $release;Healthy $current
    $current['keyHashes']=$keyHashes
    $current['previous']=if($previous){@{directory=$previous.directory;ports=$previous.ports}}else{$null}
    $temp=$stateFile+'.tmp';$current|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $temp;Move-Item -LiteralPath $temp -Destination $stateFile -Force
    Write-Output 'JAR_WEB_DEPLOY PASS backend=10 frontend=web loopback=true readiness=UP docker_image=alternative'
}catch{
    if($current){Stop-Owned $current}
    if($previous){
        $restored=$null
        try{
            $restored=Launch $previous.directory;Healthy $restored
            $restored['keyHashes']=$keyHashes
            $restored|ConvertTo-Json -Depth 6|Set-Content -LiteralPath $stateFile
            Write-Output 'JAR_WEB_AUTO_ROLLBACK PASS readiness=UP'
        }catch{
            if($restored){Stop-Owned $restored}
            throw 'Deployment and previous-runtime recovery both failed; owned partial processes stopped'
        }
    }
    throw 'Deployment failed; previous runtime restored when present'
}
} finally {
    try {
    # Rejecting a release must not accumulate downloaded JARs until the host disk fills.
    if(Test-Path -LiteralPath $release){
        $activeDirectory=$null
        if(Test-Path -LiteralPath (Join-Path $root 'active.json')){
            $saved=Get-Content -LiteralPath (Join-Path $root 'active.json') -Raw|ConvertFrom-Json -AsHashtable
            $activeDirectory=$saved.directory
        }
        if($activeDirectory -ne $release){
            $resolved=(Resolve-Path -LiteralPath $release).Path
            $releasesRoot=[IO.Path]::GetFullPath((Join-Path $root 'releases'))
            if(-not $resolved.StartsWith($releasesRoot+'\',[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^[a-f0-9]{32}$'){throw 'Unexpected rejected-release cleanup target'}
            Remove-Item -LiteralPath $resolved -Recurse -Force
        }
    }
    } finally {$lock.Dispose()}
}
