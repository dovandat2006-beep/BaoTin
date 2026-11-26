#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$BuildsFile,[Parameter(Mandatory)][string]$JarEnvFile,
      [Parameter(Mandatory)][string]$AlternativeEnvFile,[Parameter(Mandatory)][string]$ReleaseDirectory,
      [switch]$PrepareOnly)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$release=[IO.Path]::GetFullPath($ReleaseDirectory)
if($release.StartsWith($workspace+'\',[StringComparison]::OrdinalIgnoreCase) -or (Test-Path $release) -or [IO.Path]::GetFileName($release) -notmatch '^release-[a-f0-9]{32}$'){throw 'New GUID release directory outside source required'}
$cfg=@{};foreach($line in Get-Content $JarEnvFile){if($line -match '^([A-Z][A-Z0-9_]*)=(.*)$'){$cfg[$Matches[1]]=$Matches[2]}}
New-Item -ItemType Directory $release|Out-Null
& (Join-Path $PSScriptRoot 'Fetch-JenkinsArtifacts.ps1') -BuildsFile $BuildsFile -OutputDirectory "$release/artifacts" -Username $cfg.JENKINS_USERNAME -TokenFile $cfg.JENKINS_API_TOKEN_FILE
& (Join-Path $PSScriptRoot 'Build-Images.ps1') -OutputFile "$release/images.json" -VerifiedArtifactsDirectory "$release/artifacts"
& (Join-Path $PSScriptRoot 'Test-ImageSecurity.ps1') -Manifest "$release/images.json" -OutputDirectory "$release/security"
if($PrepareOnly){'DOCKER_RELEASE_PREPARED PASS artifacts=Jenkins_success Sonar=required images=11 scans=passed deploy=false';return}
if(-not(Test-Path -LiteralPath $AlternativeEnvFile)){throw 'External alternative config must be prepared before promotion'}
$before=[IO.File]::ReadAllText($AlternativeEnvFile)
$manifest=Get-Content "$release/images.json" -Raw|ConvertFrom-Json -AsHashtable
$candidate="$release/candidate.env"
$lines=$before -split '\r?\n'|ForEach-Object {if($_ -match '^([A-Z][A-Z0-9_]*_IMAGE)='){$key=$Matches[1];if(-not $manifest.images.Contains($key)){throw 'Unexpected image configuration field'};"$key=$($manifest.images[$key])"}else{$_}}
[IO.File]::WriteAllLines($candidate,$lines)
foreach($key in $manifest.images.Keys){if(-not @($lines|Where-Object {$_.StartsWith($key+'=')}).Count){throw 'External config missing required image key'}}
# Migration is separate. Candidate reader/schema/cipher must fit the real runtime DB.
$java=Join-Path $cfg.JAVA_HOME 'bin/java.exe';$jar="$release/artifacts/Auth-Service/build/libs/Auth-Service-0.0.1-SNAPSHOT.jar"
function Probe($class){
 $psi=[Diagnostics.ProcessStartInfo]::new($java);$psi.UseShellExecute=$false;$psi.CreateNoWindow=$true;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
 foreach($arg in @("-Dloader.main=$class",'-cp',$jar,'org.springframework.boot.loader.launch.PropertiesLauncher')){$psi.ArgumentList.Add($arg)}
 foreach($k in 'DB_URL','DB_PASSWORD_FILE'){$psi.Environment[$k]=$cfg[$k]};$psi.Environment['DB_USER']='baotin_auth'
 $p=[Diagnostics.Process]::Start($psi);$text=$p.StandardOutput.ReadToEnd();$null=$p.StandardError.ReadToEnd();$p.WaitForExit()
 if($p.ExitCode){throw 'Read-only DB compatibility probe failed; diagnostics withheld'}
 return (($text -split "`n"|Where-Object {$_.Trim().StartsWith('{')}|Select-Object -Last 1)|ConvertFrom-Json)
}
$caps=Probe 'com.baotin.auth.operator.RuntimeCapabilities';$db=Probe 'com.baotin.auth.operator.RuntimeDatabaseState'
if($caps.readerVersion -lt $db.readerFloor -or $caps.minimumSchema -gt $db.schemaRevision -or $db.cipherFloor -notin $caps.cipherReaders){throw 'Candidate incompatible; no deployment attempted'}
$oldAuth=(@($before -split '\r?\n'|Where-Object {$_.StartsWith('AUTH_IMAGE=')})[0] -split '=',2)[1]
if($oldAuth -notmatch '^sha256:[a-f0-9]{64}$'){throw 'Retain an immutable compatible rollback image before replacement'}
$oldMeta=& docker run --rm --network none --entrypoint java $oldAuth '-Dloader.main=com.baotin.auth.operator.RuntimeCapabilities' -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher
if($LASTEXITCODE){throw 'Previous image capabilities unavailable; no replacement attempted'}
$oldCaps=($oldMeta -join "`n")|ConvertFrom-Json
if($oldCaps.readerVersion -lt $db.readerFloor -or $oldCaps.minimumSchema -gt $db.schemaRevision -or $db.cipherFloor -notin $oldCaps.cipherReaders){throw 'Previous rollback image incompatible with DB floors'}
$compose=Join-Path $PSScriptRoot 'docker-compose.yml'
& docker compose --project-name baotin-alt --env-file $candidate -f $compose config --quiet
if($LASTEXITCODE){throw 'Candidate compose configuration invalid'}
[IO.File]::WriteAllText("$release/previous.env",$before)
try{
 & docker compose --project-name baotin-alt --env-file $candidate -f $compose --profile operations run --rm prepare-secrets
 if($LASTEXITCODE){throw 'Scoped existing secret preparation failed'}
 & docker compose --project-name baotin-alt --env-file $candidate -f $compose up -d --pull never --wait --wait-timeout 180
 if($LASTEXITCODE){throw 'Docker candidate readiness failed'}
 & (Join-Path $PSScriptRoot 'Test-Deployment.ps1') -EnvFile $candidate -ImageManifest "$release/images.json" -OutputFile "$release/deployment.json"
 Copy-Item -LiteralPath $candidate -Destination $AlternativeEnvFile -Force
 'JENKINS_DOCKER_DEPLOY PASS backend=10 web=1 readiness=UP immutable=true Sonar=required secrets=scoped migrations=separate'
}catch{
 # Previous immutable images/config remain retained. Never delete volumes or down-migrate.
 & docker compose --project-name baotin-alt --env-file "$release/previous.env" -f $compose up -d --pull never --wait --wait-timeout 180
 if($LASTEXITCODE){throw 'Candidate failed and previous readiness needs operator review'}
 'JENKINS_DOCKER_ROLLBACK PASS previous_config=restored readiness=UP database=unchanged'
 throw 'Docker candidate failed; previous runtime restored'
}
