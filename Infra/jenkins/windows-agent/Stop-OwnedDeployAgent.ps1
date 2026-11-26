#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$StateFile)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$file=(Resolve-Path -LiteralPath $StateFile).Path
if($file.StartsWith($workspace+'\',[StringComparison]::OrdinalIgnoreCase) -or (Get-Item -LiteralPath $file).LinkType -or (Get-Item -LiteralPath $file).Length -gt 4096){throw 'Bounded regular external ownership record required'}
$allowed=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')
foreach($rule in (Get-Acl -LiteralPath $file).Access){
    if($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin $allowed){throw 'Ownership record ACL must be restricted'}
}
$state=[IO.File]::ReadAllText($file)|ConvertFrom-Json -AsHashtable
if($state.node -ne 'baotin-deploy-desktop' -or $state.workDir -ne 'M:\BaoTin-deploy-agent' -or
   ($state.pid -isnot [int] -and $state.pid -isnot [long]) -or $state.pid -le 0 -or $state.pid -gt [int]::MaxValue -or $state.startTicks -isnot [long] -or
   $state.agentJarSha256 -notmatch '^[a-f0-9]{64}$'){throw 'Invalid agent ownership metadata'}
$process=Get-Process -Id $state.pid -ErrorAction SilentlyContinue
if(-not $process){'DEPLOY_AGENT_ROLLBACK PASS owned_process=already_stopped controller=unchanged';return}
if($process.ProcessName -ne 'java' -or $process.StartTime.ToUniversalTime().Ticks -ne $state.startTicks){throw 'PID ownership changed; no process stopped'}
Stop-Process -Id $process.Id -Force
Wait-Process -Id $process.Id -Timeout 10 -ErrorAction SilentlyContinue
'DEPLOY_AGENT_ROLLBACK PASS owned_process=stopped controller=unchanged'
