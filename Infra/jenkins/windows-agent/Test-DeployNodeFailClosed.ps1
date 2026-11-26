#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Username,[Parameter(Mandatory)][string]$TokenFile)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../Test-JenkinsPipelines.ps1') -Username $Username -TokenFile $TokenFile -PipelineFiles @((Join-Path $PSScriptRoot '../../Jenkinsfile'))
$code=@'
def j=jenkins.model.Jenkins.get();def n=j.getNode('baotin-deploy-desktop')
if(n==null || n.numExecutors!=1 || n.toComputer().offline || !n.toComputer().isIdle()){
    println 'DEPLOY_FAIL_CLOSED_PRECONDITION_FAILED';return null
}
def original=n.numExecutors
try {
    def source=new File(j.rootDir,'baotin-deploy-node-guard.groovy')
    def hash=java.security.MessageDigest.getInstance('SHA-256').digest(source.bytes).encodeHex().toString()
    def hook=new File(j.rootDir,'init.groovy.d/baotin-deploy-node.groovy')
    def badCopy=hook.getText('UTF-8').replace(hash,'0'*64)
    boolean denied=false
    try{new GroovyShell(j.pluginManager.uberClassLoader).evaluate(badCopy)}catch(IllegalStateException expected){denied=true}
    if(!denied || n.numExecutors!=0)throw new IllegalStateException('Invalid loader copy did not disable node')
    println 'DEPLOY_INIT_FAIL_CLOSED PASS copy_hash=invalid executors=0'
}finally{
    n.setNumExecutors(original);n.save()
    new GroovyShell(j.pluginManager.uberClassLoader).evaluate(new File(j.rootDir,'init.groovy.d/baotin-deploy-node.groovy'))
    if(n.numExecutors!=1 || n.toComputer().offline || hudson.model.queue.QueueTaskDispatcher.all().count{it.getClass().getName()=='BaoTinDeployNodeGuard'}!=1)throw new IllegalStateException('Node restoration not verified')
    println 'DEPLOY_INIT_RESTORED PASS executors=1 guard_count=1 online=true controller_restart=false'
}
return null
'@
$r=Request 'scriptText' 'POST' @{script=$code} $headers
$expected=@('DEPLOY_INIT_FAIL_CLOSED PASS copy_hash=invalid executors=0','DEPLOY_INIT_RESTORED PASS executors=1 guard_count=1 online=true controller_restart=false') -join "`n"
if($r.StatusCode -ne 200 -or ([string]$r.Content).Trim().Replace("`r",'') -ne $expected){throw 'Init fail-closed/restoration test failed; inspect owned node'}
Write-Output $expected
