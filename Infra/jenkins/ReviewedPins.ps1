#Requires -Version 7.4
function Read-ReviewedPins([string]$Json) {
    if($Json.Length -gt 32768){throw 'Reviewed manifest exceeds bounded size'}
    try{$data=$Json|ConvertFrom-Json -AsHashtable -ErrorAction Stop}catch{throw 'Reviewed manifest must be JSON'}
    $names=@('API-Gateway','Auth-Service','Threat-Service','Report-Service','Reputation-Service',
        'Model-Inference-Service','Notification-Service','Search-Service','Campaign-Service','Analytics-Service','Frontend','Infra')
    if($data -isnot [System.Collections.IDictionary] -or $data.Count -ne 2 -or
       -not $data.Contains('schemaVersion') -or ($data.schemaVersion -isnot [int] -and $data.schemaVersion -isnot [long]) -or $data.schemaVersion -ne 1 -or
       -not $data.Contains('repositories') -or $data.repositories -isnot [System.Collections.IDictionary] -or
       $data.repositories.Count -ne $names.Count -or
       @(Compare-Object -ReferenceObject $names -DifferenceObject @($data.repositories.Keys) -CaseSensitive).Count){throw 'Reviewed manifest schema/scope mismatch'}
    foreach($name in $names){
        if(-not $data.repositories.Contains($name) -or $data.repositories[$name] -isnot [string] -or
           $data.repositories[$name] -cnotmatch '^[a-f0-9]{40}$' -or $data.repositories[$name] -eq ('0'*40)){
            throw 'Each confirmed repository requires one explicit reviewed commit SHA'
        }
    }
    return $data
}
