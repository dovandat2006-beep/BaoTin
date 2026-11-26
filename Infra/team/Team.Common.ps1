#Requires -Version 7.4
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
function Invoke-TeamDocker {
    param([Parameter(Mandatory)][string[]]$Arguments,[switch]$SecretBearing)
    $output=& docker --context desktop-linux @Arguments 2>&1
    if($LASTEXITCODE -ne 0){if($SecretBearing){throw "Docker operation failed ($($Arguments[0])); secret-bearing diagnostics withheld"};throw "Docker operation failed ($($Arguments[0])): $(($output|ForEach-Object {"$_"}) -join ' ')"}
    return (($output|ForEach-Object {"$_"}) -join "`n")
}
function Read-TeamConfig {
    param([Parameter(Mandatory)][string]$Path)
    $result=@{}
    foreach($line in Get-Content -LiteralPath $Path){
        $text=$line.Trim();if(-not $text -or $text.StartsWith('#')){continue}
        if($text -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$'){throw 'Invalid public configuration line'}
        if($result.ContainsKey($Matches[1])){throw 'Duplicate public configuration key'}
        if($Matches[1] -match '(PASSWORD|TOKEN|CLIENT_SECRET|PRIVATE_KEY_VALUE|JAAS_CONFIG)$'){throw 'Secret values belong in external files'}
        $result[$Matches[1]]=$Matches[2]
    };return $result
}
function Assert-PinnedImage {
    param([Parameter(Mandatory)][string]$Image,[string]$ExpectedRepository)
    if($Image -notmatch '^[a-z0-9][a-z0-9./:_-]+@sha256:[a-f0-9]{64}$'){throw 'Immutable image digest required'}
    if($ExpectedRepository -and $Image.Split('@')[0] -ne $ExpectedRepository){throw 'Unexpected image repository'}
}
function Ensure-TeamImage {
    param([string]$Image)
    Assert-PinnedImage $Image
    $local=& docker --context desktop-linux image inspect $Image --format '{{json .RepoDigests}}' 2>$null
    if($LASTEXITCODE -eq 0){$refs=$local|ConvertFrom-Json;if($Image -in $refs){return}}
    Invoke-TeamDocker -Arguments @('pull',$Image)|Out-Null
}
function Assert-TeamPaths {
    param([hashtable]$Config)
    if($Config['COMPOSE_PROJECT_NAME'] -notin @('baotin-team','baotin-staging')){throw 'Only BảoTin team/staging project names allowed'}
    if($Config['TEAM_SUBNET'] -ne '172.29.47.0/24'){throw 'Fixed private network/peer IPs must remain consistent'}
    if($Config['EDGE_PORT'] -ne '8090'){throw 'Loopback ingress port is fixed at 8090'}
    if($Config['TAILSCALE_API_PORT'] -ne '9443'){throw 'Reserved BảoTin Tailscale port is 9443'}
    if($Config.ContainsKey('GATEWAY_HOST_PROXY_IPV6_CIDR') -and $Config['GATEWAY_HOST_PROXY_IPV6_CIDR']){
        Assert-HostProxyIpv6 $Config['GATEWAY_HOST_PROXY_IPV6_CIDR']
    }
    $secretRoot=[IO.Path]::GetFullPath($Config['BAOTIN_SECRETS_DIR']);$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
    if($secretRoot.TrimEnd('\','/') -eq $workspace.TrimEnd('\','/') -or $secretRoot.StartsWith($workspace+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Secret directory must be outside workspace'}
    return $secretRoot
}
function Assert-HostProxyIpv6 {
    param([string]$Cidr)
    $address=$null
    if($Cidr -notmatch '^([0-9a-fA-F:]+)/128$' -or -not [Net.IPAddress]::TryParse($Matches[1],[ref]$address) -or $address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetworkV6){throw 'Host proxy IPv6 must be one literal ULA /128 peer'}
    $bytes=$address.GetAddressBytes()
    if(($bytes[0] -band 254) -ne 252 -or ($bytes[0] -eq 253 -and $bytes[1] -eq 122 -and $bytes[2] -eq 17 -and $bytes[3] -eq 92 -and $bytes[4] -eq 161 -and $bytes[5] -eq 224)){throw 'Only observed Docker host ULA peer allowed, never a tailnet client'}
}
function Assert-TeamTailnet {
    param([string]$Origins,[string]$HostName,[hashtable]$Serve)
    if(-not $HostName -or $HostName -notmatch '^[a-zA-Z0-9.-]+$'){throw 'Verified tailnet DNS hostname required'}
    foreach($origin in $Origins.Split(',')){
        $uri=$null
        if(-not [uri]::TryCreate($origin.Trim(),[UriKind]::Absolute,[ref]$uri) -or $uri.Scheme -ne 'https' -or $uri.DnsSafeHost -ne $HostName -or $uri.Port -ne 9444 -or $uri.UserInfo -or $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.Fragment -or $origin.Trim() -ne "https://${HostName}:9444"){throw 'Origins must be exact HTTPS tailnet hostname on Expo port 9444'}
    }
    $authority="${HostName}:9443"
    foreach($section in @('Web','AllowFunnel')){
        if($Serve.ContainsKey($section)){
            foreach($key in $Serve[$section].Keys){if($key.EndsWith(':9443') -and $key -ne $authority){throw 'Reserved Tailscale port has a stale or different hostname; operator review required'}}
        }
    }
    if($Serve.ContainsKey('TCP') -and $Serve['TCP'].ContainsKey('9443')){
        $tcp=$Serve['TCP']['9443']
        if(-not $tcp.ContainsKey('HTTPS') -or -not $tcp['HTTPS'] -or $tcp.ContainsKey('TCPForward')){throw 'Reserved Tailscale port belongs to another app'}
    }
    if($Serve.ContainsKey('AllowFunnel') -and $Serve['AllowFunnel'].ContainsKey($authority) -and $Serve['AllowFunnel'][$authority]){throw 'BảoTin HTTPS handler must be tailnet-only, not Funnel'}
    if($Serve.ContainsKey('Web') -and $Serve['Web'].ContainsKey($authority)){
        $handlers=$Serve['Web'][$authority]['Handlers']
        if(-not $handlers.ContainsKey('/') -or $handlers['/']['Proxy'] -ne 'http://127.0.0.1:8090'){throw 'Reserved Tailscale port belongs to another app'}
    }
}
function Test-ReleaseCompatibility {
    param([hashtable]$Auth,[hashtable]$Gateway,[hashtable]$Database)
    foreach($name in @('readerVersion','minimumSchema','migrationVersion','cipherReaders','apiMajor','readerFloorAfterMigration','cipherFloorAfterMigration')){if(-not $Auth.ContainsKey($name)){throw 'Auth compatibility metadata missing'}}
    if($Auth['service'] -ne 'auth-service' -or $Gateway['service'] -ne 'api-gateway'){throw 'Incorrect service capabilities'}
    if([int]$Auth['apiMajor'] -ne [int]$Gateway['apiMajor']){throw 'Auth/Gateway API major incompatible'}
    if([int]$Auth['readerVersion'] -lt [int]$Database['readerFloor']){throw 'Target reader below database floor'}
    if([int]$Auth['minimumSchema'] -gt [int]$Database['schemaRevision']){throw 'Target requires newer schema'}
    if($Database['cipherFloor'] -notin $Auth['cipherReaders']){throw 'Target cannot read stored cipher format'}
}
function Invoke-TeamProvisioning {
    param([string]$EnvFile,[string]$Image,[ValidateSet('before','after')][string]$Phase,[switch]$SetCredentials)
    $config=Read-TeamConfig $EnvFile;$root=Assert-TeamPaths $config
    $sql=Invoke-TeamDocker -Arguments @('run','--rm','--network','none','--read-only','--tmpfs','/tmp','--entrypoint','java',$Image,'-Dloader.main=com.baotin.auth.operator.ProvisioningSql','-cp','/app/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher',$Phase)
    $redactions=[Collections.Generic.List[string]]::new()
    if($SetCredentials){
        foreach($role in @(@('baotin_auth','auth-config/spring.datasource.password'),@('baotin_auth_migrator','migration/password'),@('baotin_auth_operator','operator/password'))){
            $password=[IO.File]::ReadAllText((Join-Path $root $role[1])).TrimEnd()
            if($password -notmatch '^[A-Za-z0-9_-]{32,128}$'){throw 'Database passwords must be high-entropy alphanumeric/base64url file values'}
            $sql+="`nALTER ROLE $($role[0]) PASSWORD '$password';"
            $redactions.Add($password)
        }
    }
    $result=$sql|& docker --context desktop-linux compose --env-file $EnvFile -f (Join-Path $PSScriptRoot 'docker-compose.yml') exec -T postgres psql -v ON_ERROR_STOP=1 -U postgres -d baotin 2>&1
    if($LASTEXITCODE -ne 0){$diagnostic=($result|ForEach-Object {"$_"}) -join ' ';foreach($secret in $redactions){$diagnostic=$diagnostic.Replace($secret,'[REDACTED]')};throw "Provisioning failed: $diagnostic"}
}
function Get-TeamKeyFingerprints {
    param([string]$EnvFile)
    $config=Read-TeamConfig $EnvFile;$root=Assert-TeamPaths $config
    $result=@{}
    foreach($name in @('private.pem','encryption.bin')){$result[$name]=(Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $root "auth-keys/$name")).Hash}
    return $result
}
function Get-ImageCapabilities {
    param([string]$Image,[string]$Service)
    $class=if($Service -eq 'auth-service'){'com.baotin.auth.operator.RuntimeCapabilities'}else{'com.baotin.gateway.RuntimeCapabilities'}
    $output=Invoke-TeamDocker -Arguments @('run','--rm','--network','none','--read-only','--tmpfs','/tmp','--cap-drop','ALL','--memory','256m','--pids-limit','128','--cpus','1','--entrypoint','java',$Image,"-Dloader.main=$class",'-cp','/app/app.jar','org.springframework.boot.loader.launch.PropertiesLauncher')
    $line=($output -split "`n"|Where-Object {$_.Trim().StartsWith('{')}|Select-Object -Last 1)
    if(-not $line){throw 'Image did not report capabilities'};return ($line|ConvertFrom-Json -AsHashtable)
}
function Get-DatabaseCapabilities {
    param([string]$EnvFile)
    $output=Invoke-TeamDocker -Arguments @('compose','--env-file',$EnvFile,'-f',(Join-Path $PSScriptRoot 'docker-compose.yml'),'exec','-T','postgres','psql','-v','ON_ERROR_STOP=1','-U','postgres','-d','baotin','-At','-c','SELECT json_object_agg(name,value) FROM auth.runtime_metadata')
    return ($output.Trim()|ConvertFrom-Json -AsHashtable)
}
function Wait-TeamHealthy {
    param([string]$EnvFile,[int]$Seconds=120)
    $compose=Join-Path $PSScriptRoot 'docker-compose.yml';$deadline=[DateTime]::UtcNow.AddSeconds($Seconds)
    do{
        $text=Invoke-TeamDocker -Arguments @('compose','--env-file',$EnvFile,'-f',$compose,'ps','--format','json');$rows=@()
        foreach($line in ($text -split "`n")){if($line.Trim()){$value=$line|ConvertFrom-Json;if($value -is [array]){$rows+=$value}else{$rows+=,$value}}}
        $healthy=$true
        foreach($name in @('postgres','redis','auth-service','api-gateway','ingress')){$row=$rows|Where-Object {$_.Service -eq $name}|Select-Object -First 1;if(-not $row -or $row.State -ne 'running' -or $row.Health -ne 'healthy'){$healthy=$false}}
        if($healthy){return};Start-Sleep -Seconds 2
    }while([DateTime]::UtcNow -lt $deadline)
    throw 'Team stack did not become healthy'
}
function Test-TeamSmoke {
    $health=Invoke-RestMethod http://127.0.0.1:8090/actuator/health -TimeoutSec 5;if($health.status -ne 'UP'){throw 'Gateway health failed'}
    $jwks=Invoke-RestMethod http://127.0.0.1:8090/.well-known/jwks.json -TimeoutSec 5
    if(-not $jwks.keys -or @($jwks.keys|Where-Object {$_.PSObject.Properties.Name -contains 'd'}).Count){throw 'JWKS smoke failed'}
    $response=Invoke-WebRequest http://127.0.0.1:8090/api/v1/auth/me -SkipHttpErrorCheck -TimeoutSec 5;if([int]$response.StatusCode -ne 401){throw 'Anonymous access not rejected'}
}
function Write-AtomicJson {
    param([string]$Path,$Value)
    $directory=Split-Path -Parent $Path;New-Item -ItemType Directory -Path $directory -Force|Out-Null
    $temp=Join-Path $directory ([IO.Path]::GetRandomFileName());[IO.File]::WriteAllText($temp,($Value|ConvertTo-Json -Depth 12));[IO.File]::Move($temp,$Path,$true)
}
