#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$EnvFile)
. (Join-Path $PSScriptRoot 'Team.Common.ps1')
$config=Read-TeamConfig $EnvFile
if($config['COMPOSE_PROJECT_NAME'] -ne 'baotin-staging'){throw 'Ingress regression is isolated staging only'}
$base=@('compose','--env-file',$EnvFile,'-f',(Join-Path $PSScriptRoot 'docker-compose.yml'))
$peer='baotin-ingress-peer-'+[guid]::NewGuid().ToString('N').Substring(0,8)
$prefix='ingress'+[guid]::NewGuid().ToString('N').Substring(0,8)
function Hash-Value([string]$Text){[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Text))).ToLowerInvariant()}
function Auth-Count([string]$Ip){
    $hash=Hash-Value "auth-write-ip:$Ip"
    [int](Invoke-TeamDocker -Arguments ($base+@('exec','-T','postgres','psql','-U','postgres','-d','baotin','-At','-c',"SELECT coalesce(sum(attempts),0) FROM auth.rate_limits WHERE bucket_key='$hash'"))).Trim()
}
function Probe([string]$Target,[string]$Path,[string]$Method='POST',[string]$Body='',[string]$Forwarded='203.0.113.77'){
    $arguments=@('exec',$peer,'curl','-sS','--max-time','5','-D','-','-o','/dev/null','-w',"`nSTATUS=%{http_code}`n",'-X',$Method,
        '-H',"X-Forwarded-For: $Forwarded",'-H','X-BaoTin-Client-IP: 203.0.113.66','-H','X-User-Id: 00000000-0000-0000-0000-000000000001',
        '-H','X-Roles: ADMIN','-H','Tailscale-User-Login: operator@example.test','-H','X-Correlation-Id: 00000000-0000-0000-0000-000000000002')
    if($Body){$arguments+=@('-H','Content-Type: application/json','--data',$Body)}
    $text=Invoke-TeamDocker -Arguments ($arguments+@("http://${Target}:8080$Path"))
    if($text -notmatch 'STATUS=(\d+)'){throw 'Ingress fixture missing response status'};$status=[int]$Matches[1]
    if($text -notmatch '(?im)^X-Correlation-Id:\s*([0-9a-f-]+)\s*$'){throw 'Missing correlation through ingress'};$correlation=$Matches[1]
    $uuid=[guid]::Empty;if(-not [guid]::TryParseExact($correlation,'D',[ref]$uuid) -or $correlation -eq '00000000-0000-0000-0000-000000000002'){throw 'Untrusted correlation propagated'}
    return $status
}
try{
    Invoke-TeamDocker -Arguments @('run','-d','--name',$peer,'--network','baotin-staging_backend','--ip','172.29.47.20','--entrypoint','sleep',
        '--read-only','--tmpfs','/tmp','--cap-drop','ALL','--security-opt','no-new-privileges',$config['AUTH_IMAGE'],'300')|Out-Null
    $before=Auth-Count '172.29.47.20';$fakeBefore=(Auth-Count '203.0.113.77')+(Auth-Count '203.0.113.66')
    $body=@{identifier="$prefix@example.test"}|ConvertTo-Json -Compress
    if((Probe '172.29.47.12' '/api/v1/auth/forgot-password' -Body $body) -ne 202){throw 'Nginx forwarded-chain request failed'}
    if((Probe '172.29.47.10' '/api/v1/auth/forgot-password' -Body $body -Forwarded 'invalid-hostname.example') -ne 202){throw 'Untrusted direct Gateway headers were accepted or rejected as trusted'}
    if((Probe '172.29.47.11' '/api/v1/auth/forgot-password' -Body $body) -ne 202){throw 'Auth boundary metadata test failed'}
    if((Auth-Count '172.29.47.20') -ne $before+3 -or ((Auth-Count '203.0.113.77')+(Auth-Count '203.0.113.66')) -ne $fakeBefore){throw 'Spoofed IP controlled Auth counter'}
    foreach($path in @('/api/v1/auth/me','/api/v1/auth/admin/users','/internal/identity','/api/v1/scans')){
        if((Probe '172.29.47.12' $path -Method GET) -notin @(401,403)){throw 'Forged roles or internal route exposed'}
    }
    $gatewayHash=Hash-Value '172.29.47.20:auth-write'
    $keys=Invoke-TeamDocker -Arguments ($base+@('exec','-T','redis','sh','-c',"export REDISCLI_AUTH=`"`$(cat /run/redis/password)`"; redis-cli --scan --pattern 'gateway:rate:${gatewayHash}:*'"))
    if(-not $keys.Trim()){throw 'Gateway canonical peer bucket missing'}
    $peerInfo=Invoke-TeamDocker -Arguments @('inspect','baotin-staging-api-gateway-1','--format','{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}')
    if($peerInfo.Trim() -ne '172.29.47.10'){throw 'Observed Gateway peer differs from Auth trust'}
    # Local loopback is an operator proxy boundary. Validate its actual bridge chain,
    # without treating this synthetic forwarding test as live Tailscale evidence.
    $hostIp='203.0.113.88';$hostBefore=Auth-Count $hostIp
    $hostResponse=Invoke-WebRequest http://127.0.0.1:8090/api/v1/auth/forgot-password -Method Post -ContentType application/json -Body (@{identifier="$prefix-host@example.test"}|ConvertTo-Json) -Headers @{'X-Forwarded-For'=$hostIp} -SkipHttpErrorCheck
    if([int]$hostResponse.StatusCode -ne 202 -or (Auth-Count $hostIp) -ne $hostBefore+1){throw 'Actual host bridge/header chain differs from reviewed trust configuration'}
    Write-Output 'TEAM_INGRESS PASS nginx_gateway_auth=true untrusted_xff=ignored forged_ip=ignored forged_admin=401 internal=denied auth_counter=canonical redis_counter=canonical correlation=uuid host_bridge=verified tailscale_live=false'
}finally{try{Invoke-TeamDocker -Arguments @('rm','-f',$peer)|Out-Null}catch{}}
