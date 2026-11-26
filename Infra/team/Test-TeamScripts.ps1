#Requires -Version 7.4
. (Join-Path $PSScriptRoot 'Team.Common.ps1')
$count=0
function Assert-Throws([scriptblock]$Action){try{& $Action;throw 'EXPECTED_FAILURE_NOT_THROWN'}catch{if($_.Exception.Message -eq 'EXPECTED_FAILURE_NOT_THROWN'){throw}}}
Assert-PinnedImage ('ghcr.io/baotin/auth-service@sha256:'+('a'*64));$count++
Assert-HostProxyIpv6 'fd5e:a313:f28:2::6/128';$count++
foreach($invalid in @('fd5e:a313:f28:2::/64','::/0','::1/128','2001:db8::1/128','fd7a:115c:a1e0::1/128','100.64.0.1/32','host.docker.internal/128','fd5e:a313:f28:2::6/128,fd00::1/128')){Assert-Throws {Assert-HostProxyIpv6 $invalid};$count++}
foreach($image in @('auth-service:latest','auth-service:main','ghcr.io/x/auth-service@sha256:bad','ghcr.io/x/auth-service;whoami@sha256:'+('a'*64))){Assert-Throws {Assert-PinnedImage $image};$count++}
$auth=@{service='auth-service';readerVersion=1;minimumSchema=9;migrationVersion=9;apiMajor=1;cipherReaders=@('BK1','legacy-v1');readerFloorAfterMigration=1;cipherFloorAfterMigration='BK1'}
$gateway=@{service='api-gateway';apiMajor=1}
$database=@{readerFloor=1;schemaRevision=9;cipherFloor='BK1'}
Test-ReleaseCompatibility $auth $gateway $database;$count++
$old=$auth.Clone();$old['cipherReaders']=@('legacy-v1');Assert-Throws {Test-ReleaseCompatibility $old $gateway $database};$count++
$old=$auth.Clone();$old['readerVersion']=0;Assert-Throws {Test-ReleaseCompatibility $old $gateway $database};$count++
$old=$auth.Clone();$old['minimumSchema']=10;Assert-Throws {Test-ReleaseCompatibility $old $gateway $database};$count++
Assert-Throws {Test-ReleaseCompatibility @{} $gateway $database};$count++
$hostName='fixture-tailnet.ts.net';$origin="https://${hostName}:9444"
Assert-TeamTailnet $origin $hostName @{};$count++
foreach($invalid in @("https://${hostName}:9444@evil.example","https://${hostName}:9443","$origin/","$origin/path","$origin/?x=1","$origin/#fragment","http://${hostName}:9444","$origin,https://evil.example:9444","$origin,")){
    Assert-Throws {Assert-TeamTailnet $invalid $hostName @{}};$count++
}
Assert-Throws {Assert-TeamTailnet $origin $hostName @{AllowFunnel=@{"${hostName}:9443"=$true}}};$count++
Assert-Throws {Assert-TeamTailnet $origin $hostName @{Web=@{"${hostName}:9443"=@{Handlers=@{'/'=@{Proxy='http://127.0.0.1:9999'}}}}}};$count++
Assert-Throws {Assert-TeamTailnet $origin $hostName @{TCP=@{'9443'=@{TCPForward='localhost:9000'}}}};$count++
Assert-Throws {Assert-TeamTailnet $origin $hostName @{Web=@{'old-host.ts.net:9443'=@{Handlers=@{'/'=@{Proxy='http://127.0.0.1:8090'}}}}}};$count++
Assert-Throws {Assert-TeamTailnet $origin $hostName @{AllowFunnel=@{'old-host.ts.net:9443'=$true}}};$count++
$serve=@{Web=@{"${hostName}:9443"=@{Handlers=@{'/'=@{Proxy='http://127.0.0.1:8090'}}}};AllowFunnel=@{"${hostName}:443"=$true;"${hostName}:9443"=$false}}
$before=$serve|ConvertTo-Json -Depth 8;Assert-TeamTailnet $origin $hostName $serve;if(($serve|ConvertTo-Json -Depth 8) -ne $before){throw 'Preflight mutated Serve configuration'};$count++
$workspace=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Assert-Throws {Assert-TeamPaths @{COMPOSE_PROJECT_NAME='baotin-team';TEAM_SUBNET='172.29.47.0/24';EDGE_PORT='8090';TAILSCALE_API_PORT='9443';BAOTIN_SECRETS_DIR=$workspace}};$count++
$temp=Join-Path $env:TEMP ('baotin-script-test-'+[Guid]::NewGuid().ToString('N'));New-Item -ItemType Directory -Path $temp|Out-Null
try{
    $config=Join-Path $temp 'bad.env';[IO.File]::WriteAllText($config,"DB_PASSWORD=secret-must-not-be-config`n");Assert-Throws {Read-TeamConfig $config};$count++
    [IO.File]::WriteAllText($config,"EDGE_PORT=8090`nEDGE_PORT=8091`n");Assert-Throws {Read-TeamConfig $config};$count++
    $state=Join-Path $temp 'state.json';Write-AtomicJson $state @{image='pinned';previous=$null};if((Get-Content -Raw $state|ConvertFrom-Json).image -ne 'pinned'){throw 'Atomic state failed'};$count++
}finally{$resolved=(Resolve-Path $temp).Path;if(-not $resolved.StartsWith([IO.Path]::GetFullPath($env:TEMP)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe test cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force}
foreach($file in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1'){$tokens=$null;$errors=$null;[Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$tokens,[ref]$errors)|Out-Null;if($errors){throw "Parse failure: $($file.Name)"};$count++}
Write-Output "TEAM_SCRIPTS PASS checks=$count digest=required legacy_cipher=denied wrong_schema=denied missing_metadata=denied secrets=files"
