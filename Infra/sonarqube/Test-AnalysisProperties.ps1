$ErrorActionPreference='Stop'
$source=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'Configure-AnalysisIdentity.ps1'))
$start=$source.IndexOf('$lines=[IO.File]::ReadAllLines($properties)')
$end=$source.IndexOf('[IO.File]::WriteAllLines($properties,$lines)',$start)
if($start -lt 0 -or $end -lt $start){throw 'Canonical normalization block missing'}
$end+='[IO.File]::WriteAllLines($properties,$lines)'.Length
$normalize=[scriptblock]::Create($source.Substring($start,$end-$start))
$temp=Join-Path ([IO.Path]::GetTempPath()) ('baotin-sonar-props-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $temp|Out-Null
try{
 $properties=Join-Path $temp 'fixture.properties';$candidate='synthetic-analysis-value'
 $count=0
 foreach($line in 'SONAR_TOKEN=old','SONAR_TOKEN = old','  SONAR_TOKEN  = old',("`tSONAR_TOKEN`t= old")){
  [IO.File]::WriteAllLines($properties,@('# fixture','OTHER_FIELD=preserved',$line))
  & $normalize
  $result=[IO.File]::ReadAllLines($properties)
  if($result[2] -cne 'SONAR_TOKEN=synthetic-analysis-value' -or $result[1] -ne 'OTHER_FIELD=preserved'){throw 'Whitespace normalization failed'}
  $count++
 }
 foreach($bad in @(@('OTHER_FIELD=x'),@('SONAR_TOKEN=one',' SONAR_TOKEN =two'))){
  [IO.File]::WriteAllLines($properties,$bad);$before=(Get-FileHash $properties).Hash;$denied=$false
  try{& $normalize}catch{$denied=$true}
  if(-not $denied -or (Get-FileHash $properties).Hash -ne $before){throw 'Missing/duplicate token was not rejected without mutation'}
  $count++
 }
 if($source -notmatch '\$generatedName=\$token\.name' -or $source -notmatch '\$oldToken\.name -ne \$generatedName'){throw 'Token name must survive dot-sourced Jenkins variable changes'}
 "SONAR_PROPERTIES PASS cases=$count whitespace=normalized duplicates=denied other_fields=preserved token_name=stable"
}finally{
 $resolved=[IO.Path]::GetFullPath($temp)
 if(-not $resolved.StartsWith([IO.Path]::GetTempPath(),[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^baotin-sonar-props-[a-f0-9]{32}$'){throw 'Unexpected fixture cleanup target'}
 Remove-Item -LiteralPath $resolved -Recurse -Force
}
