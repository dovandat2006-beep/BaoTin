// Loaded as an operator-owned init hook; does not change controller authentication.
import jenkins.model.Jenkins
import hudson.model.Node
import hudson.model.Queue
import hudson.model.queue.QueueTaskDispatcher
import hudson.model.queue.CauseOfBlockage
import hudson.plugins.git.GitSCM
import hudson.plugins.git.util.BuildData
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowRun
import org.jenkinsci.plugins.workflow.support.steps.ExecutorStepExecution.PlaceholderTask

class BaoTinDeployNodeGuard extends QueueTaskDispatcher {
    static final String NODE='baotin-deploy-desktop'
    static final String JOB='baotin/FullStack'
    static final String REPOSITORY='https://github.com/vietluccuu/Infra.git'

    // Pure predicate shared by queue enforcement and synthetic regression tests.
    static boolean permits(Map s) {
        s.placeholder == true && s.job == JOB && s.running == true && s.replayed == false &&
        s.definition == true && s.lightweight == false && s.scriptPath == 'Jenkinsfile' &&
        s.urls == [REPOSITORY] && s.credentials == ['baotin-github-scm'] &&
        s.approved instanceof String && s.approved ==~ /[a-f0-9]{40}/ && s.approved != '0'*40 &&
        s.branches == [s.approved] && s.loaded instanceof List && !s.loaded.isEmpty() &&
        s.loaded.every { it == s.approved }
    }

    Map snapshot(Queue.Task task) {
        if(!(task instanceof PlaceholderTask)) return [placeholder:false]
        def run=task.getOwnerExecutable()
        if(!(run instanceof WorkflowRun)) return [placeholder:false]
        def job=run.getParent()
        def definition=job.getDefinition()
        if(!(definition instanceof CpsScmFlowDefinition) || !(definition.scm instanceof GitSCM))
            return [placeholder:true,definition:false]
        def file=new File(Jenkins.get().rootDir,'baotin-approved-main.json')
        if(!file.isFile() || java.nio.file.Files.isSymbolicLink(file.toPath()) || file.length()>32768)
            return [placeholder:true,definition:false]
        def approval=new groovy.json.JsonSlurperClassic().parseText(file.getText('UTF-8'))
        def sha=approval.schemaVersion == 1 ? approval.repositories?.Infra : null
        def revisions=run.getActions(BuildData).findAll {
            it.getRemoteUrls().contains(REPOSITORY)
        }.collect { it.lastBuiltRevision?.sha1String }
        // Replay/restart may reuse SCM metadata with a different script or execution path.
        def replayed=run.getCauses().any {
            it.getClass().getName() in [
                'org.jenkinsci.plugins.workflow.cps.replay.ReplayCause',
                'org.jenkinsci.plugins.pipeline.modeldefinition.causes.RestartDeclarativePipelineCause'
            ]
        }
        [placeholder:true,job:job.fullName,running:run.isBuilding(),replayed:replayed,
         definition:true,lightweight:definition.lightweight,scriptPath:definition.scriptPath,
         urls:definition.scm.userRemoteConfigs*.url,credentials:definition.scm.userRemoteConfigs*.credentialsId,
         branches:definition.scm.branches*.name,approved:sha,loaded:revisions]
    }

    @Override CauseOfBlockage canTake(Node node, Queue.BuildableItem item) {
        canTake(node,item.task)
    }

    @Override CauseOfBlockage canTake(Node node, Queue.Task task) {
        if(node.nodeName != NODE) return null
        try { if(permits(snapshot(task))) return null } catch(Exception ignored) { }
        new CauseOfBlockage() {
            @Override String getShortDescription() {
                'BaoTin deploy node requires the reviewed Infra SCM run; other tasks are denied'
            }
        }
    }
}

def guard=new BaoTinDeployNodeGuard()
if(binding.hasVariable('BAOTIN_GUARD_TEST_ONLY') && binding.getVariable('BAOTIN_GUARD_TEST_ONLY') == true)
    return guard
def list=QueueTaskDispatcher.all()
def previous=list.findAll { it.getClass().getName() == 'BaoTinDeployNodeGuard' }
try {
    previous.each { list.remove(it) }
    list.add(guard)
    if(list.count { it.getClass().getName() == 'BaoTinDeployNodeGuard' } != 1)
        throw new IllegalStateException('Deploy guard registration failed')
} catch(Exception failure) {
    list.remove(guard)
    previous.each { list.add(it) }
    throw new IllegalStateException('Deploy guard registration rolled back')
}
return guard
