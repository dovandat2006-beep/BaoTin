#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$SpecFile)
# Launched through WMI from the Task Scheduler job. The task's job object forbids
# CREATE_BREAKAWAY_FROM_JOB, so the agent must be started from this job-free process
# to keep hidden runtime children (JARs, static web) alive after a Jenkins build.
$ErrorActionPreference='Stop'
if(& (Join-Path $PSScriptRoot 'Get-ProcessElevation.ps1') -ProcessId $PID){throw 'Detached agent launcher must be non-elevated'}
$file=(Resolve-Path -LiteralPath $SpecFile).Path
$workDir=[IO.Path]::GetFullPath('M:\BaoTin-deploy-agent')
if(-not $file.StartsWith($workDir+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Agent launch spec must live under the approved work directory'}
if((Get-Item -LiteralPath $file).LinkType -or (Get-Item -LiteralPath $file).Length -gt 8192){throw 'Bounded regular launch spec required'}
$spec=[IO.File]::ReadAllText($file)|ConvertFrom-Json -AsHashtable
if($spec.node -ne 'baotin-deploy-desktop' -or [IO.Path]::GetFullPath([string]$spec.workDir) -ne $workDir){throw 'Unexpected agent launch spec'}
$errorFile=[IO.Path]::GetFullPath([string]$spec.errorFile)
if(-not $errorFile.StartsWith($workDir+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected agent launch spec'}
foreach($entry in $spec.environment.GetEnumerator()){[Environment]::SetEnvironmentVariable([string]$entry.Key,[string]$entry.Value,'Process')}
try{
    $arguments=[string[]]@($spec.arguments)
    $process=Start-Process -FilePath ([string]$spec.javaExe) -ArgumentList $arguments -WorkingDirectory $workDir -WindowStyle Hidden -PassThru -RedirectStandardOutput ([string]$spec.outLog) -RedirectStandardError ([string]$spec.errLog)
    [IO.File]::WriteAllText([string]$spec.pidFile,[string]$process.Id)
}catch{
    [IO.File]::WriteAllText($errorFile,([string]$_.Exception.GetType().Name+': agent JVM did not start'))
    throw
}
