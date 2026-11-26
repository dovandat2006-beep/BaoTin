#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Username,[Parameter(Mandatory)][string]$TokenFile,
      [Parameter(Mandatory)][string]$StateFile,[Parameter(Mandatory)][string]$SecretFile,
      [string]$OutputFile)
$ErrorActionPreference='Stop'
$inspectionOutput=$OutputFile # Dot-sourced Jenkins validator has its own OutputFile parameter.
$state=[IO.File]::ReadAllText($StateFile)|ConvertFrom-Json -AsHashtable
$p=Get-Process -Id $state.pid -ErrorAction Stop
if($p.ProcessName -ne 'java' -or $p.StartTime.ToUniversalTime().Ticks -ne $state.startTicks){throw 'Agent ownership mismatch'}
if(& (Join-Path $PSScriptRoot 'Get-ProcessElevation.ps1') -ProcessId $p.Id){throw 'Agent must not run elevated'}
$allow=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')
foreach($file in @($StateFile,$SecretFile)){
    if((Get-Item -LiteralPath $file).LinkType){throw 'Regular agent file required'}
    foreach($rule in (Get-Acl -LiteralPath $file).Access){if($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin $allow){throw 'Broad agent file ACL'}}
}
$secret=[IO.File]::ReadAllText($SecretFile).Trim()
foreach($name in 'agent.out.log','agent.err.log'){
    $file=Join-Path $state.workDir $name
    if(Test-Path $file){
        $stream=[IO.File]::Open($file,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
        $reader=[IO.StreamReader]::new($stream)
        try{if($reader.ReadToEnd().Contains($secret)){throw 'Remoting log contains node secret'}}finally{$reader.Dispose()}
    }
}
$secret=$null
. (Join-Path $PSScriptRoot '../Test-JenkinsPipelines.ps1') -Username $Username -TokenFile $TokenFile -PipelineFiles @((Join-Path $PSScriptRoot '../../Jenkinsfile'))
$code=@'
try {
    def j=jenkins.model.Jenkins.get();def n=j.getNode('baotin-deploy-desktop')
    if(n==null || n.numExecutors!=1 || n.mode!=hudson.model.Node.Mode.EXCLUSIVE ||
       n.labelString!='baotin-deploy-desktop' || !n.launcher.isWebSocket() || n.toComputer().offline ||
       !n.toComputer().isIdle())throw new IllegalStateException()
    if(j.securityRealm.allowsSignup() || hudson.model.User.getAll().findAll{it.getProperty(hudson.security.HudsonPrivateSecurityRealm.Details)!=null}.size()!=1)throw new IllegalStateException()
    if(hudson.model.queue.QueueTaskDispatcher.all().count{it.getClass().getName()=='BaoTinDeployNodeGuard'}!=1)throw new IllegalStateException()
    def source=new File(j.rootDir,'baotin-deploy-node-guard.groovy')
    def hash=java.security.MessageDigest.getInstance('SHA-256').digest(source.bytes).encodeHex().toString()
    if(hash!='__GUARD_SHA__')throw new IllegalStateException()
    def hook=new File(j.rootDir,'init.groovy.d/baotin-deploy-node.groovy')
    if(!hook.isFile() || !hook.getText('UTF-8').contains(hash))throw new IllegalStateException()
    println groovy.json.JsonOutput.toJson([node:'baotin-deploy-desktop',online:true,executors:1,mode:'EXCLUSIVE',websocket:true,guardCount:1,guardSha256:hash,signup:false,accounts:1,idle:true])
}catch(Throwable e){println 'DEPLOY_NODE_INSPECT_FAILED'}
return null
'@
$hash=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'DeployNodeGuard.groovy')).Hash.ToLowerInvariant()
$r=Request 'scriptText' 'POST' @{script=$code.Replace('__GUARD_SHA__',$hash)} $headers
if($r.StatusCode -ne 200 -or ([string]$r.Content).Trim() -eq 'DEPLOY_NODE_INSPECT_FAILED'){throw 'Node acceptance query failed; raw diagnostics withheld'}
$result=$r.Content|ConvertFrom-Json -AsHashtable
$result['elevated']=$false;$result['secretAcl']='restricted';$result['secretLogged']=$false
if($inspectionOutput){
    $output=[IO.Path]::GetFullPath($inspectionOutput)
    if($output -in @([IO.Path]::GetFullPath($StateFile),[IO.Path]::GetFullPath($SecretFile),[IO.Path]::GetFullPath($TokenFile)) -or [IO.Path]::GetExtension($output) -ne '.json' -or ((Test-Path $output) -and (Get-Item $output).LinkType)){throw 'Distinct public JSON report required'}
    [IO.File]::WriteAllText($output,($result|ConvertTo-Json))
}
'DEPLOY_NODE_ACCEPTANCE PASS online=true executors=1 mode=EXCLUSIVE websocket=true elevated=false guard=sha_verified signup=false accounts=1 secret_acl=restricted secret_logged=false idle=true'
