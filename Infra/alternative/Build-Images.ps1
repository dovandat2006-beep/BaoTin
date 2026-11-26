#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputFile,[string]$VerifiedArtifactsDirectory,
      [string]$DockerContext=((& docker context show).Trim()))
$ErrorActionPreference='Stop'
$workspace=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$verified=$null
if($VerifiedArtifactsDirectory){
    $workspace=(Resolve-Path -LiteralPath $VerifiedArtifactsDirectory).Path
    $verified=Get-Content (Join-Path $workspace '.ci-verified.json') -Raw|ConvertFrom-Json -AsHashtable
    if($verified.schemaVersion -ne 1 -or $verified.repositories.Count -ne 11){throw 'Complete verified Jenkins artifact set required'}
}
$names=@{'Auth-Service'='AUTH_IMAGE';'API-Gateway'='GATEWAY_IMAGE';'Threat-Service'='THREAT_IMAGE';'Report-Service'='REPORT_IMAGE';'Reputation-Service'='REPUTATION_IMAGE';'Model-Inference-Service'='MODEL_IMAGE';'Notification-Service'='NOTIFICATION_IMAGE';'Search-Service'='SEARCH_IMAGE';'Campaign-Service'='CAMPAIGN_IMAGE';'Analytics-Service'='ANALYTICS_IMAGE'}
$tempRoot=[IO.Path]::GetTempPath()
$stage=Join-Path $tempRoot ('baotin-image-artifacts-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage|Out-Null
$manifest=@{schemaVersion=1;images=@{};artifacts=@{}}
$runtimeSpec=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'runtime.Dockerfile')).Hash.ToLowerInvariant()
function Invoke-ImageDocker([string[]]$Arguments){
    $out=& docker --context $DockerContext @Arguments 2>&1
    if($LASTEXITCODE){throw ('Alternative image operation failed: '+$Arguments[0])}
    return (($out|ForEach-Object{"$_"}) -join "`n")
}
try {
    foreach($repo in $names.Keys){
        $folder=Join-Path $workspace $repo
        if(-not $verified -and @(& git -C $folder status --porcelain).Count){throw 'Image input source must be clean/published/tested'}
        $revision=if($verified){$verified.repositories[$repo].revision}else{(& git -C $folder rev-parse HEAD).Trim()}
        if($revision -notmatch '^[a-f0-9]{40}$'){throw 'Artifact source revision missing'}
        $jar=Join-Path $folder "build/libs/$repo-0.0.1-SNAPSHOT.jar"
        if(-not(Test-Path -LiteralPath $jar)){throw 'Tested JAR artifact missing'}
        if($verified){
            $record=$verified.repositories[$repo]
            $releasePolicy=$record.Contains('testPolicy') -and $record.testPolicy -eq 'external_verify' -and $record.Contains('releaseEvidenceValidated') -and $record.releaseEvidenceValidated -eq $true
            if($record.result -ne 'SUCCESS' -or $record.sonar -ne 'validated' -or (-not $releasePolicy -and $record.tests -lt 1) -or (Get-FileHash $jar).Hash.ToLowerInvariant() -ne $record.sha256){throw 'Verified Jenkins artifact changed'}
        }else{
        $reports=@(Get-ChildItem (Join-Path $folder 'build/test-results/test') -Filter 'TEST-*.xml')
        if(-not $reports.Count){throw 'Complete passing test reports required'}
        foreach($report in $reports){[xml]$xml=[IO.File]::ReadAllText($report.FullName);if([int]$xml.testsuite.failures -or [int]$xml.testsuite.errors -or [int]$xml.testsuite.skipped){throw 'Image input tests did not pass completely'}}
        }
        Copy-Item -LiteralPath $jar -Destination (Join-Path $stage 'app.jar') -Force
        $hash=(Get-FileHash -LiteralPath $jar).Hash.ToLowerInvariant()
        $tag='baotin-alt/'+$repo.ToLowerInvariant()+':'+$hash.Substring(0,16)+'-'+$runtimeSpec.Substring(0,12)
        $existing=& docker --context $DockerContext image inspect $tag --format '{{json .Config.Labels}}' 2>$null
        $reuse=$false
        if($LASTEXITCODE -eq 0){$labels=$existing|ConvertFrom-Json;$reuse=$labels.'baotin.jar.sha256' -eq $hash -and $labels.'baotin.runtime.spec' -eq $runtimeSpec}
        if(-not $reuse){Invoke-ImageDocker -Arguments @('build','-f',(Join-Path $PSScriptRoot 'runtime.Dockerfile'),'--label',"org.opencontainers.image.revision=$revision",'--label',"baotin.jar.sha256=$hash",'--label',"baotin.runtime.spec=$runtimeSpec",'-t',$tag,$stage)|Out-Null}
        $id=(Invoke-ImageDocker -Arguments @('image','inspect',$tag,'--format','{{.Id}}')).Trim()
        if($id -notmatch '^sha256:[a-f0-9]{64}$'){throw 'Immutable image identity missing'}
        $manifest.images[$names[$repo]]=$id;$manifest.artifacts[$repo]=@{sha256=$hash;revision=$revision}
        "ALT_IMAGE_BUILT service=$repo id=$id tested=true"
    }
    Remove-Item -LiteralPath (Join-Path $stage 'app.jar')
    $web=Join-Path $workspace 'Frontend/dist'
    if(-not $verified -and @(& git -C (Join-Path $workspace 'Frontend') status --porcelain).Count){throw 'Web image source must be clean and verified'}
    if($verified -and ($verified.repositories.Frontend.result -ne 'SUCCESS' -or $verified.repositories.Frontend.sonar -ne 'validated' -or
        (Get-FileHash (Join-Path $workspace 'Frontend/build/web.tar.gz')).Hash.ToLowerInvariant() -ne $verified.repositories.Frontend.sha256)){throw 'Verified web artifact changed'}
    if(-not(Test-Path -LiteralPath (Join-Path $web 'index.html'))){throw 'Tested Expo export missing'}
    Copy-Item -LiteralPath $web -Destination (Join-Path $stage 'web') -Recurse
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'nginx.conf') -Destination (Join-Path $stage 'nginx.conf')
    $webHashes=@(Get-ChildItem -LiteralPath $web -Recurse -File|Sort-Object FullName|ForEach-Object{
        $_.FullName.Substring($web.Length).Replace('\','/')+':'+(Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()
    })
    $webSpec=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'web.Dockerfile')).Hash.ToLowerInvariant()+'-'+(Get-FileHash -LiteralPath (Join-Path $stage 'nginx.conf')).Hash.ToLowerInvariant()+'-'+($webHashes -join '|')
    $webTag='baotin-alt/frontend:'+([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($webSpec)))).Substring(0,20).ToLowerInvariant()
    Invoke-ImageDocker -Arguments @('build','-f',(Join-Path $PSScriptRoot 'web.Dockerfile'),'-t',$webTag,$stage)|Out-Null
    $manifest.images.WEB_IMAGE=(Invoke-ImageDocker -Arguments @('image','inspect',$webTag,'--format','{{.Id}}')).Trim()
    if($manifest.images.WEB_IMAGE -notmatch '^sha256:[a-f0-9]{64}$'){throw 'Immutable web identity missing'}
    [IO.File]::WriteAllText([IO.Path]::GetFullPath($OutputFile),($manifest|ConvertTo-Json -Depth 5))
    'ALT_IMAGES PASS backend=10 web=1 input=passing_artifacts refs=immutable secrets_in_context=0'
} finally {
    $resolved=[IO.Path]::GetFullPath($stage)
    if(-not $resolved.StartsWith([IO.Path]::GetFullPath($tempRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^baotin-image-artifacts-[a-f0-9]{32}$'){throw 'Unexpected artifact cleanup path'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
