#Requires -Version 7.4
[CmdletBinding()]
param([string]$ConfigPath=(Join-Path $PSScriptRoot 'nginx.conf'))
. (Join-Path $PSScriptRoot 'Team.Common.ps1')
$config=(Resolve-Path -LiteralPath $ConfigPath).Path
$name='baotin-nginx-privacy-'+[guid]::NewGuid().ToString('N').Substring(0,8)
$fixture=Join-Path $env:TEMP ($name+'.conf')
# Keep the config under test; replace only its upstream with an unused fixture port.
[IO.File]::WriteAllText($fixture,[IO.File]::ReadAllText($config).Replace('server api-gateway:8080;','server 127.0.0.1:65534;'))
try{
    Invoke-TeamDocker -Arguments @('run','-d','--name',$name,'-p','127.0.0.1::8080','--mount',"type=bind,source=$fixture,target=/etc/nginx/conf.d/default.conf,readonly",'nginxinc/nginx-unprivileged:1.28-alpine')|Out-Null
    $port=(Invoke-TeamDocker -Arguments @('port',$name,'8080/tcp')).Trim().Split(':')[-1]
    $marker='synthetic-sensitive-'+[guid]::NewGuid().ToString('N');$response=$null
    for($i=0;$i -lt 30;$i++){
        try{$response=Invoke-WebRequest "http://127.0.0.1:$port/api/v1/auth/oauth2/callback/google?code=$marker" -Method Post -ContentType application/json -Body ('x'*70000) -SkipHttpErrorCheck -TimeoutSec 3;break}catch{Start-Sleep -Milliseconds 250}
    }
    if(-not $response -or [int]$response.StatusCode -ne 413){throw 'Ingress size limit failed'}
    # An upstream-connect error must not leak request query either.
    $upstream=Invoke-WebRequest "http://127.0.0.1:$port/api/v1/auth/oauth2/callback/google?code=$marker" -SkipHttpErrorCheck -TimeoutSec 3
    if((Invoke-TeamDocker -Arguments @('logs',$name)).Contains($marker)){throw 'Nginx request error leaked synthetic OAuth code'}
    if(($response.Headers['Content-Type'] -join '') -notlike 'application/problem+json*'){throw 'Ingress size error must use Problem Details'}
    $content=if($response.Content -is [byte[]]){[Text.Encoding]::UTF8.GetString($response.Content)}else{$response.Content}
    $problem=$content|ConvertFrom-Json;$uuid=[guid]::Empty
    if(-not [guid]::TryParseExact($problem.correlationId,'D',[ref]$uuid) -or $problem.code -ne 'REQUEST_TOO_LARGE' -or ($response.Headers['X-Correlation-Id'] -join '') -ne $problem.correlationId){throw 'Ingress Problem Details correlation invalid'}
    if([int]$upstream.StatusCode -ne 503 -or ($upstream.Headers['Content-Type'] -join '') -notlike 'application/problem+json*'){throw 'Upstream failure must return 503 Problem Details'}
    $content=if($upstream.Content -is [byte[]]){[Text.Encoding]::UTF8.GetString($upstream.Content)}else{$upstream.Content};$problem=$content|ConvertFrom-Json
    if(-not [guid]::TryParseExact($problem.correlationId,'D',[ref]$uuid) -or $problem.code -ne 'UPSTREAM_UNAVAILABLE' -or ($upstream.Headers['X-Correlation-Id'] -join '') -ne $problem.correlationId -or ($upstream.Headers['Retry-After'] -join '') -ne '5'){throw 'Upstream Problem Details metadata invalid'}
    Write-Output 'NGINX_PRIVACY PASS oversize=413 upstream_error=503 query_in_logs=false problem_json=true correlation=uuid retry_after=5'
}finally{try{Invoke-TeamDocker -Arguments @('rm','-f',$name)|Out-Null}catch{};Remove-Item -LiteralPath $fixture -Force}
