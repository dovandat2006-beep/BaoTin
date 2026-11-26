# Executes current validation clauses with synthetic release records, no Jenkins/DB.
$ErrorActionPreference='Stop'
$source=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'Fetch-JenkinsArtifacts.ps1'))
$clause=($source -split "`n"|Where-Object {$_.Trim().StartsWith('if($releaseProof.schemaVersion')}) -join "`n"
if(-not $clause){throw 'Release proof clause missing'}
$check=[scriptblock]::Create($clause)
$repo='Auth-Service';$sha=@('a'*40);$count=0
foreach($case in 'valid','wrong_revision','wrong_service','wrong_policy','bad_hash','bad_schema'){
 $releaseProof=@{schemaVersion=1;service='auth-service';sourceRevision=$sha[0];testPolicy='external_verify';jarSha256='b'*64}
 switch($case){wrong_revision{$releaseProof.sourceRevision='c'*40} wrong_service{$releaseProof.service='report-service'} wrong_policy{$releaseProof.testPolicy='skipped'} bad_hash{$releaseProof.jarSha256='mutable'} bad_schema{$releaseProof.schemaVersion=2}}
 $passed=$true;try{& $check}catch{$passed=$false}
 if($passed -ne ($case -eq 'valid')){throw "Invalid release proof outcome: $case"};$count++
}
if($source -notmatch 'elseif\(\$proofResponse.StatusCode -eq 404\)' -or $source -notmatch 'Downloaded JAR differs from release evidence'){throw 'Legacy/error/hash compatibility path missing'}
$builder=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'Build-Images.ps1'))
$start=$builder.IndexOf('$releasePolicy=$record.Contains(');$end=$builder.IndexOf("`n",$builder.IndexOf("throw 'Verified Jenkins artifact changed'",$start))
if($start -lt 0 -or $end -le $start){throw 'Builder policy validation missing'}
$validate=[scriptblock]::Create($builder.Substring($start,$end-$start))
$temp=Join-Path ([IO.Path]::GetTempPath()) ('baotin-release-proof-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $temp|Out-Null
try{
 $jar=Join-Path $temp 'fixture.jar';[IO.File]::WriteAllText($jar,'synthetic artifact bytes');$hash=(Get-FileHash $jar).Hash.ToLowerInvariant()
 foreach($case in 'new_valid','new_unproved','legacy_valid','legacy_empty','changed_jar'){
  $record=@{result='SUCCESS';sonar='validated';tests=0;testPolicy='external_verify';releaseEvidenceValidated=$true;sha256=$hash}
  switch($case){new_unproved{$record.releaseEvidenceValidated=$false} legacy_valid{$record.testPolicy='jenkins_tests';$record.tests=1} legacy_empty{$record.testPolicy='jenkins_tests'} changed_jar{$record.sha256='d'*64}}
  $passed=$true;try{& $validate}catch{$passed=$false}
  if($passed -ne ($case -in 'new_valid','legacy_valid')){throw "Builder proof outcome mismatch: $case"};$count++
 }
 "RELEASE_EVIDENCE PASS cases=$count source_hash=bound external_test_policy=explicit legacy_JUnit=compatible malformed=denied"
}finally{
 $resolved=[IO.Path]::GetFullPath($temp)
 if(-not $resolved.StartsWith([IO.Path]::GetTempPath(),[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^baotin-release-proof-[a-f0-9]{32}$'){throw 'Unexpected proof-fixture cleanup target'}
 Remove-Item -LiteralPath $resolved -Recurse -Force
}
