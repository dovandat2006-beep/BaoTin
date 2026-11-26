#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Username,[Parameter(Mandatory)][string]$TokenFile)
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'Test-DeployNodeGuard.ps1') -Username $Username -TokenFile $TokenFile
. (Join-Path $PSScriptRoot '../Test-JenkinsPipelines.ps1') -Username $Username -TokenFile $TokenFile -PipelineFiles @((Join-Path $PSScriptRoot '../../Jenkinsfile'))
$source=Join-Path $PSScriptRoot 'DeployNodeGuard.groovy'
$hash=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
$hook=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'DeployNodeInit.groovy')).Replace('__GUARD_SHA256__',$hash)
$temp=Join-Path $env:TEMP ('baotin-guard-hook-'+[guid]::NewGuid().ToString('N')+'.groovy')
$remote='/var/jenkins_home/baotin-deploy-node-guard.groovy'
$init='/var/jenkins_home/init.groovy.d/baotin-deploy-node.groovy'
$installed=$false
try {
    # Initial installation only; a replacement must preserve/review its prior source separately.
    & docker --context desktop-linux exec jenkins-blueocean sh -c "test ! -e '$remote' && test ! -L '$remote' && test ! -e '$init' && test ! -L '$init'"
    if($LASTEXITCODE -ne 0){throw 'Existing guard files preserved; use reviewed replacement transaction'}
    [IO.File]::WriteAllText($temp,$hook,[Text.UTF8Encoding]::new($false))
    & docker --context desktop-linux cp $source "jenkins-blueocean:${remote}.pending"
    if($LASTEXITCODE){throw 'Guard source copy failed'}
    & docker --context desktop-linux cp $temp "jenkins-blueocean:${init}.pending"
    if($LASTEXITCODE){throw 'Guard hook copy failed'}
    # docker cp creates root-owned files; the existing init directory is also root-owned.
    # Use root only for these two fixed files, retaining read-only ownership for Jenkins.
    & docker --context desktop-linux exec --user 0 jenkins-blueocean sh -c "chmod 644 '${remote}.pending' '${init}.pending' && mv '${remote}.pending' '$remote' && mv '${init}.pending' '$init'"
    if($LASTEXITCODE){throw 'Guard persistence failed'}
    $installed=$true
    $code=@'
try {
    def j=jenkins.model.Jenkins.get()
    new GroovyShell(j.pluginManager.uberClassLoader).evaluate(new File(j.rootDir,'init.groovy.d/baotin-deploy-node.groovy'))
    if(hudson.model.queue.QueueTaskDispatcher.all().count{it.getClass().getName()=='BaoTinDeployNodeGuard'}!=1)throw new IllegalStateException()
    println 'DEPLOY_GUARD_INSTALLED dispatchers=1 persisted=true controller_restart=false'
}catch(Throwable e){println 'DEPLOY_GUARD_INSTALL_FAILED'}
return null
'@
    $r=Request 'scriptText' 'POST' @{script=$code} $headers
    if($r.StatusCode -ne 200 -or ([string]$r.Content).Trim() -ne 'DEPLOY_GUARD_INSTALLED dispatchers=1 persisted=true controller_restart=false'){throw 'Guard registration not verified; inspect current state before retry'}
    Write-Output ([string]$r.Content).Trim()
} finally {
    Remove-Item -LiteralPath $temp -ErrorAction SilentlyContinue
    if(-not $installed){
        & docker --context desktop-linux exec --user 0 jenkins-blueocean sh -c "rm -f '${remote}.pending' '${init}.pending'" | Out-Null
    }
}
