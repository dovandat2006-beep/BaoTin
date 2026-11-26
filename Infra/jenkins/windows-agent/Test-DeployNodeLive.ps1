#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Username,[Parameter(Mandatory)][string]$TokenFile)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../Test-JenkinsPipelines.ps1') -Username $Username -TokenFile $TokenFile -PipelineFiles @((Join-Path $PSScriptRoot '../../Jenkinsfile'))
$id=[guid]::NewGuid().ToString('N')
$prefix='baotin-deploy-negative-'+$id
$code=@'
try {
    def j=jenkins.model.Jenkins.get()
    def node=j.getNode('baotin-deploy-desktop')
    if(node==null || node.numExecutors!=1 || node.mode!=hudson.model.Node.Mode.EXCLUSIVE ||
       node.labelString!='baotin-deploy-desktop' || !node.launcher.isWebSocket() || node.toComputer().offline ||
       !node.toComputer().isIdle())throw new IllegalStateException()
    def guard=hudson.model.queue.QueueTaskDispatcher.all().find{it.getClass().getName()=='BaoTinDeployNodeGuard'}
    if(guard==null)throw new IllegalStateException()
    def free=j.createProject(hudson.model.FreeStyleProject,'__PREFIX__-free')
    free.setAssignedLabel(j.getLabel('baotin-deploy-desktop'));free.save()
    def pipe=j.createProject(org.jenkinsci.plugins.workflow.job.WorkflowJob,'__PREFIX__-pipe')
    pipe.setDefinition(new org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition(
        "node('baotin-deploy-desktop') { echo 'NEGATIVE_MARKER_SHOULD_NOT_RUN' }",true));pipe.save()
    free.scheduleBuild2(0);pipe.scheduleBuild2(0)
    println 'DEPLOY_NEGATIVE_QUEUED tasks=2'
}catch(Throwable e){println 'DEPLOY_NEGATIVE_SETUP_FAILED'}
return null
'@
try {
    $r=Request 'scriptText' 'POST' @{script=$code.Replace('__PREFIX__',$prefix)} $headers
    if($r.StatusCode -ne 200 -or ([string]$r.Content).Trim() -ne 'DEPLOY_NEGATIVE_QUEUED tasks=2'){throw 'Negative queue fixture setup failed'}
    $passed=$false
    for($i=0;$i -lt 12;$i++){
        Start-Sleep -Seconds 1
        $check=@'
try {
    def j=jenkins.model.Jenkins.get();def n=j.getNode('baotin-deploy-desktop')
    def guard=hudson.model.queue.QueueTaskDispatcher.all().find{it.getClass().getName()=='BaoTinDeployNodeGuard'}
    def jobs=['__PREFIX__-free','__PREFIX__-pipe'].collect{j.getItemByFullName(it)}
    def items=j.queue.items.findAll{jobs.contains(it.task.getOwnerTask())}
    if(items.size()!=2 || items.any{!(it instanceof hudson.model.Queue.BuildableItem) || guard.canTake(n,it)==null}) {
        println 'DEPLOY_NEGATIVE_WAIT'
    }else{
        def pipelineItem=items.find{it.task instanceof org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution.PlaceholderTask}
        if(pipelineItem==null || !(pipelineItem.task.getOwnerExecutable() instanceof org.jenkinsci.plugins.workflow.job.WorkflowRun) ||
           pipelineItem.task.getOwnerExecutable().parent!=jobs[1])throw new IllegalStateException()
        if(jobs[0].lastBuild!=null || jobs[1].lastBuild.logFile.getText('UTF-8').contains('[Pipeline] echo') || !n.toComputer().isIdle())throw new IllegalStateException()
        println 'DEPLOY_NODE_LIVE PASS freestyle=blocked pipeline=blocked executor=idle guard=active commands_executed=0'
    }
}catch(Throwable e){println 'DEPLOY_NEGATIVE_VERIFY_FAILED'}
return null
'@
        $r=Request 'scriptText' 'POST' @{script=$check.Replace('__PREFIX__',$prefix)} $headers
        if($r.StatusCode -ne 200){throw 'Negative scheduling observation failed'}
        $output=([string]$r.Content).Trim()
        if($output -eq 'DEPLOY_NODE_LIVE PASS freestyle=blocked pipeline=blocked executor=idle guard=active commands_executed=0'){$passed=$true;Write-Output $output;break}
        if($output -ne 'DEPLOY_NEGATIVE_WAIT'){throw 'Negative scheduling assertions failed; raw diagnostics withheld'}
    }
    if(-not $passed){throw 'Negative scheduling fixture did not settle within bounded observation'}
} finally {
    $cleanup=@'
def j=jenkins.model.Jenkins.get()
def jobs=['__PREFIX__-free','__PREFIX__-pipe'].collect{j.getItemByFullName(it)}.findAll{it!=null}
j.queue.items.findAll{jobs.contains(it.task.getOwnerTask())}.each{j.queue.cancel(it)}
jobs.each{job->if(job.lastBuild!=null && job.lastBuild.isBuilding())job.lastBuild.doStop()}
println 'DEPLOY_NEGATIVE_STOP_REQUESTED'
return null
'@
    $r=Request 'scriptText' 'POST' @{script=$cleanup.Replace('__PREFIX__',$prefix)} $headers
    if($r.StatusCode -ne 200){throw 'Fixture cleanup request failed'}
    $deleted=$false
    for($i=0;$i -lt 10;$i++){
        Start-Sleep -Seconds 1
        $cleanup=@'
def j=jenkins.model.Jenkins.get()
def jobs=['__PREFIX__-free','__PREFIX__-pipe'].collect{j.getItemByFullName(it)}.findAll{it!=null}
if(jobs.any{it.isBuilding()}){println 'DEPLOY_NEGATIVE_CLEANUP_WAIT'}else{
    jobs.each{it.delete()}
    println 'DEPLOY_NEGATIVE_CLEANUP PASS jobs_removed=2 node=retained'
}
return null
'@
        $r=Request 'scriptText' 'POST' @{script=$cleanup.Replace('__PREFIX__',$prefix)} $headers
        if($r.StatusCode -eq 200 -and ([string]$r.Content).Trim() -eq 'DEPLOY_NEGATIVE_CLEANUP PASS jobs_removed=2 node=retained'){$deleted=$true;Write-Output ([string]$r.Content).Trim();break}
    }
    if(-not $deleted){throw 'Negative fixture cleanup not verified; inspect these exact temporary jobs'}
}
