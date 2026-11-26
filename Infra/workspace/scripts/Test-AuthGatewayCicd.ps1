#Requires -Version 7.4
[CmdletBinding()]
param([string]$Root=(Split-Path -Parent $PSScriptRoot))
$ErrorActionPreference='Stop'
$rootPath=(Resolve-Path -LiteralPath $Root).Path
$generator=Join-Path $rootPath 'scripts/generate-auth-gateway-cicd.ps1'
$source=[IO.File]::ReadAllText($generator)
if($source -notmatch "param\(\[string\]\`$GitHubOwner='vietluccuu'"){throw 'Default registry owner must be vietluccuu'}
$template=[IO.File]::ReadAllText((Join-Path $rootPath 'scripts/templates/service.Jenkinsfile'))
$hashes=@{};$checks=1
foreach($repo in 'Auth-Service','API-Gateway'){
    $file=Join-Path $rootPath "$repo/Jenkinsfile";$content=[IO.File]::ReadAllText($file)
    $expected=$template.Replace('__SERVICE__',$repo.ToLowerInvariant()).Replace('__OWNER__','vietluccuu')
    if($content -ne $expected){throw 'Generated service pipeline drift or incorrect registry owner'}
    $checks++;$hashes[$file]=(Get-FileHash -LiteralPath $file).Hash
}
$dry=@(& $generator -DryRun)
if($dry.Count -ne 6 -or @($dry|Where-Object {$_ -notlike 'DRY_RUN *'}).Count){throw 'Dry run did not enumerate six generated artifacts'};$checks++
foreach($file in $hashes.Keys){if((Get-FileHash -LiteralPath $file).Hash -ne $hashes[$file]){throw 'Dry run changed a pipeline'}};$checks++
foreach($owner in @('owner;whoami','../owner','owner/name','UppercaseOwner','')){
    $denied=$false;try{& $generator -GitHubOwner $owner -DryRun|Out-Null}catch{if($_.Exception.Message -ne 'Invalid registry owner'){throw};$denied=$true}
    if(-not $denied){throw 'Invalid registry owner accepted'};$checks++
}
$temp=Join-Path $env:TEMP ('baotin-cicd-owner-'+[guid]::NewGuid().ToString('N'))
try{
    New-Item -ItemType Directory -Path "$temp/scripts/templates" -Force|Out-Null
    Copy-Item -LiteralPath $generator -Destination "$temp/scripts/generate-auth-gateway-cicd.ps1"
    foreach($name in 'service.Jenkinsfile','verify-only.workflow.yml','security-gates.sh'){
        Copy-Item -LiteralPath (Join-Path $rootPath "scripts/templates/$name") -Destination "$temp/scripts/templates/$name"
    }
    & "$temp/scripts/generate-auth-gateway-cicd.ps1"|Out-Null
    $generated=@{}
    foreach($repo in 'Auth-Service','API-Gateway'){
        $pipeline=Join-Path $temp "$repo/Jenkinsfile"
        if([IO.File]::ReadAllText($pipeline) -ne [IO.File]::ReadAllText((Join-Path $rootPath "$repo/Jenkinsfile"))){throw 'Generator rendered wrong namespace'};$checks++
        foreach($name in 'Jenkinsfile','.github/workflows/ci-cd.yml','ci/security-gates.sh'){
            $file=Join-Path $temp "$repo/$name";$generated[$file]=(Get-FileHash -LiteralPath $file).Hash
        }
    }
    & "$temp/scripts/generate-auth-gateway-cicd.ps1"|Out-Null
    foreach($file in $generated.Keys){if((Get-FileHash -LiteralPath $file).Hash -ne $generated[$file]){throw 'Generator is not idempotent'}};$checks++
    Write-Output "CICD_OWNER_TESTS PASS checks=$checks owner=vietluccuu pipelines=2 generator=consistent dry_run=no_mutation invalid_owner=denied idempotent=true"
}finally{
    if(Test-Path -LiteralPath $temp){$resolved=(Resolve-Path -LiteralPath $temp).Path
        if(-not $resolved.StartsWith([IO.Path]::GetFullPath($env:TEMP)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected test cleanup target'}
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
