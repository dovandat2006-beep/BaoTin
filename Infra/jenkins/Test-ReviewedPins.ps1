#Requires -Version 7.4
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'ReviewedPins.ps1')
$repos=@('API-Gateway','Auth-Service','Threat-Service','Report-Service','Reputation-Service',
    'Model-Inference-Service','Notification-Service','Search-Service','Campaign-Service','Analytics-Service','Frontend','Infra')
function Fixture {$map=@{};foreach($name in $repos){$map[$name]='a'*40};@{schemaVersion=1;repositories=$map}}
$valid=Read-ReviewedPins ((Fixture)|ConvertTo-Json -Depth 5)
if($valid.repositories.Count -ne 12){throw 'Valid pin scope failed'}
$count=1
foreach($change in @('branch','zero','missing','extra','schema','uppercase','malformed','string_schema','case_scope')){
    $data=Fixture
    switch($change){
        branch {$data.repositories['Auth-Service']='*/main'}
        zero {$data.repositories['Auth-Service']='0'*40}
        missing {$data.repositories.Remove('Auth-Service')}
        extra {$data.repositories['Other-App']='a'*40}
        schema {$data.schemaVersion=2}
        uppercase {$data.repositories['Auth-Service']='A'*40}
        malformed {$data.repositories['Auth-Service']='a'*39+';'}
        string_schema {$data.schemaVersion='1'}
        case_scope {$data.repositories.Remove('Infra');$data.repositories['infra']='a'*40}
    }
    $failed=$false;try{Read-ReviewedPins ($data|ConvertTo-Json -Depth 5)|Out-Null}catch{$failed=$true}
    if(-not $failed){throw "Manifest negative case accepted: $change"};$count++
}
$bootstrap=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'init/baotin-ci.groovy'))
if($bootstrap -match "BranchSpec'.*\[\['\*/main'\]\]" -or
   -not $bootstrap.Contains('baotin-approved-main.json') -or -not $bootstrap.Contains('approvedPins[svc.repo]')){
    throw 'Bootstrap can still select moving/unreviewed main before Jenkinsfile retrieval'
}
Write-Output "REVIEWED_PINS_TESTS PASS cases=$count repositories=12 branch=denied zero=denied foreign=denied missing=denied bootstrap=pinned"
