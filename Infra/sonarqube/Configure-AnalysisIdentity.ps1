#Requires -Version 7.4
[CmdletBinding()]
param([string]$SecretRoot=(Join-Path $env:USERPROFILE '.baotin'),[string]$SonarUrl='http://127.0.0.1:9001')
$ErrorActionPreference='Stop'
$uri=[uri]$SonarUrl
if($uri.Scheme -ne 'http' -or $uri.DnsSafeHost -notin '127.0.0.1','localhost','::1' -or $uri.Port -notin 9000,9001 -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/'){throw 'Local Sonar endpoint required'}
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$SecretRoot=[IO.Path]::GetFullPath($SecretRoot)
if($SecretRoot.StartsWith($workspace+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'Secret files must remain outside Git workspace'}
$probe=Invoke-RestMethod ($SonarUrl+'/api/system/status') -NoProxy -MaximumRedirection 0 -TimeoutSec 10
if($probe.status -ne 'UP'){throw 'Sonar endpoint not ready; no credentials read'}
$operatorToken=Join-Path $SecretRoot 'sonarqube-gate-admin.token';$ciFile=Join-Path $SecretRoot 'sonarqube-ci.token'
function Save-Secret($file,$value){
 [IO.File]::WriteAllText($file,$value)
 $acl=[Security.AccessControl.FileSecurity]::new();$acl.SetAccessRuleProtection($true,$false)
 foreach($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')){$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid),'FullControl','Allow'))}
 Set-Acl -LiteralPath $file -AclObject $acl
}
if(-not(Test-Path $operatorToken)){Save-Secret $operatorToken ([IO.File]::ReadAllText($ciFile).Trim())}
$admin=[IO.File]::ReadAllText($operatorToken).Trim();$auth=@{Authorization=('Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($admin+':')))};$admin=$null
function Api($route,$body=$null){
 $args=@{Uri=$SonarUrl+$route;Headers=$auth;NoProxy=$true;MaximumRedirection=0;TimeoutSec=20}
 if($null -ne $body){$args.Method='POST';$args.Body=$body}
 try{return Invoke-RestMethod @args}catch{throw 'Sonar identity configuration failed; diagnostics withheld'}
}
$login='baotin-ci-analysis';$passwordFile=Join-Path $SecretRoot 'sonarqube-analysis-user.password'
$users=Api ('/api/users/search?q='+$login)
$found=@($users.users|Where-Object login -eq $login)
if($found.Count -eq 0){
 $password=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
 $null=Api '/api/users/create' @{login=$login;name='BaoTin CI analysis only';password=$password;local='true'}
 Save-Secret $passwordFile $password;$password=$null
}elseif(-not(Test-Path $passwordFile)){throw 'Existing analysis account preserved; configure its external credential explicitly'}
foreach($repo in 'auth-service','api-gateway','analytics-service','campaign-service','model-inference-service','notification-service','report-service','reputation-service','search-service','threat-service','frontend'){
 foreach($permission in 'scan','user'){$null=Api '/api/permissions/add_user' @{login=$login;permission=$permission;projectKey='baotin_'+$repo}}
}
$password=[IO.File]::ReadAllText($passwordFile).Trim();$ciAuth=@{Authorization=('Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($login+':'+$password)))};$password=$null
$token=Invoke-RestMethod ($SonarUrl+'/api/user_tokens/generate') -Method POST -Body @{name=('baotin-jenkins-'+[guid]::NewGuid().ToString('N'));type='USER_TOKEN'} -Headers $ciAuth -NoProxy -MaximumRedirection 0 -TimeoutSec 20
$candidate=$token.token
$generatedName=$token.name
$candidateAuth=@{Authorization=('Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($candidate+':')))}
$deny=Invoke-WebRequest ($SonarUrl+'/api/qualitygates/create') -Method POST -Body @{name='baotin-ci-mutation-denial'} -Headers $candidateAuth -NoProxy -MaximumRedirection 0 -SkipHttpErrorCheck -TimeoutSec 20
if($deny.StatusCode -ne 403){throw 'Analysis identity must not administer quality gates'}
Save-Secret $ciFile $candidate
# Keep the canonical ignored operator properties and persisted Jenkins config in sync.
$properties=Join-Path $PSScriptRoot '../jenkins/init/baotin-ci.properties'
$lines=[IO.File]::ReadAllLines($properties)
$tokenIndexes=@(for($i=0;$i -lt $lines.Length;$i++){if($lines[$i] -match '^\s*SONAR_TOKEN\s*='){$i}})
if($tokenIndexes.Count -ne 1){throw 'Exactly one canonical SONAR_TOKEN property required'}
$lines[$tokenIndexes[0]]='SONAR_TOKEN='+$candidate
[IO.File]::WriteAllLines($properties,$lines)
& docker cp $properties jenkins-blueocean:/var/jenkins_home/baotin-ci.properties
if($LASTEXITCODE){throw 'Persisted Jenkins properties update failed'}
& docker exec -u root jenkins-blueocean sh -c 'chown jenkins:jenkins /var/jenkins_home/baotin-ci.properties && chmod 600 /var/jenkins_home/baotin-ci.properties'
if($LASTEXITCODE){throw 'Persisted properties ACL failed'}
. (Join-Path $PSScriptRoot '../jenkins/Test-JenkinsPipelines.ps1') -Username rimiprod -TokenFile (Join-Path $SecretRoot 'jenkins-api.token') -PipelineFiles @((Join-Path $PSScriptRoot '../Jenkinsfile'))
$code=@'
def store=com.cloudbees.plugins.credentials.SystemCredentialsProvider.getInstance().getStore()
def domain=com.cloudbees.plugins.credentials.domains.Domain.global()
def old=store.getCredentials(domain).find{it.id=='sonar-token'}
if(old==null)throw new IllegalStateException('Existing Sonar credential required')
def next=new org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl(com.cloudbees.plugins.credentials.CredentialsScope.GLOBAL,'sonar-token',old.description,hudson.util.Secret.fromString('__TOKEN__'))
if(!store.updateCredentials(domain,old,next))throw new IllegalStateException('Credential update failed')
store.save()
println 'SONAR_CI_IDENTITY PASS analysis_account=dedicated gate_admin=403 credential=updated controller_restart=false'
return null
'@
$r=Request 'scriptText' 'POST' @{script=$code.Replace('__TOKEN__',$candidate)} $headers
if($r.StatusCode -ne 200 -or [string]$r.Content -notmatch 'SONAR_CI_IDENTITY PASS'){throw 'Jenkins credential update failed; inspect identity state without logging token'}
foreach($oldToken in (Invoke-RestMethod ($SonarUrl+'/api/user_tokens/search') -Headers $ciAuth -NoProxy -MaximumRedirection 0 -TimeoutSec 20).userTokens){
    if($oldToken.name.StartsWith('baotin-jenkins-') -and $oldToken.name -ne $generatedName){
        $null=Invoke-RestMethod ($SonarUrl+'/api/user_tokens/revoke') -Method POST -Body @{name=$oldToken.name} -Headers $ciAuth -NoProxy -MaximumRedirection 0 -TimeoutSec 20
    }
}
$candidate=$null;$token=$null;$auth.Clear();$ciAuth.Clear();$candidateAuth.Clear()
([string]$r.Content).Trim()
