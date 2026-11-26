#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Manifest,[Parameter(Mandatory)][string]$OutputDirectory,
      [switch]$Baseline)
$ErrorActionPreference='Stop'
if(-not $Baseline){
    $data=Get-Content -LiteralPath $Manifest -Raw|ConvertFrom-Json -AsHashtable
    $expected=@('AUTH_IMAGE','GATEWAY_IMAGE','THREAT_IMAGE','REPORT_IMAGE','REPUTATION_IMAGE','MODEL_IMAGE','NOTIFICATION_IMAGE','SEARCH_IMAGE','CAMPAIGN_IMAGE','ANALYTICS_IMAGE','WEB_IMAGE')
    if($data.schemaVersion -ne 1 -or $data.images -isnot [Collections.IDictionary] -or
       $data.images.Count -ne 11 -or @(Compare-Object $expected @($data.images.Keys) -CaseSensitive).Count){
        throw 'Complete schemaVersion=1 eleven-image manifest required'
    }
    foreach($image in $data.images.Values){
        if($image -isnot [string] -or $image -cnotmatch '^sha256:[a-f0-9]{64}$'){throw 'Immutable local image IDs required'}
    }
    if(@($data.images.Values|Select-Object -Unique).Count -ne 11){throw 'Eleven distinct service image IDs required'}
}
$scanner='aquasec/trivy@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa'
$context=(& docker context show).Trim()
function Invoke-ScanDocker([string[]]$Arguments){
    $out=& docker --context $context @Arguments 2>&1
    if($LASTEXITCODE){throw ('Docker verification operation failed: '+$Arguments[0])}
    return (($out|ForEach-Object{"$_"}) -join "`n").Trim()
}
$out=[IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $out -Force|Out-Null
$images=if($Baseline){@{WEB_BASELINE='nginxinc/nginx-unprivileged:1.28-alpine@sha256:7377697a821c131a924a7105fafbe7414db4e9fcc77a6f08f776f33f141ec3f8'}}else{$data.images}
$lock=$null;$child=$null;$archive=Join-Path $out 'image.tar';$records=@()
try {
    Invoke-ScanDocker -Arguments @('volume','create','baotin-security-trivy-cache')|Out-Null
    # Atomic shared cache ownership; preserve any pre-existing holder.
    $lock=Invoke-ScanDocker -Arguments @('create','--name','baotin-security-trivy-cache-lock','--label','baotin.security-cache-lock=true','--label','baotin.security-cache-owner=alternative-final','--network','none','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges','--entrypoint','/bin/true',$scanner)
    foreach($name in @($images.Keys|Sort-Object)){
        $id=Invoke-ScanDocker -Arguments @('image','inspect',$images[$name],'--format','{{.Id}}')
        Invoke-ScanDocker -Arguments @('save','-o',$archive,$id)|Out-Null
        $report=Join-Path $out ($name+'.json')
        $child=Invoke-ScanDocker -Arguments @('create','--cap-drop','ALL','--security-opt','no-new-privileges','-v','baotin-security-trivy-cache:/root/.cache/trivy',$scanner,'image','--input','/tmp/image.tar','--scanners','vuln','--severity','HIGH,CRITICAL','--exit-code','1','--format','json','--output','/tmp/report.json')
        Invoke-ScanDocker -Arguments @('cp',$archive,($child+':/tmp/image.tar'))|Out-Null
        & docker --context $context start -a $child
        $scanExit=$LASTEXITCODE
        Invoke-ScanDocker -Arguments @('cp',($child+':/tmp/report.json'),$report)|Out-Null
        Invoke-ScanDocker -Arguments @('rm',$child)|Out-Null;$child=$null
        $json=Get-Content -LiteralPath $report -Raw|ConvertFrom-Json -AsHashtable
        $vulns=@($json.Results|ForEach-Object{if($_.ContainsKey('Vulnerabilities')){$_.Vulnerabilities}})
        $os=@($json.Results|Where-Object{$_.Class -eq 'os-pkgs'}).Count
        $jar=@($json.Results|Where-Object{$_.Type -eq 'jar'}).Count
        if(-not $os -or ($name -notlike 'WEB*' -and -not $jar)){throw 'Missing OS/JAR scanner coverage'}
        $records+=@{name=$name;image=$id;scanExit=$scanExit;highCritical=$vulns.Count;osCoverage=$true;jarCoverage=($jar -gt 0)}
        "ALT_IMAGE_SCAN name=$name high_critical=$($vulns.Count) exit=$scanExit os=true jar=$($jar -gt 0)"
        if(-not $Baseline -and $scanExit -ne 0){throw 'Image vulnerability gate failed'}
        if(-not $Baseline){
            $child=Invoke-ScanDocker -Arguments @('create','--cap-drop','ALL','--security-opt','no-new-privileges','-v','baotin-security-trivy-cache:/root/.cache/trivy',$scanner,'image','--input','/tmp/image.tar','--format','cyclonedx','--output','/tmp/sbom.json')
            Invoke-ScanDocker -Arguments @('cp',$archive,($child+':/tmp/image.tar'))|Out-Null
            Invoke-ScanDocker -Arguments @('start','-a',$child)|Out-Null
            $sbom=Join-Path $out ($name+'.sbom.cdx.json')
            Invoke-ScanDocker -Arguments @('cp',($child+':/tmp/sbom.json'),$sbom)|Out-Null
            Invoke-ScanDocker -Arguments @('rm',$child)|Out-Null;$child=$null
            $bom=Get-Content -LiteralPath $sbom -Raw|ConvertFrom-Json -AsHashtable
            if($bom.bomFormat -ne 'CycloneDX' -or -not $bom.components.Count){throw 'SBOM generation failed'}
        }
        Remove-Item -LiteralPath $archive
    }
    [IO.File]::WriteAllText((Join-Path $out 'summary.json'),($records|ConvertTo-Json -Depth 5))
    if($Baseline){'BASELINE_SCAN_COMPLETE'}else{"ALT_SECURITY PASS images=$($images.Count) high_critical=0 coverage=OS_AND_JAR sboms=$($images.Count)"}
}finally{
    # Keep the lock if child cleanup fails; never permit overlapping cache writers.
    if($child){Invoke-ScanDocker -Arguments @('rm','-f',$child)|Out-Null}
    if($lock){Invoke-ScanDocker -Arguments @('rm',$lock)|Out-Null}
    if(Test-Path -LiteralPath $archive){Remove-Item -LiteralPath $archive}
}
