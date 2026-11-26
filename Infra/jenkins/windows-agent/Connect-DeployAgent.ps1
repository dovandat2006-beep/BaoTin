#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Username,[Parameter(Mandatory)][string]$TokenFile,
      [Parameter(Mandatory)][string]$JavaHome,[Parameter(Mandatory)][string]$AgentJar,
      [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$AgentJarSha256,
      [Parameter(Mandatory)][string]$SecretFile,[Parameter(Mandatory)][string]$JarEnvFile,
      [string]$WorkDir='M:\BaoTin-deploy-agent')
$ErrorActionPreference='Stop'
if(& (Join-Path $PSScriptRoot 'Get-ProcessElevation.ps1') -ProcessId $PID){throw 'Connect from the normal non-elevated user session'}
. (Join-Path $PSScriptRoot '../Test-JenkinsPipelines.ps1') -Username $Username -TokenFile $TokenFile -PipelineFiles @((Join-Path $PSScriptRoot '../../Jenkinsfile'))
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$work=[IO.Path]::GetFullPath($WorkDir)
if($work -ne 'M:\BaoTin-deploy-agent'){throw 'Approved deploy workdir required'}
foreach($path in @($SecretFile,$JarEnvFile)){
    $full=[IO.Path]::GetFullPath($path)
    if($full.StartsWith($workspace+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'External node configuration required'}
}
if(-not(Test-Path -LiteralPath $JarEnvFile -PathType Leaf)){throw 'Public JAR env draft required'}
if((Test-Path -LiteralPath $SecretFile) -or (Test-Path -LiteralPath $work)){throw 'Existing agent files preserved; inspect owned state before reconnecting'}
$stateFile=Join-Path (Split-Path -Parent $SecretFile) 'deploy-agent.state.json'
if(Test-Path -LiteralPath $stateFile){throw 'Existing agent ownership record preserved'}
$allowed=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')
function Protect-File([string]$Path){
    $acl=[Security.AccessControl.FileSecurity]::new();$acl.SetAccessRuleProtection($true,$false)
    foreach($sid in $allowed){$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid),'FullControl','Allow'))}
    Set-Acl -LiteralPath $Path -AclObject $acl
}
$nodeCreated=$false;$secretCreated=$false;$stateCreated=$false;$process=$null;$connected=$false;$state=$null
$old=@{};foreach($key in 'BAOTIN_JAR_ENV_FILE','DOCKER_HOST','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH'){$old[$key]=[Environment]::GetEnvironmentVariable($key)}
try {
    $code=@'
try {
    def j=jenkins.model.Jenkins.get()
    if(j.getNode('baotin-deploy-desktop')!=null)throw new IllegalStateException()
    if(hudson.model.queue.QueueTaskDispatcher.all().count{it.getClass().getName()=='BaoTinDeployNodeGuard'}!=1)throw new IllegalStateException()
    def hook=new File(j.rootDir,'init.groovy.d/baotin-deploy-node.groovy')
    if(!hook.isFile())throw new IllegalStateException()
    def pins=new groovy.json.JsonSlurperClassic().parseText(new File(j.rootDir,'baotin-approved-main.json').getText('UTF-8'))
    def job=j.getItemByFullName('baotin/FullStack')
    if(job==null || job.definition.scm.branches*.name!=[pins.repositories.Infra])throw new IllegalStateException()
    def launcher=new hudson.slaves.JNLPLauncher();launcher.setWebSocket(true)
    def node=new hudson.slaves.DumbSlave('baotin-deploy-desktop','BaoTin reviewed Infra deployment only',
        'M:/BaoTin-deploy-agent','1',hudson.model.Node.Mode.EXCLUSIVE,'baotin-deploy-desktop',launcher,new hudson.slaves.RetentionStrategy.Always())
    node.nodeProperties.add(new hudson.slaves.EnvironmentVariablesNodeProperty(
        new hudson.slaves.EnvironmentVariablesNodeProperty.Entry('BAOTIN_JAR_ENV_FILE','__JAR_ENV_PATH__')))
    j.addNode(node)
    println 'DEPLOY_NODE_CREATED executors=1 mode=EXCLUSIVE websocket=true builds_started=0'
}catch(Throwable e){println 'DEPLOY_NODE_CREATE_FAILED'}
return null
'@
    $publicPath=[IO.Path]::GetFullPath($JarEnvFile).Replace('\','/')
    if($publicPath.Contains("'") -or $publicPath.Contains("`n") -or $publicPath.Contains("`r")){throw 'Invalid public env path'}
    $r=Request 'scriptText' 'POST' @{script=$code.Replace('__JAR_ENV_PATH__',$publicPath)} $headers
    if($r.StatusCode -ne 200 -or ([string]$r.Content).Trim() -ne 'DEPLOY_NODE_CREATED executors=1 mode=EXCLUSIVE websocket=true builds_started=0'){throw 'Node creation not verified; inspect current state before retry'}
    $nodeCreated=$true
    Write-Output ([string]$r.Content).Trim()
    # Receive the generated secret in memory and write only to an external ACL-restricted file.
    $r=Request 'scriptText' 'POST' @{script="println jenkins.model.Jenkins.get().getNode('baotin-deploy-desktop').toComputer().getJnlpMac(); return null"} $headers
    $secret=([string]$r.Content).Trim()
    if($r.StatusCode -ne 200 -or $secret -notmatch '^[a-fA-F0-9]{64}$'){throw 'Generated remoting secret invalid; raw response withheld'}
    # Restrict an empty file before any secret bytes are written.
    $stream=[IO.File]::Open($SecretFile,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None);$secretCreated=$true;$stream.Dispose()
    Protect-File $SecretFile
    [IO.File]::WriteAllText($SecretFile,$secret,[Text.UTF8Encoding]::new($false));$secret=$null;$r=$null
    & (Join-Path $PSScriptRoot 'Start-DeployAgent.ps1') -AgentJar $AgentJar -AgentJarSha256 $AgentJarSha256 -SecretFile $SecretFile -WorkDir $work -JavaHome $JavaHome -ValidateOnly
    New-Item -ItemType Directory -Path $work | Out-Null
    $acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetAccessRuleProtection($true,$false)
    foreach($sid in $allowed){$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid),'FullControl','ContainerInherit,ObjectInherit','None','Allow'))}
    Set-Acl -LiteralPath $work -AclObject $acl
    $env:BAOTIN_JAR_ENV_FILE=$JarEnvFile;$env:DOCKER_HOST=$null;$env:DOCKER_TLS_VERIFY=$null;$env:DOCKER_CERT_PATH=$null
    # These approved paths have no spaces; use quoted arguments for caller-supplied JAR/secret paths.
    foreach($path in @($AgentJar,$SecretFile)){if($path.Contains('"')){throw 'Invalid remoting path quoting'}}
    $args=@('-jar',('"'+$AgentJar+'"'),'-url','http://[::1]:8080/','-name','baotin-deploy-desktop','-secret',('"@'+$SecretFile+'"'),'-webSocket','-workDir',$work)
    $process=Start-Process -FilePath (Join-Path $JavaHome 'bin/java.exe') -ArgumentList $args -WorkingDirectory $work -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $work 'agent.out.log') -RedirectStandardError (Join-Path $work 'agent.err.log')
    $state=@{node='baotin-deploy-desktop';pid=$process.Id;startTicks=$process.StartTime.ToUniversalTime().Ticks;workDir=$work;jarEnvFile=$JarEnvFile;agentJarSha256=$AgentJarSha256.ToLowerInvariant()}
    if(& (Join-Path $PSScriptRoot 'Get-ProcessElevation.ps1') -ProcessId $process.Id){throw 'Agent unexpectedly elevated'}
    $state.elevated=$false
    $stream=[IO.File]::Open($stateFile,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None);$stateCreated=$true;$stream.Dispose()
    Protect-File $stateFile;[IO.File]::WriteAllText($stateFile,($state|ConvertTo-Json))
    for($i=0;$i -lt 30;$i++){
        $process.Refresh();if($process.HasExited){throw 'Owned remoting process exited; inspect restricted local log'}
        $r=Request 'computer/baotin-deploy-desktop/api/json?tree=offline,temporarilyOffline,numExecutors' 'GET'
        if($r.StatusCode -eq 200){$status=$r.Content|ConvertFrom-Json;if(-not $status.offline -and -not $status.temporarilyOffline -and $status.numExecutors -eq 1){$connected=$true;break}}
        Start-Sleep -Seconds 2
    }
    if(-not $connected){throw 'Agent connection not verified within bounded wait'}
    'DEPLOY_AGENT_CONNECTED node=baotin-deploy-desktop executors=1 transport=websocket secret_logged=false deploy_started=false'
} finally {
    foreach($key in $old.Keys){[Environment]::SetEnvironmentVariable($key,$old[$key],'Process')}
    if(-not $connected){
        if($process -and $state){$p=Get-Process -Id $process.Id -ErrorAction SilentlyContinue;if($p -and $p.StartTime.ToUniversalTime().Ticks -eq $state.startTicks){Stop-Process -Id $p.Id -Force}}
        if($nodeCreated){
            $code="def j=jenkins.model.Jenkins.get();def n=j.getNode('baotin-deploy-desktop');if(n!=null && n.toComputer().isIdle()){j.removeNode(n);println 'DEPLOY_NODE_CREATE_ROLLBACK removed=true'};return null"
            $r=Request 'scriptText' 'POST' @{script=$code} $headers
            if($r.StatusCode -ne 200 -or ([string]$r.Content).Trim() -ne 'DEPLOY_NODE_CREATE_ROLLBACK removed=true'){throw 'Node cleanup requires authoritative inspection'}
            Write-Output ([string]$r.Content).Trim()
            if($secretCreated){Remove-Item -LiteralPath $SecretFile -ErrorAction SilentlyContinue}
            if($stateCreated){Remove-Item -LiteralPath $stateFile -ErrorAction SilentlyContinue}
        }
        # Keep restricted remoting logs on failure; no recursive removal of a live workdir.
    }
}
