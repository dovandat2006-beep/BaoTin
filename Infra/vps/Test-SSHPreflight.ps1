$ErrorActionPreference='Stop'
$temp=Join-Path ([IO.Path]::GetTempPath()) ('baotin-ssh-test-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $temp|Out-Null
try{
 $key=Join-Path $temp 'fixture.key';$known=Join-Path $temp 'known_hosts'
 [IO.File]::WriteAllText($key,'synthetic fixture, never used to connect');[IO.File]::WriteAllText($known,'synthetic host key')
 $manifest=Get-Content (Join-Path $PSScriptRoot 'images.example.json') -Raw|ConvertFrom-Json -AsHashtable
 foreach($k in @($manifest.images.Keys)){$manifest.images[$k]=$manifest.images[$k].Replace('REPLACE_WITH_SCANNED_DIGEST',('a'*64))}
 $mf=Join-Path $temp 'images.json';[IO.File]::WriteAllText($mf,($manifest|ConvertTo-Json -Depth 4))
 $base=@{VPS_HOST='192.0.2.1';VPS_USER='baotin_deploy';VPS_PORT='22';VPS_ROOT='/opt/baotin';KNOWN_HOSTS_FILE=$known}
 $count=0
 foreach($case in 'valid','host_injection','root_user','root_escape','bad_port','missing_hosts','placeholder','mutable_image','wrong_owner'){
  $cfg=$base.Clone();$m=($manifest|ConvertTo-Json -Depth 4)|ConvertFrom-Json -AsHashtable
  switch($case){
   host_injection {$cfg.VPS_HOST='host;touch BAD'}
   root_user {$cfg.VPS_USER='root'}
   root_escape {$cfg.VPS_ROOT='/opt/baotin/../other'}
   bad_port {$cfg.VPS_PORT='70000'}
   missing_hosts {$cfg.KNOWN_HOSTS_FILE=Join-Path $temp 'missing'}
   placeholder {$cfg.VPS_HOST='YOUR_VPS_HOST'}
   mutable_image {$m.images.AUTH_IMAGE='ghcr.io/vietluccuu/auth-service:main'}
   wrong_owner {$m.images.AUTH_IMAGE='ghcr.io/other/auth-service@sha256:'+('a'*64)}
  }
  $cf=Join-Path $temp 'vps.env';[IO.File]::WriteAllLines($cf,@($cfg.Keys|ForEach-Object {$_+'='+$cfg[$_]}))
  [IO.File]::WriteAllText($mf,($m|ConvertTo-Json -Depth 4))
  $passed=$false
  try{& (Join-Path $PSScriptRoot 'Deploy-SSH.ps1') -ConfigFile $cf -ImageManifest $mf -KeyFile $key -ValidateOnly|Out-Null;$passed=$true}catch{if($case -eq 'valid'){throw}}
  if($passed -ne ($case -eq 'valid')){throw "SSH preflight case failed: $case"};$count++
 }
 "VPS_SSH_TESTS PASS cases=$count injection=denied mutable_refs=denied host_key=required connection_attempted=false"
}finally{
 $resolved=[IO.Path]::GetFullPath($temp)
 if(-not $resolved.StartsWith([IO.Path]::GetTempPath(),[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^baotin-ssh-test-[a-f0-9]{32}$'){throw 'Invalid fixture cleanup target'}
 Remove-Item -LiteralPath $resolved -Recurse -Force
}
