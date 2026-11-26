#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$StateDirectory,[switch]$Baseline)
$ErrorActionPreference='Stop'
$state=(Resolve-Path -LiteralPath $StateDirectory).Path
$mapping=@{'jenkins-blueocean'='jenkins';'docker'='docker';'sonarqube'='sonarqube';'baotin-sonar-db'='sonar-db'}
$originals=@{}
foreach($name in $mapping.Keys){$originals[$mapping[$name]]=@(Get-Content "$state/$name.inspect.json" -Raw|ConvertFrom-Json)[0]}
if($Baseline){
    if($originals.jenkins.Config.Labels.PSObject.Properties.Name -contains 'com.docker.compose.project'){throw 'Baseline unexpectedly grouped'}
    if($originals.sonarqube.HostConfig.PortBindings.'9000/tcp'[0].HostPort -ne '9000'){throw 'Unexpected baseline port'}
    'CI_STACK_BASELINE PASS controller=standalone sonar_project=baotin-sonar host_port=9000 adoption=not_applied'
    return
}
$raw=& docker compose --env-file "$state/manifest.env" -f "$PSScriptRoot/compose.yml" config --format json
if($LASTEXITCODE){throw 'Compose config failed'}
$config=$raw|ConvertFrom-Json -AsHashtable
function Assert-Config($cfg){
    if($cfg.name -ne 'baotin-ci' -or $cfg.services.Count -ne 4){throw 'Expected one four-container CI project'}
    foreach($key in $mapping.Values){
        $svc=$cfg.services[$key];$old=$originals[$key]
        if($svc.container_name -ne $old.Name.TrimStart('/')){throw 'Container name changed'}
        if(-not $svc.healthcheck -or $svc.pull_policy -ne 'never'){throw 'Readiness/immutable local image required'}
        if($key -ne 'docker' -and $svc.privileged){throw 'Only dind may be privileged'}
        if($key -in @('docker','sonar-db') -and $svc.ports.Count){throw 'Private infrastructure port published'}
        if($svc.volumes.Count -ne $old.Mounts.Count){throw 'Persistent mount count changed'}
        foreach($m in $old.Mounts){
            $v=@($svc.volumes|Where-Object target -eq $m.Destination)
            if($v.Count -ne 1 -or $v[0].type -ne 'volume' -or $cfg.volumes[$v[0].source].name -ne $m.Name -or -not $cfg.volumes[$v[0].source].external -or [bool]$v[0].read_only -eq [bool]$m.RW){throw 'Exact persistent volume or access changed'}
        }
    }
    $p=@($cfg.services.sonarqube.ports)
    if($p.Count -ne 1 -or $p[0].target -ne 9000 -or $p[0].published -ne '9001' -or $p[0].host_ip -ne '0.0.0.0'){throw 'Sonar public IPv4 mapping must be 9001:9000'}
    foreach($network in $cfg.networks.Values){if(-not $network.external){throw 'Existing networks must remain external'}}
}
Assert-Config $config
$count=1
foreach($case in @('new_dind_volume','public_dind','public_db','wrong_sonar_port','extra_service','privileged_jenkins','rw_certs')){
    $bad=($config|ConvertTo-Json -Depth 50)|ConvertFrom-Json -AsHashtable
    switch($case){
        new_dind_volume {$bad.volumes['docker-data'].name='empty-replacement'}
        public_dind {$bad.services.docker.ports=@(@{target=2376;published='2376'})}
        public_db {$bad.services['sonar-db'].ports=@(@{target=5432;published='5432'})}
        wrong_sonar_port {$bad.services.sonarqube.ports[0].published='9000'}
        extra_service {$bad.services.other=@{}}
        privileged_jenkins {$bad.services.jenkins.privileged=$true}
        rw_certs {$bad.services.jenkins.volumes[1].read_only=$false}
    }
    $denied=$false;try{Assert-Config $bad}catch{$denied=$true}
    if(-not $denied){throw "Invalid config accepted: $case"};$count++
}
$errors=$null
foreach($file in Get-ChildItem $PSScriptRoot -Filter '*.ps1'){
    [System.Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$null,[ref]$errors)|Out-Null
    if($errors.Count){throw 'PowerShell parse failed'}
}
'CI_STACK_CONFIG PASS cases='+$count+' services=4 exact_volumes=true private_ports=denied syntax=valid changed=false'
