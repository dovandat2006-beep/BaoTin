#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$JavaHome,[Parameter(Mandatory)][string]$AgentJar,
      [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$AgentJarSha256,
      [Parameter(Mandatory)][string]$SecretFile,[Parameter(Mandatory)][string]$JarEnvFile,
      [Parameter(Mandatory)][string]$StateFile,[string]$WorkDir='M:\BaoTin-deploy-agent')
$ErrorActionPreference='Stop'
if($WorkDir -ne 'M:\BaoTin-deploy-agent' -or -not(Test-Path -LiteralPath $WorkDir -PathType Container)){throw 'Existing approved agent directory required'}
$report=Join-Path $WorkDir 'agent-launch.result.json'
$process=$null;$state=$null;$saved=$false;$stage='token';$pendingCreated=$false
try {
    if(& (Join-Path $PSScriptRoot 'Get-ProcessElevation.ps1') -ProcessId $PID){throw 'Normal non-elevated user session required'}
    $stage='paths'
    $workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
    foreach($file in @($SecretFile,$JarEnvFile,$StateFile)){
        if([IO.Path]::GetFullPath($file).StartsWith($workspace+'\',[StringComparison]::OrdinalIgnoreCase) -or (Get-Item -LiteralPath $file -ErrorAction Stop).LinkType){throw 'Regular external agent configuration required'}
    }
    $before=[IO.File]::ReadAllText($StateFile)|ConvertFrom-Json -AsHashtable
    $stage='previous_pid'
    if($before.node -ne 'baotin-deploy-desktop' -or $before.workDir -ne $WorkDir){throw 'Prior approved ownership record required'}
    $old=Get-Process -Id $before.pid -ErrorAction SilentlyContinue
    if($old){throw 'Old ownership PID is present; inspect it before reconnecting'}
    $stage='preflight'
    & (Join-Path $PSScriptRoot 'Start-DeployAgent.ps1') -AgentJar $AgentJar -AgentJarSha256 $AgentJarSha256 -SecretFile $SecretFile -WorkDir $WorkDir -JavaHome $JavaHome -ValidateOnly
    foreach($file in @($AgentJar,$SecretFile)){if($file.Contains('"')){throw 'Invalid agent path quoting'}}
    $environment=@{BAOTIN_JAR_ENV_FILE=$JarEnvFile}
    foreach($key in 'SystemRoot','WINDIR','PATH','USERPROFILE','APPDATA','LOCALAPPDATA','TEMP','TMP'){
        $value=[Environment]::GetEnvironmentVariable($key);if($value){$environment[$key]=$value}
    }
    $arguments=@('-jar',('"'+$AgentJar+'"'),'-url','http://[::1]:8080/','-name','baotin-deploy-desktop','-secret',('"@'+$SecretFile+'"'),'-webSocket','-workDir',$WorkDir)
    $specFile=Join-Path $WorkDir 'agent-launch.spec.json'
    $pidFile=Join-Path $WorkDir 'agent-launch.pid'
    $errorFile=Join-Path $WorkDir 'agent-launch.error.txt'
    $spec=@{node='baotin-deploy-desktop';workDir=$WorkDir;javaExe=(Join-Path $JavaHome 'bin/java.exe');arguments=$arguments;
        outLog=(Join-Path $WorkDir 'agent.out.log');errLog=(Join-Path $WorkDir 'agent.err.log');pidFile=$pidFile;errorFile=$errorFile;environment=$environment}
    Remove-Item -LiteralPath $pidFile,$errorFile -Force -ErrorAction SilentlyContinue
    [IO.File]::WriteAllText($specFile,($spec|ConvertTo-Json -Depth 5))
    $stage='launch'
    # The scheduled task runs inside a job object that forbids breakaway, so the JVM is
    # started by a WMI-created job-free launcher; Jenkins runtime children can then detach.
    $launchCmd='"'+((Get-Process -Id $PID).Path)+'" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "'+(Join-Path $PSScriptRoot 'Start-AgentDetached.ps1')+'" -SpecFile "'+$specFile+'"'
    $created=([wmiclass]'Win32_Process').Create($launchCmd,$WorkDir,$null)
    if([int]$created.ReturnValue -ne 0){throw 'Detached agent launcher create failed'}
    $agentPid=0
    for($i=0;$i -lt 100 -and $agentPid -eq 0;$i++){
        if(Test-Path -LiteralPath $pidFile){
            $text=[IO.File]::ReadAllText($pidFile).Trim()
            if($text -match '^[1-9][0-9]{0,8}$'){$agentPid=[int]$text}
        }
        if($agentPid -eq 0){Start-Sleep -Milliseconds 200}
    }
    Remove-Item -LiteralPath $specFile -Force -ErrorAction SilentlyContinue
    if($agentPid -eq 0){
        $detail=if(Test-Path -LiteralPath $errorFile){([IO.File]::ReadAllText($errorFile).Trim()|Select-Object -First 1)}else{'launcher timeout'}
        throw "Detached agent launcher failed: $detail"
    }
    $process=Get-Process -Id $agentPid -ErrorAction Stop
    $state=@{node='baotin-deploy-desktop';pid=$process.Id;startTicks=$process.StartTime.ToUniversalTime().Ticks;workDir=$WorkDir;jarEnvFile=$JarEnvFile;agentJarSha256=$AgentJarSha256.ToLowerInvariant();elevated=$false}
    $stage='agent_token'
    if(& (Join-Path $PSScriptRoot 'Get-ProcessElevation.ps1') -ProcessId $process.Id){throw 'Agent unexpectedly elevated'}
    $stage='ownership_record'
    $pending=$StateFile+'.pending'
    $stream=[IO.File]::Open($pending,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None);$pendingCreated=$true;$stream.Dispose()
    $acl=[Security.AccessControl.FileSecurity]::new();$acl.SetAccessRuleProtection($true,$false)
    foreach($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')){
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid),'FullControl','Allow'))
    }
    Set-Acl -LiteralPath $pending -AclObject $acl
    [IO.File]::WriteAllText($pending,($state|ConvertTo-Json));Move-Item -LiteralPath $pending -Destination $StateFile -Force
    $saved=$true
    [IO.File]::WriteAllText($report,(@{result='STARTED';pid=$process.Id;elevated=$false}|ConvertTo-Json))
    'DEPLOY_AGENT_START PASS elevated=false owned_pid=recorded'
} catch {
    if($process -and $state -and -not $saved){$p=Get-Process -Id $process.Id -ErrorAction SilentlyContinue;if($p -and $p.StartTime.ToUniversalTime().Ticks -eq $state.startTicks){Stop-Process -Id $p.Id -Force}}
    if($pendingCreated -and -not $saved){Remove-Item -LiteralPath ($StateFile+'.pending') -ErrorAction SilentlyContinue}
    [IO.File]::WriteAllText($report,(@{result='FAILED';stage=$stage;details='Inspect agent preflight without exposing credential values'}|ConvertTo-Json))
    throw 'Owned agent launch failed; raw diagnostics withheld'
}
