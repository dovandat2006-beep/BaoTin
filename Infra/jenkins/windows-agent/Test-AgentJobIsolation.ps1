#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$StateFile)
$ErrorActionPreference='Stop'
$state=Get-Content -LiteralPath $StateFile -Raw|ConvertFrom-Json
if($state.node -ne 'baotin-deploy-desktop' -or $state.workDir -ne 'M:\BaoTin-deploy-agent'){
    throw 'Unexpected agent ownership record'
}
$process=Get-Process -Id $state.pid -ErrorAction Stop
if($process.ProcessName -ne 'java' -or $process.StartTime.ToUniversalTime().Ticks -ne $state.startTicks){
    throw 'Agent ownership mismatch'
}
if(& (Join-Path $PSScriptRoot 'Get-ProcessElevation.ps1') -ProcessId $process.Id){
    throw 'Agent must not be elevated'
}
if(-not ('BaoTinAgentJobIsolation' -as [type])){
    Add-Type @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class BaoTinAgentJobIsolation {
  [DllImport("kernel32.dll", SetLastError=true)]
  static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool result);
  public static bool InJob(IntPtr process) {
    bool result;
    if (!IsProcessInJob(process, IntPtr.Zero, out result))
      throw new Win32Exception(Marshal.GetLastWin32Error());
    return result;
  }
}
'@
}
if([BaoTinAgentJobIsolation]::InJob($process.Handle)){
    throw 'Agent is still inside a job object; do not promote'
}
'AGENT_JOB_ISOLATION PASS ownership=matched elevated=false inJob=false'
