[CmdletBinding()]
param([string]$PromtoolPath)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath($PSScriptRoot)+[IO.Path]::DirectorySeparatorChar
$count=0
foreach($line in (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'SHA256SUMS.txt'))){
    if($line -notmatch '^([0-9a-f]{64})  (.+)$'){throw 'Invalid checksum entry'}
    $hash=$Matches[1]
    $relative=$Matches[2]
    $path=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot $relative))
    if(-not $path.StartsWith($root,[StringComparison]::OrdinalIgnoreCase)){throw 'Checksum path escapes package'}
    if((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash){throw "Checksum mismatch: $relative"}
    $count++
}
$dashboard=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'BaoTin-Operations-Grafana.json') -Raw|ConvertFrom-Json
if($dashboard.uid -ne 'baotin-operations' -or -not $dashboard.editable){throw 'Unexpected dashboard identity or editing mode'}
if($dashboard.__inputs[0].name -ne 'DS_PROMETHEUS'){throw 'Missing datasource selection'}
$panels=@($dashboard.panels|Where-Object type -ne 'row')
if($panels.Count -ne 12){throw 'Expected12 data panels'}
foreach($panel in $panels){
    if($panel.datasource.uid -ne '${DS_PROMETHEUS}'){throw 'Datasource is not selectable at import'}
    if($panel.fieldConfig.defaults.noValue -ne 'NO DATA'){throw 'Missing data must not appear healthy'}
}
"PASS checksums=$count importDashboard=VALID panels=12 selectableDatasource=YES"
if($PromtoolPath){
    & $PromtoolPath check config (Join-Path $PSScriptRoot 'prometheus/prometheus.yml')
    if($LASTEXITCODE){throw 'Prometheus config validation failed'}
    & $PromtoolPath test rules (Join-Path $PSScriptRoot 'prometheus/alerts.test.yml')
    if($LASTEXITCODE){throw 'Alert rule tests failed'}
    'PASS PrometheusConfig=VALID alertRules=6'
}
