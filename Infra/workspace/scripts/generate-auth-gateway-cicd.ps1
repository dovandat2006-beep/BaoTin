#Requires -Version 7.4
[CmdletBinding()]
param([string]$GitHubOwner='vietluccuu',[switch]$DryRun)
$ErrorActionPreference='Stop'
if($GitHubOwner -cnotmatch '^[a-z0-9][a-z0-9_-]*$'){throw 'Invalid registry owner'}
$root=Split-Path -Parent $PSScriptRoot
$template=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'templates/service.Jenkinsfile')
$workflow=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'templates/verify-only.workflow.yml')
$security=Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'templates/security-gates.sh')
foreach($repo in @('Auth-Service','API-Gateway')){
    $service=$repo.ToLowerInvariant();$rendered=$template.Replace('__SERVICE__',$service).Replace('__OWNER__',$GitHubOwner)
    foreach($entry in @(@('Jenkinsfile',$rendered),@('.github/workflows/ci-cd.yml',$workflow),@('ci/security-gates.sh',$security))){
        $path=Join-Path (Join-Path $root $repo) $entry[0]
        if($DryRun){Write-Output "DRY_RUN $repo/$($entry[0])"}else{New-Item -ItemType Directory -Path (Split-Path $path) -Force|Out-Null;$content=if($entry[0].EndsWith('.sh')){$entry[1].Replace("`r`n","`n")}else{$entry[1]};[IO.File]::WriteAllText($path,$content);Write-Output "GENERATED $repo/$($entry[0])"}
    }
}
