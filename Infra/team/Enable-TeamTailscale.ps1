#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$EnvFile)
. (Join-Path $PSScriptRoot 'Team.Common.ps1')
& (Join-Path $PSScriptRoot 'Test-TeamPreflight.ps1') -EnvFile $EnvFile -RequireTailscale
$before=tailscale serve status --json|ConvertFrom-Json -AsHashtable
# Preflight proves this port is unused or already points at BảoTin; preserve every other handler.
& tailscale serve --bg --https=9443 http://127.0.0.1:8090
if($LASTEXITCODE -ne 0){throw 'Tailscale Serve could not enable the isolated BảoTin handler'}
$status=tailscale status --json|ConvertFrom-Json;$hostName=$status.Self.DNSName.TrimEnd('.')
$after=tailscale serve status --json|ConvertFrom-Json -AsHashtable
$config=Read-TeamConfig $EnvFile;Assert-TeamTailnet $config['AUTH_ALLOWED_ORIGINS'] $hostName $after
foreach($section in @('TCP','Web','AllowFunnel')){
    if($before.ContainsKey($section)){
        foreach($key in $before[$section].Keys){
            if($key -eq '9443' -or $key -eq "${hostName}:9443"){continue}
            if(-not $after.ContainsKey($section) -or -not $after[$section].ContainsKey($key) -or ($before[$section][$key]|ConvertTo-Json -Depth 12 -Compress) -ne ($after[$section][$key]|ConvertTo-Json -Depth 12 -Compress)){throw 'Unrelated Serve handler changed; stop and inspect before further deployment'}
        }
    }
}
$deadline=[DateTime]::UtcNow.AddSeconds(120);$health=$null
do{
    try{$health=Invoke-RestMethod "https://${hostName}:9443/actuator/health" -NoProxy -TimeoutSec 5;if($health.status -eq 'UP'){break}}catch{}
    Start-Sleep -Seconds 2
}while([DateTime]::UtcNow -lt $deadline)
if(-not $health -or $health.status -ne 'UP'){throw 'Tailnet HTTPS health did not verify after bounded certificate/startup wait'}
Write-Output "TEAM_TAILSCALE PASS https://${hostName}:9443 tailnet-only"
