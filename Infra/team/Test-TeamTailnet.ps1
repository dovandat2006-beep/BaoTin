#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$EnvFile)
. (Join-Path $PSScriptRoot 'Team.Common.ps1')
$config=Read-TeamConfig $EnvFile;if($config['COMPOSE_PROJECT_NAME'] -ne 'baotin-staging'){throw 'Tailnet regression is isolated staging only'}
$status=tailscale status --json|ConvertFrom-Json;if($status.BackendState -ne 'Running'){throw 'Tailscale must be Running'}
$hostName=$status.Self.DNSName.TrimEnd('.');$authority="${hostName}:9443"
$before=tailscale serve status --json|ConvertFrom-Json -AsHashtable
if(($before.ContainsKey('Web') -and $before['Web'].ContainsKey($authority)) -or ($before.ContainsKey('TCP') -and $before['TCP'].ContainsKey('9443'))){throw 'Tailnet test will not replace an existing 9443 handler'}
$base=@('compose','--env-file',$EnvFile,'-f',(Join-Path $PSScriptRoot 'docker-compose.yml'))
function Count-IP([string]$Ip){
    $hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes("auth-write-ip:$Ip"))).ToLowerInvariant()
    [int](Invoke-TeamDocker -Arguments ($base+@('exec','-T','postgres','psql','-U','postgres','-d','baotin','-At','-c',"SELECT coalesce(sum(attempts),0) FROM auth.rate_limits WHERE bucket_key='$hash'"))).Trim()
}
$added=$false
try{
    # Mark intent before the command so partial success is also cleaned up.
    $added=$true;& (Join-Path $PSScriptRoot 'Enable-TeamTailscale.ps1') -EnvFile $EnvFile
    $url="https://${hostName}:9443"
    $actualIps=@($status.Self.TailscaleIPs|ForEach-Object {
        $bytes=[Net.IPAddress]::Parse($_).GetAddressBytes()
        if($bytes.Length -eq 4){$_}else{(@(for($i=0;$i -lt 16;$i+=2){(([int]$bytes[$i]*256)+[int]$bytes[$i+1]).ToString('x')}) -join ':')}
    }) # Java canonical Inet6Address spelling; independent HTTPS calls may select either family.
    $PSDefaultParameterValues=@{'Invoke-RestMethod:NoProxy'=$true;'Invoke-WebRequest:NoProxy'=$true;'Invoke-RestMethod:TimeoutSec'=10;'Invoke-WebRequest:TimeoutSec'=10}
    $count=0;foreach($ip in $actualIps){$count+=Count-IP $ip};$fakeCount=Count-IP '203.0.113.77'
    $name='tailnet'+[guid]::NewGuid().ToString('N').Substring(0,10);$password='tailnet-synthetic-password-long'
    $headers=@{'X-Forwarded-For'='203.0.113.77';'X-BaoTin-Client-IP'='203.0.113.77';'X-Roles'='ADMIN'}
    $profile=Invoke-RestMethod "$url/api/v1/auth/register" -Method Post -Headers $headers -ContentType application/json -Body (@{username=$name;email="$name@example.test";password=$password;displayName='Tailnet fixture'}|ConvertTo-Json)
    $login=Invoke-RestMethod "$url/api/v1/auth/login" -Method Post -Headers $headers -ContentType application/json -Body (@{identifier=$name;password=$password}|ConvertTo-Json)
    $me=Invoke-RestMethod "$url/api/v1/auth/me" -Headers @{Authorization="Bearer $($login.tokens.accessToken)"}
    if($me.id -ne $profile.id){throw 'Tailnet authenticated profile mismatch'}
    $afterCount=0;foreach($ip in $actualIps){$afterCount+=Count-IP $ip}
    if($afterCount -ne $count+2 -or (Count-IP '203.0.113.77') -ne $fakeCount){
        throw "Live Tailscale canonical IP mismatch: tailnet_delta=$($afterCount-$count) forged_delta=$((Count-IP '203.0.113.77')-$fakeCount)"
    }
    $anon=Invoke-WebRequest "$url/api/v1/auth/admin/users" -Headers $headers -SkipHttpErrorCheck
    if([int]$anon.StatusCode -ne 401){throw 'Tailnet forged ADMIN headers authenticated'}
    $origin="https://${hostName}:9444"
    $web=Invoke-WebRequest "$url/api/v1/auth/web/login" -Method Post -Headers @{Origin=$origin} -ContentType application/json -Body (@{identifier=$name;password=$password}|ConvertTo-Json) -SkipHttpErrorCheck
    if([int]$web.StatusCode -ne 200 -or ($web.Headers['Access-Control-Allow-Origin'] -join '') -ne $origin -or ($web.Headers['Set-Cookie'] -join '') -notmatch '__Host-baotin-refresh=.*;.*Secure;.*HttpOnly'){
        $cookie=$web.Headers['Set-Cookie'] -join '';throw "Tailnet origin/cookie mismatch: status=$($web.StatusCode) origin_headers=$(@($web.Headers['Access-Control-Allow-Origin']).Count) origin_exact=$(($web.Headers['Access-Control-Allow-Origin'] -join '') -eq $origin) refresh_present=$($cookie -match '__Host-baotin-refresh=') secure=$($cookie -match ';\s*Secure') http_only=$($cookie -match ';\s*HttpOnly')"
    }
    $evil=Invoke-WebRequest "$url/api/v1/auth/web/login" -Method Post -Headers @{Origin='https://evil.example'} -ContentType application/json -Body (@{identifier=$name;password=$password}|ConvertTo-Json) -SkipHttpErrorCheck
    if([int]$evil.StatusCode -ne 403){throw 'Tailnet unapproved browser origin accepted'}
    if(@($web.Headers['Access-Control-Allow-Credentials']).Count -ne 1 -or ($web.Headers['Access-Control-Allow-Credentials'] -join '') -ne 'true'){throw 'Credentialed CORS header must be single true'}
    function Web-Body($Response){$text=if($Response.Content -is [byte[]]){[Text.Encoding]::UTF8.GetString($Response.Content)}else{$Response.Content};return ($text|ConvertFrom-Json)}
    function Refresh-Cookie($Response){$value=@($Response.Headers['Set-Cookie']|Where-Object {$_ -like '__Host-baotin-refresh=*'});if($value.Count -ne 1){throw 'Exactly one refresh cookie required'};return $value[0].Split(';')[0]}
    $webBody=Web-Body $web;$cookie=Refresh-Cookie $web
    if($webBody.PSObject.Properties.Name -contains 'refreshToken'){throw 'Web JSON exposed refresh token'}
    $missing=Invoke-WebRequest "$url/api/v1/auth/web/refresh" -Method Post -Headers @{Origin=$origin;Cookie=$cookie} -SkipHttpErrorCheck
    if([int]$missing.StatusCode -ne 403){throw 'HTTPS refresh without CSRF accepted'}
    $rotated=Invoke-WebRequest "$url/api/v1/auth/web/refresh" -Method Post -Headers @{Origin=$origin;Cookie=$cookie;'X-CSRF-TOKEN'=$webBody.csrfToken} -SkipHttpErrorCheck
    if([int]$rotated.StatusCode -ne 200 -or @($rotated.Headers['Access-Control-Allow-Origin']).Count -ne 1){throw 'HTTPS cookie refresh or CORS failed'}
    $next=Web-Body $rotated;$nextCookie=Refresh-Cookie $rotated
    if($next.csrfToken -eq $webBody.csrfToken -or $nextCookie -eq $cookie -or $next.PSObject.Properties.Name -contains 'refreshToken'){throw 'HTTPS refresh did not rotate secret transport'}
    $stale=Invoke-WebRequest "$url/api/v1/auth/web/logout" -Method Post -Headers @{Origin=$origin;Cookie=$nextCookie;'X-CSRF-TOKEN'=$webBody.csrfToken} -SkipHttpErrorCheck
    if([int]$stale.StatusCode -ne 403){throw 'HTTPS logout accepted stale CSRF'}
    $logout=Invoke-WebRequest "$url/api/v1/auth/web/logout" -Method Post -Headers @{Origin=$origin;Cookie=$nextCookie;'X-CSRF-TOKEN'=$next.csrfToken} -SkipHttpErrorCheck
    if([int]$logout.StatusCode -ne 204){throw 'HTTPS cookie logout failed'}
    $revoked=Invoke-WebRequest "$url/api/v1/auth/me" -Headers @{Authorization="Bearer $($next.accessToken)"} -SkipHttpErrorCheck
    if([int]$revoked.StatusCode -ne 401){throw 'HTTPS logged-out token remained usable'}
    Write-Output 'TEAM_TAILNET PASS https=verified host=current peer_ip=canonical spoofed_xff=ignored admin_headers=401 expo_origin=exact cors_headers=single secure_cookie=true bad_origin=403 refresh=rotated csrf=required stale_csrf=403 logout=204 revoked=401 funnel=false'
}finally{
    if($added){& tailscale serve --https=9443 off|Out-Null;if($LASTEXITCODE -ne 0){throw 'Temporary BảoTin Serve handler cleanup failed'}}
    $after=tailscale serve status --json|ConvertFrom-Json -AsHashtable
    foreach($section in @('TCP','Web','AllowFunnel')){
        if($before.ContainsKey($section)){foreach($key in $before[$section].Keys){if(-not $after.ContainsKey($section) -or -not $after[$section].ContainsKey($key) -or ($before[$section][$key]|ConvertTo-Json -Depth 12 -Compress) -ne ($after[$section][$key]|ConvertTo-Json -Depth 12 -Compress)){throw 'Unrelated Tailscale handler changed'}}}
    }
    if(($after.ContainsKey('Web') -and $after['Web'].ContainsKey($authority)) -or ($after.ContainsKey('TCP') -and $after['TCP'].ContainsKey('9443'))){throw 'Temporary handler remains configured'}
    Write-Output 'TEAM_TAILNET_CLEANUP PASS temporary_9443=removed existing_handlers=preserved'
}
