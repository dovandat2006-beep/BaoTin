[CmdletBinding(SupportsShouldProcess)]
param([Parameter(Mandatory)][string]$RuntimeRoot)
$ErrorActionPreference='Stop'
$statePath=Join-Path $RuntimeRoot 'active.json'
if(-not (Test-Path -LiteralPath $statePath)){throw 'No monitoring ownership record'}
$state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
# Preflight every entry before signalling any process. Never infer ownership from a port/name.
$owned=@()
foreach($entry in $state.processes){
    $p=Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
    if(-not $p){continue}
    if($p.StartTime.ToUniversalTime().Ticks -ne [long]$entry.startTicks -or $p.Path -ne $entry.path){
        throw 'Monitoring PID identity mismatch; no processes stopped'
    }
    $owned+=@{process=$p; entry=$entry}
}
# Capture verified plugin children before the Grafana parent exits. Preserve all
# unrelated processes, including any process that reuses a recorded PID later.
$plugins=@()
foreach($item in @($owned|Where-Object {$_.entry.name -eq 'grafana'})){
    $pluginRoot=Join-Path (Split-Path (Split-Path $item.entry.path -Parent) -Parent) 'data/plugins-bundled'
    $pluginRoot=[IO.Path]::GetFullPath($pluginRoot)+[IO.Path]::DirectorySeparatorChar
    foreach($child in @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$($item.entry.pid)")){
        if($child.ExecutablePath -and $child.ExecutablePath.StartsWith($pluginRoot,[StringComparison]::OrdinalIgnoreCase) -and $child.Name -like 'gpx_*'){
            $p=Get-Process -Id $child.ProcessId -ErrorAction SilentlyContinue
            if($p -and $p.Path -eq $child.ExecutablePath -and $p.StartTime.ToUniversalTime().Ticks -ge [long]$item.entry.startTicks){
                $plugins+=@{pid=$p.Id;path=$p.Path;startTicks=$p.StartTime.ToUniversalTime().Ticks}
            }
        }
    }
}
foreach($item in $owned){
    if($PSCmdlet.ShouldProcess($item.entry.name,'Stop owned monitoring process')){
        $p=Get-Process -Id $item.entry.pid -ErrorAction SilentlyContinue
        if($p -and $p.StartTime.ToUniversalTime().Ticks -eq [long]$item.entry.startTicks -and $p.Path -eq $item.entry.path){
            Stop-Process -InputObject $p
            $p.WaitForExit(10000) | Out-Null
        }
    }
}
foreach($entry in $plugins){
    if($PSCmdlet.ShouldProcess($entry.path,'Stop verified Grafana plugin child')){
        $p=Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
        if($p -and $p.Path -eq $entry.path -and $p.StartTime.ToUniversalTime().Ticks -eq [long]$entry.startTicks){
            Stop-Process -InputObject $p
            $p.WaitForExit(10000)|Out-Null
        }
    }
}
if(-not $WhatIfPreference){
    $state.stoppedAt=[DateTime]::UtcNow.ToString('o')
    $state|ConvertTo-Json -Depth 8|Set-Content -LiteralPath $statePath -Encoding utf8
    'STOPPED owned monitoring only; configs, TSDB, Grafana data and applications preserved'
}
