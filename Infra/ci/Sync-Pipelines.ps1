#Requires -Version 7.4
[CmdletBinding()]
param([string]$Workspace=(Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),[switch]$DryRun)
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath $Workspace).Path
$repos=@('API-Gateway','Auth-Service','Threat-Service','Report-Service','Reputation-Service',
    'Model-Inference-Service','Notification-Service','Search-Service','Campaign-Service','Analytics-Service')
$pipeline=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'templates/service.Jenkinsfile')).Replace("`r`n","`n")
$workflow=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'templates/verify-only.workflow.yml')).Replace("`r`n","`n")
$jarSecurity=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'templates/jar-security-gates.sh')).Replace("`r`n","`n")
$security=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'templates/security-gates.sh')).Replace("`r`n","`n")
$sonar=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'templates/sonar-quality-gate.sh')).Replace("`r`n","`n")
foreach($repo in $repos){
    $folder=Join-Path $root $repo
    if(-not(Test-Path -LiteralPath $folder -PathType Container)){throw "Missing service folder: $repo"}
    $files=@(@('Jenkinsfile',$pipeline.Replace('__SERVICE__',$repo.ToLowerInvariant()).Replace('__OWNER__','vietluccuu')),
        @('.github/workflows/ci-cd.yml',$workflow),@('ci/security-gates.sh',$security),@('ci/jar-security-gates.sh',$jarSecurity),
        @('ci/sonar-quality-gate.sh',$sonar),@('ci/sonar-validate-gate.pl',[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'templates/sonar-validate-gate.pl')).Replace("`r`n","`n")))
    foreach($file in $files){
        $path=Join-Path $folder $file[0]
        if($DryRun){Write-Output "DRY_RUN $repo/$($file[0])";continue}
        New-Item -ItemType Directory -Force (Split-Path $path)|Out-Null
        [IO.File]::WriteAllText($path,$file[1])
        Write-Output "GENERATED $repo/$($file[0])"
    }
}
