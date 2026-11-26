#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$BuildsFile,[Parameter(Mandatory)][string]$OutputDirectory,
      [Parameter(Mandatory)][string]$Username,[Parameter(Mandatory)][string]$TokenFile)
$ErrorActionPreference='Stop'
$workspace=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output=[IO.Path]::GetFullPath($OutputDirectory)
if($output.StartsWith($workspace+'\',[StringComparison]::OrdinalIgnoreCase) -or (Test-Path $output)){throw 'New artifact directory outside source workspace required'}
$builds=Get-Content $BuildsFile -Raw|ConvertFrom-Json -AsHashtable
$names=@('Auth-Service','API-Gateway','Threat-Service','Report-Service','Reputation-Service','Model-Inference-Service','Notification-Service','Search-Service','Campaign-Service','Analytics-Service','Frontend')
if($builds.Count -ne 11 -or @(Compare-Object $names @($builds.Keys)).Count){throw 'Complete immutable Jenkins build manifest required'}
. (Join-Path $PSScriptRoot '../jenkins/Test-JenkinsPipelines.ps1') -Username $Username -TokenFile $TokenFile -PipelineFiles @((Join-Path $PSScriptRoot '../Jenkinsfile'))
New-Item -ItemType Directory $output|Out-Null
$verified=@{schemaVersion=1;repositories=@{}}
function Json($route){$r=Request $route;if($r.StatusCode -ne 200){throw 'Required Jenkins evidence missing'};$s=if($r.Content -is [byte[]]){[Text.Encoding]::UTF8.GetString($r.Content)}else{[string]$r.Content};return ,($s|ConvertFrom-Json -AsHashtable)}
function Artifact($route,$path){try{$r=Invoke-WebRequest @http -Uri ($JenkinsUrl+$route) -OutFile $path -PassThru -Verbose:$false -Debug:$false;if($r.StatusCode -ne 200){throw 'Artifact HTTP status failed'}}catch{throw 'Artifact transfer failed; diagnostics withheld'}}
foreach($repo in $names){
 if([string]$builds[$repo] -notmatch '^[1-9][0-9]{0,8}$'){throw 'Immutable build number required'}
 $base="job/baotin/job/$repo/$($builds[$repo])/"
 $job=Json ($base+'api/json?tree=result,building,actions[lastBuiltRevision[SHA1]]')
 if($job.result -ne 'SUCCESS' -or $job.building){throw 'Build must finish all gates successfully'}
 $sha=@($job.actions|Where-Object {$_.Contains('lastBuiltRevision')}|ForEach-Object {$_.lastBuiltRevision.SHA1})
 if($sha.Count -ne 1 -or $sha[0] -notmatch '^[a-f0-9]{40}$'){throw 'Missing reviewed source lineage'}
 $dir=Join-Path $output $repo;New-Item -ItemType Directory -Force "$dir/build/libs","$dir/build/sonar"|Out-Null
 Artifact ($base+'artifact/build/sonar/quality-gate.json') "$dir/build/sonar/quality-gate.json"
 & (Join-Path ${env:ProgramFiles} 'Git/usr/bin/perl.exe') (Join-Path $PSScriptRoot '../ci/templates/sonar-validate-gate.pl') "$dir/build/sonar/quality-gate.json" ('baotin_'+$repo.ToLowerInvariant())
 if($LASTEXITCODE){throw 'Archived Sonar verdict missing required policy'}
 $leaks=Json ($base+'artifact/build/security/gitleaks.json');if(@($leaks).Count){throw 'Secret findings'}
 if($repo -eq 'Frontend'){
  $path="$dir/build/web.tar.gz";Artifact ($base+'artifact/build/web.tar.gz') $path
  $entries=@(& tar -tzf $path);$types=@(& tar -tvzf $path)
  if($LASTEXITCODE -or @($entries|Where-Object {$_ -match '(^/|^[A-Za-z]:|(^|/)\.\.(/|$))'}).Count -or @($types|Where-Object {$_ -notmatch '^[-d]'}).Count){throw 'Unsafe web archive path/type'}
  New-Item -ItemType Directory "$dir/dist"|Out-Null; & tar -xzf $path -C "$dir/dist"
  if($LASTEXITCODE -or -not(Test-Path "$dir/dist/index.html")){throw 'Web export missing'}
 $tests=0;$testPolicy='external_verify';$releaseProof=$null
 }else{
  $releaseProof=$null;$testPolicy='jenkins_tests'
  $proofResponse=Request ($base+'artifact/build/release-evidence.json')
  if($proofResponse.StatusCode -eq 200){
   $proofText=if($proofResponse.Content -is [byte[]]){[Text.Encoding]::UTF8.GetString($proofResponse.Content)}else{[string]$proofResponse.Content}
   $releaseProof=$proofText|ConvertFrom-Json -AsHashtable
   if($releaseProof.schemaVersion -ne 1 -or $releaseProof.service -ne $repo.ToLowerInvariant() -or $releaseProof.sourceRevision -ne $sha[0] -or $releaseProof.testPolicy -ne 'external_verify' -or $releaseProof.jarSha256 -notmatch '^[a-f0-9]{64}$'){throw 'Release evidence does not match immutable build/source'}
   $tests=0;$testPolicy='external_verify'
  }elseif($proofResponse.StatusCode -eq 404){
   # Compatibility for older successfully tested releases; errors never bypass proof.
  $test=Json ($base+'testReport/api/json?tree=failCount,skipCount,passCount')
  if($test.failCount -or $test.skipCount -or $test.passCount -lt 1){throw 'Nonpassing/missing backend tests'}
  $tests=[int]$test.passCount
  }else{throw 'Release evidence request failed'}
  $deps=Json ($base+'artifact/build/security/dependencies.json')
  if(-not @($deps.Results|Where-Object {$_.Type -eq 'jar'}).Count){throw 'JAR scan coverage missing'}
  foreach($result in $deps.Results){if($result.Contains('Vulnerabilities') -and @($result.Vulnerabilities|Where-Object {$_.Severity -in 'HIGH','CRITICAL'}).Count){throw 'HIGH/CRITICAL dependency findings'}}
  $sbom=Json ($base+'artifact/build/security/sbom.cdx.json');if($sbom.components.Count -lt 1){throw 'Empty JAR SBOM'}
  $path="$dir/build/libs/$repo-0.0.1-SNAPSHOT.jar";Artifact ($base+'artifact/build/libs/'+$repo+'-0.0.1-SNAPSHOT.jar') $path
  if($releaseProof -and (Get-FileHash $path).Hash.ToLowerInvariant() -ne $releaseProof.jarSha256){throw 'Downloaded JAR differs from release evidence'}
 }
 $verified.repositories[$repo]=@{revision=$sha[0];build=[int]$builds[$repo];tests=$tests;testPolicy=$testPolicy;releaseEvidenceValidated=($null -ne $releaseProof);sha256=(Get-FileHash $path).Hash.ToLowerInvariant();sonar='validated';result='SUCCESS'}
}
[IO.File]::WriteAllText((Join-Path $output '.ci-verified.json'),($verified|ConvertTo-Json -Depth 6))
'JENKINS_ARTIFACTS PASS services=11 successful_builds=true sonar_policy=validated test_policy=explicit security_sboms=required source_lineage=recorded'
