// Append to DeployNodeGuard.groovy in a TEST_ONLY binding on the existing controller.
def guardForTest=new BaoTinDeployNodeGuard()
def good=[placeholder:true,job:'baotin/FullStack',running:true,replayed:false,
          definition:true,lightweight:false,scriptPath:'Jenkinsfile',
          urls:['https://github.com/vietluccuu/Infra.git'],credentials:['baotin-github-scm'],
          approved:'a'*40,branches:['a'*40],loaded:['a'*40]]
assert BaoTinDeployNodeGuard.permits(good)
def changes=[
    [placeholder:false], [job:'baotin/Auth-Service'], [job:'baotin/FullStack-copy'],
    [running:false], [replayed:true], [definition:false], [lightweight:true],
    [scriptPath:'team/Jenkinsfile'], [urls:['https://github.com/vietluccuu/Frontend.git']],
    [urls:['https://github.com/vietluccuu/Infra.git','https://example.test/evil.git']],
    [credentials:['global-token']], [approved:null], [approved:'main'], [approved:'0'*40],
    [branches:['main']], [branches:['a'*40,'develop']], [loaded:[]],
    [loaded:['b'*40]], [loaded:['a'*40,'b'*40]], [loaded:[null]]
]
changes.each { assert !BaoTinDeployNodeGuard.permits(good+it) }
assert !BaoTinDeployNodeGuard.permits([:])
def launcher=new hudson.slaves.JNLPLauncher();launcher.setWebSocket(true)
def node=new hudson.slaves.DumbSlave('baotin-deploy-desktop','test only','M:\\not-created','1',
    hudson.model.Node.Mode.EXCLUSIVE,'baotin-deploy-desktop',launcher,new hudson.slaves.RetentionStrategy.Always())
// A real Queue.Task implementation for which owner-executable is not a Pipeline run.
def job=new hudson.model.FreeStyleProject(jenkins.model.Jenkins.get(),'baotin-guard-not-saved')
assert guardForTest.canTake(node,(hudson.model.Queue.Task)job) != null
def waiting=new hudson.model.Queue.WaitingItem(java.util.Calendar.getInstance(),job,[])
def item=new hudson.model.Queue.BuildableItem(waiting)
assert guardForTest.canTake(node,item) != null
def other=new hudson.slaves.DumbSlave('unrelated-node','test only','M:\\not-created','1',
    hudson.model.Node.Mode.EXCLUSIVE,'unrelated-node',launcher,new hudson.slaves.RetentionStrategy.Always())
assert guardForTest.canTake(other,item) == null
println 'DEPLOY_NODE_GUARD PASS checks=25 reviewed_sha=required replay=denied other_jobs=denied unrelated_nodes=unchanged mutation=false'
return null
