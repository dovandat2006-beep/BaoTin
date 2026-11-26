#Requires -Version 7.4
# Operator bootstrap helper. Only the selected cluster's PUBLIC CA field is
# queried with --raw; full admin kubeconfig/private keys are never emitted/copied.
[CmdletBinding()]
param([Parameter(Mandatory)][string]$AdminKubeConfig,[Parameter(Mandatory)][string]$Context,
      [Parameter(Mandatory)][string]$Namespace,[Parameter(Mandatory)][string]$OutputFile)
$ErrorActionPreference='Stop'
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if($Namespace -notmatch '^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$' -or $Namespace -eq 'default' -or $Namespace.StartsWith('kube-') -or
   $Context -notmatch '^[A-Za-z0-9][A-Za-z0-9_.:/@+-]{0,255}$'){throw 'Explicit dedicated namespace/context required'}
$admin=Get-Item -LiteralPath $AdminKubeConfig
$output=[IO.Path]::GetFullPath($OutputFile)
if($admin.LinkType -or $admin.PSIsContainer -or $admin.Length -gt 1048576 -or
   $admin.FullName.StartsWith($workspace+'\',[StringComparison]::OrdinalIgnoreCase) -or
   $output.StartsWith($workspace+'\',[StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $output)){
    throw 'Regular external admin kubeconfig and new external output file required'
}
if(-not(Test-Path -LiteralPath (Split-Path $output) -PathType Container) -or (Get-Item -LiteralPath (Split-Path $output)).LinkType){throw 'Existing regular private output directory required'}
$prefix=@('--kubeconfig',$admin.FullName,'--context',$Context,'--namespace',$Namespace,'--request-timeout=30s')
function Metadata([string[]]$Arguments){
    $value=& kubectl @prefix @Arguments 2>$null
    if($LASTEXITCODE){throw 'Operator cluster metadata/token request failed; output withheld'}
    return (($value|ForEach-Object {[string]$_}) -join "`n").Trim()
}
$server=Metadata @('config','view','--minify','-o','jsonpath={.clusters[0].cluster.server}')
$skip=Metadata @('config','view','--minify','-o','jsonpath={.clusters[0].cluster.insecure-skip-tls-verify}')
if($server -notmatch '^https://[^\s/?#@]+$' -or $skip -eq 'true'){throw 'Verified HTTPS API endpoint required'}
$ca=Metadata @('config','view','--minify','--raw','-o','jsonpath={.clusters[0].cluster.certificate-authority-data}')
if($ca -notmatch '^[A-Za-z0-9+/=]+$' -or [Text.Encoding]::ASCII.GetString([Convert]::FromBase64String($ca)) -notlike '*BEGIN CERTIFICATE*'){
    throw 'Selected inline public CA required; do not disable TLS verification'
}
$ns=Metadata @('get','namespace',$Namespace,'-o','json')|ConvertFrom-Json
if($ns.metadata.labels.'baotin.io/managed' -ne 'true' -or $ns.metadata.labels.'pod-security.kubernetes.io/enforce' -ne 'restricted'){throw 'Bootstrap the dedicated restricted namespace first'}
$token=Metadata @('create','token','baotin-deployer','--duration=2h')
if($token -notmatch '^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$'){throw 'Bounded deployer token response invalid'}
$config=@{apiVersion='v1';kind='Config';clusters=@(@{name='baotin-target';cluster=@{server=$server;'certificate-authority-data'=$ca}});
    users=@(@{name='baotin-deployer';user=@{token=$token}});contexts=@(@{name=$Context;context=@{cluster='baotin-target';user='baotin-deployer';namespace=$Namespace}});'current-context'=$Context}
$handle=[IO.File]::Open($output,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None);$handle.Dispose()
$acl=[Security.AccessControl.FileSecurity]::new();$acl.SetAccessRuleProtection($true,$false)
foreach($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')){
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid),'FullControl','Allow'))
}
Set-Acl -LiteralPath $output -AclObject $acl
[IO.File]::WriteAllText($output,($config|ConvertTo-Json -Depth 9),[Text.UTF8Encoding]::new($false))
$token=$null;$config=$null
'SCOPED_KUBECONFIG PASS namespace='+$Namespace+' namespace_uid='+$ns.metadata.uid+' expiry=2h global_context_changed=false credentials_logged=false'
