#Requires -Version 7.4
[CmdletBinding()]
param([string]$BuildScript=(Join-Path $PSScriptRoot 'Build-Images.ps1'),
      [string]$TempRoot=([IO.Path]::GetTempPath()))
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath($TempRoot)
$stage=Join-Path $root ('baotin-image-temp-test-'+[guid]::NewGuid().ToString('N'))
$saved=@{};foreach($key in 'TEMP','TMP','TMPDIR'){$saved[$key]=[Environment]::GetEnvironmentVariable($key)}
function git {
    $global:LASTEXITCODE=0
    if($args -contains '--porcelain'){return}
    if($args -contains 'HEAD'){return ('a'*40)}
    throw 'Unexpected fixture Git operation'
}
function docker {
    $global:LASTEXITCODE=0
    $a=@($args);if($a[0] -eq '--context'){$a=$a[2..($a.Length-1)]}
    if($a[0] -eq 'build'){return 'fixture image build'}
    if($a[0] -eq 'image' -and $a[1] -eq 'inspect'){
        if($a[-1] -eq '{{json .Config.Labels}}'){return '{}'}
        return 'sha256:'+([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($a[2])))).ToLowerInvariant()
    }
    throw 'Unexpected fixture Docker operation'
}
try{
    $fixture=Join-Path $stage 'Infra/alternative'
    $artifacts=Join-Path $stage 'temporary-artifacts'
    New-Item -ItemType Directory -Path $fixture,$artifacts -Force|Out-Null
    Copy-Item -LiteralPath $BuildScript -Destination (Join-Path $fixture 'Build-Images.ps1')
    foreach($name in 'runtime.Dockerfile','web.Dockerfile','nginx.conf'){
        [IO.File]::WriteAllText((Join-Path $fixture $name),'synthetic fixture only')
    }
    foreach($repo in 'Auth-Service','API-Gateway','Threat-Service','Report-Service','Reputation-Service','Model-Inference-Service','Notification-Service','Search-Service','Campaign-Service','Analytics-Service'){
        $folder=Join-Path $stage $repo
        New-Item -ItemType Directory -Path (Join-Path $folder 'build/libs'),(Join-Path $folder 'build/test-results/test') -Force|Out-Null
        [IO.File]::WriteAllText((Join-Path $folder "build/libs/$repo-0.0.1-SNAPSHOT.jar"),$repo)
        [IO.File]::WriteAllText((Join-Path $folder 'build/test-results/test/TEST-fixture.xml'),'<testsuite tests="1" failures="0" errors="0" skipped="0"/>')
    }
    New-Item -ItemType Directory -Path (Join-Path $stage 'Frontend/dist') -Force|Out-Null
    [IO.File]::WriteAllText((Join-Path $stage 'Frontend/dist/index.html'),'<html>fixture</html>')
    $sentinel=Join-Path $artifacts 'outside-sentinel.txt';[IO.File]::WriteAllText($sentinel,'preserve unrelated file')
    $env:TEMP=$null;$env:TMP=$artifacts;$env:TMPDIR=$artifacts
    $output=Join-Path $stage 'images.json'
    & (Join-Path $fixture 'Build-Images.ps1') -OutputFile $output -DockerContext fixture|Out-Null
    $m=Get-Content -LiteralPath $output -Raw|ConvertFrom-Json -AsHashtable
    if($m.schemaVersion -ne 1 -or $m.images.Count -ne 11 -or $m.artifacts.Count -ne 10){throw 'Fixture packaging incomplete'}
    if(@(Get-ChildItem -LiteralPath $artifacts -Directory -Filter 'baotin-image-artifacts-*').Count){throw 'Owned artifact stage retained'}
    if([IO.File]::ReadAllText($sentinel) -ne 'preserve unrelated file'){throw 'Unrelated temporary file changed'}
    'ALT_IMAGE_TEMP PASS TEMP=unset images=11 stage=removed unrelated_file=preserved synthetic_only=true'
}finally{
    foreach($key in $saved.Keys){[Environment]::SetEnvironmentVariable($key,$saved[$key])}
    $resolved=[IO.Path]::GetFullPath($stage)
    if(-not $resolved.StartsWith($root.TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^baotin-image-temp-test-[a-f0-9]{32}$'){throw 'Unexpected fixture cleanup path'}
    if(Test-Path -LiteralPath $resolved){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
