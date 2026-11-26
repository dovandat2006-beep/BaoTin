#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$JavaHome)
$ErrorActionPreference='Stop'
$root=Join-Path $env:TEMP ('baotin-agent-rollback-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$acl=[Security.AccessControl.DirectorySecurity]::new();$acl.SetAccessRuleProtection($true,$false)
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
Set-Acl -LiteralPath $root -AclObject $acl
$process=$null;$startTicks=$null
try {
    $source=Join-Path $root 'RollbackSleeper.java'
    [IO.File]::WriteAllText($source,'class RollbackSleeper { public static void main(String[] args) throws Exception { Thread.sleep(60000); } }')
    $process=Start-Process -FilePath (Join-Path $JavaHome 'bin/java.exe') -ArgumentList ('"'+$source+'"') -WindowStyle Hidden -PassThru
    $startTicks=$process.StartTime.ToUniversalTime().Ticks
    $state=@{node='baotin-deploy-desktop';pid=$process.Id;startTicks=$startTicks;workDir='M:\BaoTin-deploy-agent';agentJarSha256='a'*64}
    $file=Join-Path $root 'copy-state.json'
    $state.startTicks=$startTicks+1
    [IO.File]::WriteAllText($file,($state|ConvertTo-Json))
    $failed=$false
    try{& (Join-Path $PSScriptRoot 'Stop-OwnedDeployAgent.ps1') -StateFile $file}catch{if($_.Exception.Message -ne 'PID ownership changed; no process stopped'){throw};$failed=$true}
    if(-not $failed -or -not(Get-Process -Id $process.Id -ErrorAction SilentlyContinue)){throw 'Mismatched process ownership not preserved'}
    $state.startTicks=$startTicks;[IO.File]::WriteAllText($file,($state|ConvertTo-Json))
    & (Join-Path $PSScriptRoot 'Stop-OwnedDeployAgent.ps1') -StateFile $file
    if(Get-Process -Id $process.Id -ErrorAction SilentlyContinue){throw 'Owned fixture process still running'}
    & (Join-Path $PSScriptRoot 'Stop-OwnedDeployAgent.ps1') -StateFile $file
    'AGENT_ROLLBACK_TEST PASS wrong_start=denied owned_copy=stopped repeat=already_stopped live_agent=untouched'
} finally {
    if($process -and $startTicks){$p=Get-Process -Id $process.Id -ErrorAction SilentlyContinue;if($p -and $p.StartTime.ToUniversalTime().Ticks -eq $startTicks){Stop-Process -Id $p.Id -Force}}
    $resolved=[IO.Path]::GetFullPath($root)
    if(-not $resolved.StartsWith([IO.Path]::GetFullPath($env:TEMP).TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^baotin-agent-rollback-[a-f0-9]{32}$'){throw 'Unexpected rollback fixture cleanup path'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
