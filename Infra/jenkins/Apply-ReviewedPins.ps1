#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$ManifestFile,[Parameter(Mandatory)][string]$Username,
      [Parameter(Mandatory)][string]$TokenFile,[string]$Workspace=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path,
      [switch]$Apply)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'ReviewedPins.ps1')
$workspaceRoot=[IO.Path]::GetFullPath($Workspace)
$manifest=(Resolve-Path -LiteralPath $ManifestFile).Path
if($manifest.StartsWith($workspaceRoot+'\',[StringComparison]::OrdinalIgnoreCase) -or
   (Get-Item -LiteralPath $manifest).LinkType -or (Get-Item -LiteralPath $manifest).Length -gt 32768){
    throw 'Reviewed manifest must be a bounded regular external file'
}
$allowed=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')
foreach($rule in (Get-Acl -LiteralPath $manifest).Access){
    if($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin $allowed){
        throw 'Only operator/SYSTEM/Administrators may write the approval manifest'
    }
}
$pins=Read-ReviewedPins ([IO.File]::ReadAllText($manifest))
foreach($repo in $pins.repositories.Keys){
    $folder=Join-Path $workspaceRoot $repo
    $origin=& git -C $folder remote get-url origin
    if($LASTEXITCODE -or $origin.Trim() -ne "https://github.com/vietluccuu/$repo.git"){throw 'Reviewed repository origin mismatch'}
    $head=(& git -C $folder rev-parse HEAD).Trim()
    $dirty=@(& git -C $folder status --porcelain=v1)
    if($LASTEXITCODE -or $dirty.Count -or $head -ne $pins.repositories[$repo]){throw 'Reviewed workspace must be clean and exactly match approval'}
    $ref=& git -C $folder ls-remote --exit-code origin refs/heads/main
    if($LASTEXITCODE -or ($ref -split '\s+')[0] -ne $head){throw 'Main changed since review; approve a fresh manifest'}
}
if(-not $Apply){Write-Output 'REVIEWED_PINS PREFLIGHT repositories=12 manifest=restricted current_main=matched mutation=false';return}
$pipelines=@($pins.repositories.Keys|ForEach-Object {Join-Path $workspaceRoot "$_/Jenkinsfile"})
. (Join-Path $PSScriptRoot 'Test-JenkinsPipelines.ps1') -Username $Username -TokenFile $TokenFile -PipelineFiles $pipelines
$script=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'apply-reviewed-pins.groovy')).Replace('__PINS_JSON__',($pins|ConvertTo-Json -Depth 5 -Compress))
$r=Request 'scriptText' 'POST' @{script=$script} $headers
if([int]$r.StatusCode -ne 200 -or ([string]$r.Content).Trim() -ne 'REVIEWED_PINS_APPLIED jobs=12 sandbox=retained builds_started=0'){
    throw 'Pin transaction did not return verified completion; reread state before retry'
}
Write-Output ([string]$r.Content).Trim()
