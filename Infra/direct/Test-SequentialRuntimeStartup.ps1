$ErrorActionPreference='Stop'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Deploy-Jars.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Deployment script does not parse'}
foreach($f in $ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Launch'},$true)){
    . ([scriptblock]::Create($f.Extent.Text))
}
$script:events=[Collections.Generic.List[string]]::new()
$script:stopped=@()
$script:failReady=$null
$names=@('Threat-Service','Report-Service','Notification-Service')
$ports=@{'Threat-Service'=8182;'Report-Service'=8183;'Notification-Service'=8186}
$java='C:\fixture\java.exe';$node='C:\fixture\node.exe'
$cfg=@{'THREAT_CONFIG_DIR'='C:\fixture\threat';'REPORT_CONFIG_DIR'='C:\fixture\report';'NOTIFICATION_CONFIG_DIR'='C:\fixture\notification';'AUTH_ALLOWED_ORIGINS'='https://baotin.rimiprod.me'}
function Start-BaoTinDetachedProcess($Info,$LogPrefix){
    $name=[IO.Path]::GetFileName($LogPrefix);$script:events.Add('start:'+$name)
    return [pscustomobject]@{Id=100001+$script:events.Count;StartTime=[DateTime]::Now}
}
function Wait-RuntimeService($State,$Name){
    $script:events.Add('ready:'+$Name)
    if($Name -eq $script:failReady){throw 'Fixture startup/readiness failure'}
}
function Stop-Owned($State){$script:stopped=@($State.processes)}
$state=Launch 'C:\fixture\accepted-release'
$expected=@('start:Threat-Service','ready:Threat-Service','start:Report-Service','ready:Report-Service','start:Notification-Service','ready:Notification-Service')
if(($script:events -join '|') -ne ($expected -join '|')){throw 'A service started before the previous service became ready'}
$script:events.Clear();$script:failReady='Report-Service';$failed=$false
try{Launch 'C:\fixture\rejected-release'|Out-Null}catch{$failed=$true}
if(-not $failed -or $script:stopped.Count -ne 2 -or ($script:events -join '|') -ne (($expected|Select-Object -First 4) -join '|')){throw 'Partial startup was not stopped transactionally'}
'SEQUENTIAL_RUNTIME_STARTUP PASS orderedReadiness=3 partialFailureCleanup=2 laterServicesStarted=0 realProcessesStarted=0'
