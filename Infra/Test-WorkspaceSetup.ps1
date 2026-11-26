#Requires -Version 7.4
$ErrorActionPreference='Stop'
$temp=Join-Path $env:TEMP ('baotin-workspace-test-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $temp|Out-Null
try{
    $seed=Join-Path $temp 'seed';New-Item -ItemType Directory $seed|Out-Null
    git -C $seed init -b main --quiet
    git -C $seed config user.name fixture-user;git -C $seed config user.email fixture@example.test
    [IO.File]::WriteAllText((Join-Path $seed 'README.md'),'synthetic clone fixture')
    git -C $seed add README.md;git -C $seed commit -m fixture --quiet
    if($LASTEXITCODE -ne 0){throw 'Seed creation failed'}
    function git {
        $arguments=@($args)
        if($arguments[0] -eq 'clone'){
            $expected=$arguments[-2];$folder=$arguments[-1];$arguments[-2]=$seed
            & git.exe @arguments 2>$null
            if($LASTEXITCODE -ne 0){throw 'Local clone fixture failed'}
            & git.exe -C $folder remote set-url origin $expected
            return
        }
        & git.exe @arguments
    }
    $workspace=Join-Path $temp 'workspace'
    $first=& (Join-Path $PSScriptRoot 'Setup-Workspace.ps1') -TargetDirectory $workspace
    if(($first -join '') -notmatch 'repositories=14 cloned=14 preserved=0'){throw 'Workspace clone count failed'}
    $doc=Join-Path $workspace 'AGENTS.md';[IO.File]::WriteAllText($doc,'fixture local user edit');$hash=(Get-FileHash $doc).Hash
    $second=& (Join-Path $PSScriptRoot 'Setup-Workspace.ps1') -TargetDirectory $workspace -SkipClone
    if(($second -join '') -notmatch 'cloned=0 preserved=14' -or (Get-FileHash $doc).Hash -ne $hash){throw 'Setup overwrote local documentation'}
    if(Test-Path (Join-Path $workspace '.env')){throw 'Setup copied actual environment'}
    git.exe -C (Join-Path $workspace 'Auth-Service') remote set-url origin https://example.test/wrong.git
    $failed=$false;try{& (Join-Path $PSScriptRoot 'Setup-Workspace.ps1') -TargetDirectory $workspace -SkipClone|Out-Null}catch{$failed=$true}
    if(-not $failed){throw 'Wrong origin accepted'}
    Write-Output 'WORKSPACE_SETUP_TESTS PASS clone=14 rerun=preserved wrong_origin=denied local_docs=preserved secrets=not_copied remote_transport=synthetic'
}finally{
    $resolved=(Resolve-Path -LiteralPath $temp).Path
    if(-not $resolved.StartsWith([IO.Path]::GetFullPath($env:TEMP)+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected workspace test cleanup target'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
