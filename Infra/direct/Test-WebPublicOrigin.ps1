#Requires -Version 7.4
$ErrorActionPreference='Stop'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Deploy-Jars.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Deployment script parse failed'}
foreach($function in $ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -in @('Assert-WebPublicOrigin','Launch')},$true)){. ([scriptblock]::Create($function.Extent.Text))}
Assert-WebPublicOrigin '';Assert-WebPublicOrigin 'https://app.example'
$rejected=0
foreach($bad in @('http://app.example','https://user:pass@app.example','https://app.example/path','https://app.example/','https://app.example?next=other','https://app.example#secret','https://bảo-tin.example','not-a-uri')){
 $failed=$false;try{Assert-WebPublicOrigin $bad}catch{$failed=$true};if(-not $failed){throw 'Invalid web origin accepted'};$rejected++
}
$names=@('Frontend');$ports=@{Frontend=8091};$node='C:\fixture\node.exe';$java='C:\fixture\java.exe';$cfg=@{WEB_PUBLIC_ORIGIN='https://app.example'}
$script:info=$null
function Start-BaoTinDetachedProcess($Info,$LogPrefix){$script:info=$Info;return [pscustomobject]@{Id=99999;StartTime=[DateTime]::Now}}
function Wait-RuntimeService($State,$Name){}
function Stop-Owned($State){throw 'No real process may be stopped'}
$state=Launch 'C:\fixture\release'
if($script:info.Environment['BAOTIN_WEB_PUBLIC_ORIGIN'] -ne 'https://app.example'){throw 'Reviewed web origin did not reach only the web runtime'}
$cfg=@{};$state=Launch 'C:\fixture\release'
if($script:info.Environment.ContainsKey('BAOTIN_WEB_PUBLIC_ORIGIN')){throw 'Absent origin must retain the documented server default'}
"WEB_PUBLIC_ORIGIN PASS valid=2 rejected=$rejected deploymentForwarding=verified absent=default realProcessesStarted=0 realProcessesStopped=0"
