#Requires -Version 7.4
$ErrorActionPreference='Stop'
$tokens=$null;$errors=$null
$source=Join-Path $PSScriptRoot 'Deploy-Jars.ps1'
$ast=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Deployment source must parse'}
foreach($f in $ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -in @('Launch','Stop-Owned')},$true)){
    . ([scriptblock]::Create($f.Extent.Text))
}
# The OS process-start boundary records the actual launch request; no JVM starts.
$script:captured=@{}
function Start-BaoTinDetachedProcess($Info,$LogPrefix){
    $name=[IO.Path]::GetFileName($LogPrefix)
    $script:captured[$name]=$Info
    return [pscustomobject]@{Id=100001+$script:captured.Count;StartTime=[DateTime]::Now}
}
function Wait-RuntimeService($State,$Name){}
$names=@('Threat-Service','Report-Service','Notification-Service')
$ports=@{'Threat-Service'=8182;'Report-Service'=8183;'Notification-Service'=8186}
$java='C:\fixture\java.exe';$node='C:\fixture\node.exe'
$cfg=@{'THREAT_CONFIG_DIR'='C:\fixture\threat';'REPORT_CONFIG_DIR'='C:\fixture\report';'NOTIFICATION_CONFIG_DIR'='C:\fixture\notification';'AUTH_ALLOWED_ORIGINS'='https://baotin.rimiprod.me'}
$state=Launch 'C:\fixture\accepted-release'
if($state.processes.Count -ne 3){throw 'Every business service must be launched'}
foreach($name in $names){
    $info=$script:captured[$name];$key=$name.Split('-')[0].ToUpperInvariant()+'_CONFIG_DIR'
    $expected='configtree:'+($cfg[$key].Replace('\','/').TrimEnd('/')+'/')
    if($info.Environment['SPRING_CONFIG_IMPORT'] -ne $expected){throw "Business config-tree missing: $name"}
    if($info.Environment['SPRING_FLYWAY_ENABLED'] -ne 'false' -or $info.Environment['DB_MIGRATION_ENABLED'] -ne 'false'){throw 'Runtime migration must stay disabled'}
    if($info.Environment['AUTH_JWKS_URI'] -ne 'http://127.0.0.1:8181/.well-known/jwks.json'){throw 'Private Auth JWKS is required'}
    if($info.Environment['SERVER_ADDRESS'] -ne '127.0.0.1'){throw 'Business listener must stay loopback'}
    if($name -eq 'Notification-Service' -and $info.Environment['NOTIFICATION_ALLOWED_ORIGINS'] -ne 'https://baotin.rimiprod.me'){throw 'Notification must accept the reviewed browser origin'}
    foreach($key in @('DB_PASSWORD','DB_MIGRATION_PASSWORD','MODEL_SERVICE_TOKEN','REPORT_THREAT_SERVICE_TOKEN')){
        if($info.Environment.ContainsKey($key)){throw 'Credential contents must not be copied into environment from config paths'}
    }
}
$sourceText=[IO.File]::ReadAllText($source)
foreach($key in @('THREAT_CONFIG_DIR','REPORT_CONFIG_DIR','NOTIFICATION_CONFIG_DIR')){
    if(!$sourceText.Substring(0,$sourceText.IndexOf("function Fetch")).Contains("'"+$key+"'")){throw 'External config path must be preflighted'}
}
'DEPLOYMENT_ENVIRONMENT PASS business_services=3 configtree=separate runtime_migration=false loopback=true credentials=external_files'
