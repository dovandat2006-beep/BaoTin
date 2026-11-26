#Requires -Version 7.4
[CmdletBinding()]
param([string]$SecurityScript=(Join-Path $PSScriptRoot 'Test-ImageSecurity.ps1'),
      [string]$TempRoot=([IO.Path]::GetTempPath()))
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath($TempRoot)
$stage=Join-Path $root ('baotin-manifest-test-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage|Out-Null
$global:BaoTinManifestDockerCalls=0
function docker { $global:BaoTinManifestDockerCalls++;throw 'MANIFEST_VALIDATION_DOCKER_SENTINEL' }
function Good-Manifest {
    $images=@{};$i=1
    foreach($name in 'AUTH','GATEWAY','THREAT','REPORT','REPUTATION','MODEL','NOTIFICATION','SEARCH','CAMPAIGN','ANALYTICS','WEB'){
        $images[$name+'_IMAGE']='sha256:'+('{0:x64}' -f $i);$i++
    }
    return @{schemaVersion=1;images=$images}
}
function Check-Input([string]$Name,$Data,[bool]$Valid=$false){
    $path=Join-Path $stage ($Name+'.json')
    [IO.File]::WriteAllText($path,($Data|ConvertTo-Json -Depth 5))
    $global:BaoTinManifestDockerCalls=0;$failure=$null
    try{& $SecurityScript -Manifest $path -OutputDirectory (Join-Path $stage $Name)|Out-Null}catch{$failure=$_.Exception.Message}
    if($Valid){
        if($global:BaoTinManifestDockerCalls -ne 1 -or $failure -ne 'MANIFEST_VALIDATION_DOCKER_SENTINEL'){throw 'Valid complete manifest did not reach scanner transport'}
    }elseif(-not $failure -or $global:BaoTinManifestDockerCalls -ne 0){throw ('Invalid manifest accepted/reached Docker: '+$Name)}
}
try{
    Check-Input empty @{schemaVersion=1;images=@{}}
    Check-Input missing @{schemaVersion=1}
    $m=Good-Manifest;$m.images.Remove('AUTH_IMAGE');Check-Input missingService $m
    $m=Good-Manifest;$m.images.EXTRA_IMAGE='sha256:'+('a'*64);Check-Input extraService $m
    $m=Good-Manifest;$m.schemaVersion=2;Check-Input wrongVersion $m
    Check-Input arrayImages @{schemaVersion=1;images=@('sha256:'+('a'*64))}
    $m=Good-Manifest;$m.images.Remove('AUTH_IMAGE');$m.images.auth_image='sha256:'+('a'*64);Check-Input wrongCase $m
    $m=Good-Manifest;$m.images.Remove('AUTH_IMAGE');$m.images['../escape']='sha256:'+('a'*64);Check-Input pathName $m
    $m=Good-Manifest;$m.images.AUTH_IMAGE='nginx:latest';Check-Input mutableTag $m
    $m=Good-Manifest;$m.images.AUTH_IMAGE='sha256:'+('A'*64);Check-Input uppercaseId $m
    $m=Good-Manifest;$m.images.AUTH_IMAGE=123;Check-Input nonString $m
    $m=Good-Manifest;$m.images.AUTH_IMAGE=$m.images.GATEWAY_IMAGE;Check-Input duplicateId $m
    $m=Good-Manifest;$m.images.AUTH_IMAGE=$null;Check-Input nullId $m
    $m=Good-Manifest;$m.images.AUTH_IMAGE='';Check-Input emptyId $m
    Check-Input complete (Good-Manifest) $true
    'ALT_MANIFEST_VALIDATION PASS cases=15 invalid_before_docker=true complete_input=accepted synthetic_only=true'
}finally{
    Remove-Variable BaoTinManifestDockerCalls -Scope Global -ErrorAction SilentlyContinue
    $resolved=[IO.Path]::GetFullPath($stage)
    if(-not $resolved.StartsWith($root.TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^baotin-manifest-test-[a-f0-9]{32}$'){throw 'Unexpected fixture cleanup path'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
