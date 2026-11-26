// Operator-owned transaction; validates all targets before changing any definition.
import jenkins.model.Jenkins
import hudson.plugins.git.GitSCM
import hudson.plugins.git.BranchSpec
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition
def pins=new groovy.json.JsonSlurperClassic().parseText('''__PINS_JSON__''')
def j=Jenkins.get()
def expected=['API-Gateway','Auth-Service','Threat-Service','Report-Service','Reputation-Service',
    'Model-Inference-Service','Notification-Service','Search-Service','Campaign-Service','Analytics-Service','Frontend','Infra']
if(pins.schemaVersion!=1 || pins.repositories.keySet()!=expected.toSet() ||
   pins.repositories.values().any{!(it instanceof String) || !(it==~/[a-f0-9]{40}/) || it=='0'*40})throw new IllegalStateException('Invalid reviewed pin scope')
def targets=[]
expected.each{repo->
    def name=repo=='Infra'?'FullStack':repo
    def job=j.getItemByFullName('baotin/'+name)
    if(job==null || job.isBuilding() || j.queue.items.any{it.task==job})throw new IllegalStateException('Target missing/active; pins unchanged')
    def old=job.definition
    if(!(old instanceof CpsScmFlowDefinition) || !(old.scm instanceof GitSCM) || old.scriptPath!='Jenkinsfile' ||
       old.scm.userRemoteConfigs.size()!=1 || old.scm.userRemoteConfigs[0].url!='https://github.com/vietluccuu/'+repo+'.git' ||
       old.scm.userRemoteConfigs[0].credentialsId!='baotin-github-scm')throw new IllegalStateException('Unexpected source boundary; pins unchanged')
    def scm=new GitSCM(old.scm.userRemoteConfigs,[new BranchSpec(pins.repositories[repo])],old.scm.browser,old.scm.gitTool,old.scm.extensions)
    def definition=new CpsScmFlowDefinition(scm,old.scriptPath);definition.setLightweight(false)
    targets<<[job:job,old:old,next:definition,revision:pins.repositories[repo]]
}
def file=new File(j.rootDir,'baotin-approved-main.json')
def before=file.exists()?file.bytes:null
def changed=[]
try{
    targets.each{t->changed<<t;t.job.setDefinition(t.next);t.job.save()}
    targets.each{t->if(t.job.definition.scm.branches*.name!=[t.revision] || t.job.definition.lightweight)throw new IllegalStateException('Pin verification failed')}
    def temp=new File(j.rootDir,'baotin-approved-main.json.pending')
    temp.setText(groovy.json.JsonOutput.toJson(pins),'UTF-8')
    java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
}catch(Throwable failure){
    changed.reverseEach{t->t.job.setDefinition(t.old);t.job.save()}
    if(before==null){file.delete()}else{file.bytes=before}
    throw new IllegalStateException('Reviewed pin transaction failed; previous definitions restored')
}
println('REVIEWED_PINS_APPLIED jobs=12 sandbox=retained builds_started=0')
return null
