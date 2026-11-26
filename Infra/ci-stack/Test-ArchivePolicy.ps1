#Requires -Version 7.4
$ErrorActionPreference='Stop'
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'Switch-ExistingStack.ps1'),[ref]$null,[ref]$null)
$fn=$ast.Find({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Assert-VolumeArchives'},$true)
if(-not $fn){throw 'Archive policy function missing'}
Invoke-Expression $fn.Extent.Text
$tmp=Join-Path ([IO.Path]::GetTempPath()) ('baotin-archive-policy-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $tmp|Out-Null
try{
    $count=0;$denied=$false
    try{Assert-VolumeArchives $tmp $false}catch{$denied=$true}
    if(-not $denied){throw 'Default archive requirement bypassed'};$count++
    $result=Assert-VolumeArchives $tmp $true
    if($result -ne 'CI_STACK_VOLUME_ARCHIVE skipped=explicit_operator_option'){throw 'Explicit waiver failed'};$count++
    $hashes=@();foreach($name in @('jenkins-volumes.tar.xz','docker-volumes.tar.xz','sonar-volumes.tar.xz','sonar-db-volumes.tar.xz')){
        $path=Join-Path $tmp $name;[IO.File]::WriteAllBytes($path,[byte[]]::new(2048))
        $hashes+=@{name=$name;sha256=(Get-FileHash $path).Hash;bytes=2048}
    }
    $hashes|ConvertTo-Json|Set-Content "$tmp/backup-hashes.json"
    Assert-VolumeArchives $tmp $false;$count++
    [IO.File]::WriteAllBytes((Join-Path $tmp $hashes[0].name),[byte[]]::new(2049))
    $denied=$false;try{Assert-VolumeArchives $tmp $false}catch{$denied=$true}
    if(-not $denied){throw 'Corrupt archive was accepted'};$count++
    $hashes[0..2]|ConvertTo-Json|Set-Content "$tmp/backup-hashes.json"
    $denied=$false;try{Assert-VolumeArchives $tmp $false}catch{$denied=$true}
    if(-not $denied){throw 'Incomplete archive manifest was accepted'};$count++
    "CI_ARCHIVE_POLICY PASS cases=$count default=required explicit_skip=accepted corrupt=denied missing_hash=denied Docker_calls=0"
}finally{
    $target=[IO.Path]::GetFullPath($tmp)
    if([IO.Path]::GetFileName($target) -notmatch '^baotin-archive-policy-[a-f0-9]{32}$' -or -not $target.StartsWith([IO.Path]::GetTempPath(),[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected fixture cleanup target'}
    Remove-Item -LiteralPath $target -Recurse -Force
}
