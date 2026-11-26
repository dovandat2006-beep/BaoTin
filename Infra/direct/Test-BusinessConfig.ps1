#Requires -Version 7.4
$ErrorActionPreference='Stop'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Deploy-Jars.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Deployment source must parse'}
$definition=$ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Assert-BusinessRuntimeConfig'},$true)
if(!$definition){throw 'Missing business runtime identity preflight'}
. ([scriptblock]::Create($definition.Extent.Text))
$root=Join-Path ([IO.Path]::GetTempPath()) ('baotin-config-test-'+[guid]::NewGuid().ToString('N'))
$cfg=@{DB_URL='jdbc:postgresql://127.0.0.1:15432/baotin'}
$pairs=@(@('THREAT_CONFIG_DIR','baotin_threat'),@('REPORT_CONFIG_DIR','baotin_report'),@('NOTIFICATION_CONFIG_DIR','baotin_notification'),@('ANALYTICS_CONFIG_DIR','baotin_analytics'),@('SEARCH_CONFIG_DIR','baotin_search'))
function Reject([scriptblock]$Operation){$denied=$false;try{& $Operation}catch{$denied=$true};if(!$denied){throw 'Invalid runtime config was accepted'}}
try{
    foreach($pair in $pairs){
        $dir=Join-Path $root $pair[1];New-Item -ItemType Directory -Path $dir -Force|Out-Null;$cfg[$pair[0]]=$dir
        [IO.File]::WriteAllText((Join-Path $dir 'spring.datasource.username'),$pair[1])
        [IO.File]::WriteAllText((Join-Path $dir 'spring.datasource.password'),'TEST-FIXTURE-ONLY-password-at-least-32')
        [IO.File]::WriteAllText((Join-Path $dir 'spring.datasource.url'),$cfg.DB_URL)
    }
    [IO.File]::WriteAllText((Join-Path $cfg.NOTIFICATION_CONFIG_DIR 'notification.threat-uri'),'http://127.0.0.1:8182')
    [IO.File]::WriteAllText((Join-Path $cfg.NOTIFICATION_CONFIG_DIR 'notification.threat-token'),'TEST-FIXTURE-ONLY-target-credential-at-least-32')
    [IO.File]::WriteAllText((Join-Path $cfg.ANALYTICS_CONFIG_DIR 'spring.flyway.enabled'),'false')
    [IO.File]::WriteAllText((Join-Path $cfg.ANALYTICS_CONFIG_DIR 'analytics.security.jwks-uri'),'http://127.0.0.1:8181/.well-known/jwks.json')
    Assert-BusinessRuntimeConfig $cfg $PSScriptRoot
    $file=Join-Path $cfg.ANALYTICS_CONFIG_DIR 'spring.flyway.enabled';[IO.File]::WriteAllText($file,'true');Reject {Assert-BusinessRuntimeConfig $cfg $PSScriptRoot};[IO.File]::WriteAllText($file,'false')
    $file=Join-Path $cfg.ANALYTICS_CONFIG_DIR 'analytics.security.jwks-uri';[IO.File]::WriteAllText($file,'http://localhost:8081/.well-known/jwks.json');Reject {Assert-BusinessRuntimeConfig $cfg $PSScriptRoot};[IO.File]::WriteAllText($file,'http://127.0.0.1:8181/.well-known/jwks.json')
    foreach($pair in $pairs){
        $file=Join-Path $cfg[$pair[0]] 'spring.datasource.username';[IO.File]::WriteAllText($file,'postgres')
        Reject {Assert-BusinessRuntimeConfig $cfg $PSScriptRoot};[IO.File]::WriteAllText($file,$pair[1])
        $file=Join-Path $cfg[$pair[0]] 'spring.flyway.password';[IO.File]::WriteAllText($file,'fixture-migration-secret')
        Reject {Assert-BusinessRuntimeConfig $cfg $PSScriptRoot};Remove-Item -LiteralPath $file
        $file=Join-Path $cfg[$pair[0]] 'spring.datasource.url';[IO.File]::WriteAllText($file,'jdbc:postgresql://other-host/other-db')
        Reject {Assert-BusinessRuntimeConfig $cfg $PSScriptRoot};[IO.File]::WriteAllText($file,$cfg.DB_URL)
    }
    $file=Join-Path $cfg.NOTIFICATION_CONFIG_DIR 'notification.threat-token';Remove-Item -LiteralPath $file
    Reject {Assert-BusinessRuntimeConfig $cfg $PSScriptRoot}
    'BUSINESS_CONFIG PASS runtime_roles=exact database=reviewed migration_credentials=denied missing_target_bridge=denied'
}finally{
    $target=[IO.Path]::GetFullPath($root);$parent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\'
    if(!$target.StartsWith($parent,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($target) -notmatch '^baotin-config-test-[a-f0-9]{32}$'){throw 'Unexpected fixture cleanup target'}
    if(Test-Path -LiteralPath $target){Remove-Item -LiteralPath $target -Recurse -Force}
}
