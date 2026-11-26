[CmdletBinding()]
param([Parameter(Mandatory)][string]$RuntimeRoot)
$ErrorActionPreference='Stop'
$state=Get-Content -LiteralPath (Join-Path $RuntimeRoot 'active.json') -Raw|ConvertFrom-Json
if($state.processes.Count -ne 3){throw 'Expected three monitoring processes'}
foreach($entry in $state.processes){
    $p=Get-Process -Id $entry.pid
    if($p.StartTime.ToUniversalTime().Ticks -ne [long]$entry.startTicks -or $p.Path -ne $entry.path){throw 'Process ownership mismatch'}
}
$listeners=@(Get-NetTCPConnection -State Listen|Where-Object LocalPort -in 3000,9090,9115)
if($listeners.Count -ne 3 -or @($listeners|Where-Object LocalAddress -ne '127.0.0.1').Count){throw 'Expected only three loopback monitoring listeners'}
$until=[DateTime]::UtcNow.AddSeconds(65)
do{
    $targets=Invoke-RestMethod http://127.0.0.1:9090/api/v1/targets -NoProxy
    if($targets.data.activeTargets.Count -eq 14 -and @($targets.data.activeTargets|Where-Object health -ne 'up').Count -eq 0){break}
    Start-Sleep -Seconds 2
}while([DateTime]::UtcNow -lt $until)
if($targets.data.activeTargets.Count -ne 14 -or @($targets.data.activeTargets|Where-Object health -ne 'up').Count){throw 'Expected fourteen healthy scrape targets'}
$q=[uri]::EscapeDataString('probe_success{job=~"service-readiness|website-https"}')
$probes=(Invoke-RestMethod "http://127.0.0.1:9090/api/v1/query?query=$q" -NoProxy).data.result
if($probes.Count -ne 12 -or @($probes|Where-Object {$_.value[1] -ne '1'}).Count){throw 'Expected twelve successful application/website probes'}
$expectedProbes=@{
    'api-gateway'='http://127.0.0.1:8090/actuator/health/readiness'
    'auth-service'='http://127.0.0.1:8181/actuator/health/readiness'
    'threat-service'='http://127.0.0.1:8182/actuator/health/readiness'
    'report-service'='http://127.0.0.1:8183/actuator/health/readiness'
    'reputation-service'='http://127.0.0.1:8184/actuator/health/readiness'
    'model-inference-service'='http://127.0.0.1:8185/actuator/health/readiness'
    'notification-service'='http://127.0.0.1:8186/actuator/health/readiness'
    'search-service'='http://127.0.0.1:8187/actuator/health/readiness'
    'campaign-service'='http://127.0.0.1:8188/actuator/health/readiness'
    'analytics-service'='http://127.0.0.1:8189/actuator/health/readiness'
    'expo-web'='http://127.0.0.1:8091/healthz'
    'public-website'='https://baotin.rimiprod.me/'
}
foreach($name in $expectedProbes.Keys){
    $matches=@($probes|Where-Object {$_.metric.service -eq $name -and $_.metric.instance -eq $expectedProbes[$name]})
    if($matches.Count -ne 1){throw "Missing or duplicate probe identity: $name"}
    $target=@($targets.data.activeTargets|Where-Object {$_.labels.service -eq $name -and $_.labels.instance -eq $expectedProbes[$name]})
    if($target.Count -ne 1 -or [DateTime]::UtcNow.Subtract(([DateTime]$target[0].lastScrape).ToUniversalTime()).TotalSeconds -gt 90){throw 'Probe scrape is missing or stale'}
}
$password=[IO.File]::ReadAllText((Join-Path $RuntimeRoot 'grafana-admin.secret')).Trim()
$auth=@{Authorization='Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('baotin-admin:'+$password))}
$dash=Invoke-RestMethod http://127.0.0.1:3000/api/dashboards/uid/baotin-overview -Headers $auth -NoProxy
if($dash.dashboard.panels.Count -ne 9){throw 'Dashboard provisioning failed'}
$datasource=Invoke-RestMethod http://127.0.0.1:3000/api/datasources/uid/baotin-prometheus/health -Headers $auth -NoProxy
if($datasource.status -ne 'OK'){throw 'Grafana datasource query failed'}
foreach($panel in $dash.dashboard.panels){
    foreach($query in $panel.targets){
        $expression=$query.expr.Replace('$service','.*').Replace('$__range','15m')
        $uri='http://127.0.0.1:3000/api/datasources/proxy/uid/baotin-prometheus/api/v1/query?query='+[uri]::EscapeDataString($expression)
        $result=Invoke-RestMethod $uri -Headers $auth -NoProxy
        if($result.status -ne 'success'){throw 'Dashboard panel query failed through Grafana'}
        if($panel.title -ne 'Active monitoring alerts' -and $result.data.result.Count -eq 0){throw 'Dashboard panel has no data'}
    }
}
$rules=(Invoke-RestMethod http://127.0.0.1:9090/api/v1/rules -NoProxy).data.groups.rules
$expectedRules=@('BaoTinServiceNotReady','BaoTinWebsiteUnavailable','BaoTinMonitoringTargetDown','BaoTinTlsCertificateExpiring','BaoTinSlowProbe')
foreach($name in $expectedRules){
    $rule=@($rules|Where-Object name -eq $name)
    if($rule.Count -ne 1 -or $rule[0].health -ne 'ok'){throw 'Missing or unhealthy monitoring alert rule'}
}
$grafana= $state.processes | Where-Object name -eq 'grafana'
$grafanaRoot=[IO.Path]::GetFullPath((Split-Path (Split-Path $grafana.path -Parent) -Parent))+[IO.Path]::DirectorySeparatorChar
$grafanaProcesses=@(Get-Process | Where-Object {$_.Path -and $_.Path.StartsWith($grafanaRoot,[StringComparison]::OrdinalIgnoreCase)})
if(@($grafanaProcesses|Where-Object MainWindowHandle -ne 0).Count){throw 'Visible Grafana/plugin console detected'}
if(@($grafanaProcesses|Where-Object ProcessName -eq 'grafana').Count -ne 1 -or @($grafanaProcesses|Where-Object ProcessName -like 'gpx_*').Count -ne 1){throw 'Expected one Grafana and only its Prometheus plugin'}
foreach($url in @('http://127.0.0.1:3000/api/dashboards/uid/baotin-overview','http://127.0.0.1:9090/api/v1/admin/tsdb/delete_series?match[]=up')){
    $r=Invoke-WebRequest $url -NoProxy -SkipHttpErrorCheck
    if($r.StatusCode -notin @(401,403,404,405)){throw 'Negative access check failed'}
}
$public=Invoke-WebRequest https://baotin.rimiprod.me/actuator/prometheus -NoProxy -SkipHttpErrorCheck
if($public.StatusCode -ne 404){throw 'Public actuator must remain blocked'}
"PASS ownedProcesses=3 loopbackListeners=3 scrapeTargets=14 probes=12 dashboardPanels=9 datasource=OK negativeAccess=PASS publicActuator=404 grafanaServers=1 pluginProcesses=1 visibleWindows=0"
