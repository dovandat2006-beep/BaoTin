#Requires -Version 7.4
[CmdletBinding()]
param([string]$Workspace=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath $Workspace).Path
$repos=@('API-Gateway','Auth-Service','Threat-Service','Report-Service','Reputation-Service',
    'Model-Inference-Service','Notification-Service','Search-Service','Campaign-Service','Analytics-Service')
$count=0
foreach($repo in $repos){
    $pipeline=[IO.File]::ReadAllText((Join-Path $root "$repo/Jenkinsfile"))
    foreach($marker in @("IMAGE = 'ghcr.io/vietluccuu/$($repo.ToLowerInvariant())'",'--no-daemon bootJar','ci/security-gates.sh','env.ON_MAIN','release-fragment.json',"credentialsId: 'sonar-token'",'sh ci/sonar-quality-gate.sh')){
        if(-not $pipeline.Contains($marker)){throw "Pipeline invariant failed: $repo"};$count++
    }
    if($pipeline.Contains('clean test bootJar')){throw "Release lane must not run tests (github lane owns verification): $repo"};$count++
    if($pipeline -match 'SKIP_TESTS|deploy-ssh-key|DEPLOY_ENV|-x test'){throw "Legacy bypass/deployment in build pipeline: $repo"};$count++
    $docker=[IO.File]::ReadAllText((Join-Path $root "$repo/Dockerfile"))
    $runtimeBase=if($repo -eq 'Model-Inference-Service'){'FROM eclipse-temurin:21-jre-jammy@sha256:[a-f0-9]{64}'}else{'FROM eclipse-temurin:21-jre-alpine@sha256:[a-f0-9]{64}'}
    if($docker -notmatch 'FROM eclipse-temurin:21-jdk-alpine@sha256:[a-f0-9]{64}' -or $docker -notmatch $runtimeBase -or $docker.Contains('-x test')){throw "Unpinned/legacy Docker builder: $repo"};$count++
    if($repo -eq 'Model-Inference-Service' -and $docker -notmatch '\blibgomp1\b'){throw 'Native ONNX runtime requires its glibc/OpenMP dependency'}
    foreach($gate in 'jar-security-gates.sh','security-gates.sh','sonar-quality-gate.sh','sonar-validate-gate.pl'){
        $template=Join-Path $PSScriptRoot "templates/$gate"
        $generated=Join-Path $root "$repo/ci/$gate"
        if((Get-FileHash -LiteralPath $template).Hash -ne (Get-FileHash -LiteralPath $generated).Hash){throw "Scanner template drift: $repo/$gate"};$count++
    }
    $workflow=[IO.File]::ReadAllText((Join-Path $root "$repo/.github/workflows/ci-cd.yml"))
    if($workflow -match 'push:|deploy:|packages: write|pull_request_target:'){throw "Duplicate privileged release workflow: $repo"};$count++
    if($workflow -notmatch 'gradlew\s+--no-daemon\s+test\s+(?:integrationTest\s+)?bootJar\b' -or $workflow -match '(?<!\S)-x\s+test\b|--exclude-task(?:=|\s+)test\b|SKIP_TESTS'){throw "GitHub verify lane must still run tests: $repo"};$count++
}
$fullstack=[IO.File]::ReadAllText((Join-Path $root 'Infra/Jenkinsfile'))
if($fullstack -match 'env\['){throw 'FullStack dynamic env bracket assignment requires a broad sandbox approval'};$count++
if(-not $fullstack.Contains('env."${buildKey}" = result.number.toString()')){throw 'FullStack must use sandbox-supported env property assignment'};$count++
if($fullstack -match 'ScriptApproval|approveSignature|sandbox\s*:\s*false'){throw 'FullStack must retain the existing sandbox'};$count++
if($fullstack -notmatch "(?s)stage\('Deploy JAR and web'\).*?when\s*\{\s*beforeAgent true;\s*expression\s*\{ params\.DEPLOY_RELEASE \}"){throw 'No-deploy builds must skip deploy agent before scheduling'};$count++
$before=@{}
foreach($repo in $repos){$p=Join-Path $root "$repo/Jenkinsfile";$before[$p]=(Get-FileHash $p).Hash}
$output=@(& (Join-Path $PSScriptRoot 'Sync-Pipelines.ps1') -Workspace $root -DryRun)
if($output.Count -ne 60){throw 'Dry-run artifact count failed'};$count++
foreach($p in $before.Keys){if((Get-FileHash $p).Hash -ne $before[$p]){throw 'Dry run mutated source'}};$count++
python (Join-Path $PSScriptRoot 'Test-ReleaseOrdering.py') --workspace $root
if($LASTEXITCODE){throw 'Gate-before-packaging contract failed'}
Write-Output "FULL_PIPELINES_TESTS PASS checks=$count services=10 tests=github_verify_lane base_images=pinned release=jenkins_only dry_run=no_mutation"
