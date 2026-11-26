#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$TargetDirectory,[switch]$SkipClone)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath($TargetDirectory)
if($root -eq [IO.Path]::GetPathRoot($root)){throw 'Choose an explicit project workspace, not a drive root'}
New-Item -ItemType Directory -Force $root|Out-Null
if(Test-Path -LiteralPath (Join-Path $root '.git')){throw 'BảoTin workspace is a parent directory, not a Git repository'}
$repos=@('AI','Frontend','Analytics-Service','API-Gateway','Auth-Service','Campaign-Service','Contracts',
    'Infra','Model-Inference-Service','Notification-Service','Report-Service','Reputation-Service','Search-Service','Threat-Service')
$created=0;$existing=0
foreach($repo in $repos){
    $folder=Join-Path $root $repo;$url="https://github.com/vietluccuu/$repo.git"
    if(Test-Path -LiteralPath $folder){
        if(-not(Test-Path -LiteralPath (Join-Path $folder '.git'))){throw "Existing folder is not a repository; preserve and reconcile it: $repo"}
        $top=& git -C $folder rev-parse --show-toplevel 2>$null
        if($LASTEXITCODE -ne 0 -or [IO.Path]::GetFullPath($top) -ne [IO.Path]::GetFullPath($folder)){throw 'Unexpected nested/parent repository'}
        $origin=& git -C $folder remote get-url origin 2>$null
        if($LASTEXITCODE -ne 0 -or $origin.TrimEnd('/') -notin @($url,$url.Replace('.git',''))){throw "Origin does not match confirmed project repo: $repo"}
        $existing++;continue
    }
    if($SkipClone){throw "Repository missing in SkipClone mode: $repo"}
    & git clone --branch main --single-branch $url $folder
    if($LASTEXITCODE -ne 0){throw "Clone failed: $repo; existing repositories preserved"};$created++
}
$templates=Join-Path $PSScriptRoot 'workspace'
foreach($name in 'BaoTin_Backend_Architecture_Master_Plan.md','AGENTS.md','TODO.md','CI-CD.md','.env.example'){
    $source=Join-Path $templates $name;$target=Join-Path $root $name
    if(-not(Test-Path -LiteralPath $source)){throw 'Versioned workspace documentation missing'}
    # Never replace local edits or create/copy an actual .env/secret file.
    if(-not(Test-Path -LiteralPath $target)){Copy-Item -LiteralPath $source -Destination $target}
}
Write-Output "WORKSPACE_SETUP PASS repositories=14 cloned=$created preserved=$existing parent_git=false docs=present secrets=not_copied"
