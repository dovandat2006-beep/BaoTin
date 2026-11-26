[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RuntimeRoot,
    [Parameter(Mandatory)][string]$PrometheusHome,
    [Parameter(Mandatory)][string]$BlackboxHome,
    [Parameter(Mandatory)][string]$GrafanaHome
)
$ErrorActionPreference='Stop'
$RuntimeRoot=[IO.Path]::GetFullPath($RuntimeRoot)
if(-not (Test-Path -LiteralPath $RuntimeRoot -PathType Container)){throw 'Create an external runtime directory first'}
foreach($path in @($RuntimeRoot,$PrometheusHome,$BlackboxHome,$GrafanaHome)){
    $item=Get-Item -LiteralPath $path
    while($item){
        if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Reparse paths are not accepted'}
        $item=$item.Parent
    }
}
$statePath=Join-Path $RuntimeRoot 'active.json'
if(Test-Path -LiteralPath $statePath){
    $previous=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    foreach($entry in $previous.processes){
        if(Get-Process -Id $entry.pid -ErrorAction SilentlyContinue){throw 'Existing monitoring PID record; inspect or stop with ownership guard first'}
    }
}
foreach($port in @(3000,9090,9115)){
    if([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()|Where-Object Port -eq $port){throw "Preserve occupied monitoring port $port"}
}
$prom=Join-Path $PrometheusHome 'prometheus.exe'
$promtool=Join-Path $PrometheusHome 'promtool.exe'
$blackbox=Join-Path $BlackboxHome 'blackbox_exporter.exe'
$grafana=Join-Path $GrafanaHome 'bin/grafana.exe'
foreach($exe in @($prom,$promtool,$blackbox,$grafana)){if(-not (Test-Path -LiteralPath $exe -PathType Leaf)){throw 'Missing verified monitoring executable'}}
& $promtool check config (Join-Path $PSScriptRoot 'prometheus.yml')
if($LASTEXITCODE){throw 'Invalid Prometheus config'}
foreach($dir in @('logs','prometheus-data','grafana-data','grafana-logs','grafana-plugins')){
    New-Item -ItemType Directory -Path (Join-Path $RuntimeRoot $dir) -Force | Out-Null
}
# Bootstrap secret exists only outside Git and is readable only by the current user.
$passwordFile=Join-Path $RuntimeRoot 'grafana-admin.secret'
if(-not (Test-Path -LiteralPath $passwordFile)){
    $password=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    [IO.File]::WriteAllText($passwordFile,$password)
    $acl=[Security.AccessControl.FileSecurity]::new()
    $acl.SetAccessRuleProtection($true,$false)
    $identity=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($identity,'FullControl','Allow'))
    Set-Acl -LiteralPath $passwordFile -AclObject $acl
}
. (Join-Path $PSScriptRoot 'HiddenProcess.ps1')
$state=@{createdAt=[DateTime]::UtcNow.ToString('o'); stoppedAt=$null; processes=@()}
function Launch([string]$Name,[string]$Exe,[string[]]$Arguments,[hashtable]$Variables){
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=[IO.Path]::GetFullPath($Exe)
    $info.WorkingDirectory=$RuntimeRoot
    foreach($arg in $Arguments){$info.ArgumentList.Add($arg)}
    foreach($key in $Variables.Keys){$info.Environment[$key]=[string]$Variables[$key]}
    $p=Start-BaoTinMonitoringHiddenProcess $info (Join-Path $RuntimeRoot "logs/$Name")
    $state.processes+=@{name=$Name;pid=$p.Id;startTicks=$p.StartTime.ToUniversalTime().Ticks;path=$info.FileName}
    $state|ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $statePath -Encoding utf8
}
function Wait-Ready([string]$Url){
    $until=[DateTime]::UtcNow.AddSeconds(180)
    do{
        foreach($entry in $state.processes){if(-not (Get-Process -Id $entry.pid -ErrorAction SilentlyContinue)){throw 'Monitoring process exited during startup'}}
        try{$r=Invoke-WebRequest -Uri $Url -NoProxy -TimeoutSec 3; if($r.StatusCode -eq 200){return}}catch{}
        Start-Sleep -Milliseconds 500
    }while([DateTime]::UtcNow -lt $until)
    throw 'Monitoring readiness timeout'
}
try{
    Launch 'blackbox' $blackbox @(( '--config.file='+(Join-Path $PSScriptRoot 'blackbox.yml')),'--web.listen-address=127.0.0.1:9115') @{}
    Wait-Ready 'http://127.0.0.1:9115/-/healthy'
    Launch 'prometheus' $prom @(( '--config.file='+(Join-Path $PSScriptRoot 'prometheus.yml')),'--web.listen-address=127.0.0.1:9090',('--storage.tsdb.path='+(Join-Path $RuntimeRoot 'prometheus-data')),'--storage.tsdb.retention.time=15d','--storage.tsdb.retention.size=256MB') @{}
    Wait-Ready 'http://127.0.0.1:9090/-/ready'
    $vars=@{
        GF_PATHS_HOME=$GrafanaHome
        GF_PATHS_CONFIG=(Join-Path $PSScriptRoot 'grafana.ini')
        GF_PATHS_DATA=(Join-Path $RuntimeRoot 'grafana-data')
        GF_PATHS_LOGS=(Join-Path $RuntimeRoot 'grafana-logs')
        GF_PATHS_PLUGINS=(Join-Path $RuntimeRoot 'grafana-plugins')
        GF_PATHS_PROVISIONING=(Join-Path $PSScriptRoot 'provisioning')
        GF_SECURITY_ADMIN_PASSWORD=('$__file{'+$passwordFile+'}')
        BAOTIN_MONITORING_DASHBOARDS=(Join-Path $PSScriptRoot 'dashboards')
    }
    # Grafana's file provider uses $__file{...}; no password enters arguments/logs.
    Launch 'grafana' $grafana @('server','--homepath',$GrafanaHome,'--config',(Join-Path $PSScriptRoot 'grafana.ini')) $vars
    Wait-Ready 'http://127.0.0.1:3000/api/health'
    'READY Prometheus=127.0.0.1:9090 Grafana=127.0.0.1:3000 Blackbox=127.0.0.1:9115'
}catch{
    if($state.processes.Count){& (Join-Path $PSScriptRoot 'Stop-Monitoring.ps1') -RuntimeRoot $RuntimeRoot}
    throw
}
