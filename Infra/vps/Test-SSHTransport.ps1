#Requires -Version 7.4
# Exercises SSH/SCP command flow using local native shims; never opens a socket.
$ErrorActionPreference='Stop'
$temp=Join-Path ([IO.Path]::GetTempPath()) ('baotin-ssh-transport-'+[guid]::NewGuid().ToString('N'))
$oldPath=$env:PATH;$oldLog=$env:BAOTIN_SSH_TEST_LOG;$oldMode=$env:BAOTIN_SSH_TEST_MODE
New-Item -ItemType Directory $temp|Out-Null
try{
 $ssh=@'
@echo off
echo SSH %*>>"%BAOTIN_SSH_TEST_LOG%"
if "%BAOTIN_SSH_TEST_MODE%"=="ssh" exit /b 6
if "%BAOTIN_SSH_TEST_MODE%"=="remote" echo %* | findstr /c:"remote-deploy.sh" >nul && exit /b 8
exit /b 0
'@
 $scp=@'
@echo off
echo SCP %*>>"%BAOTIN_SSH_TEST_LOG%"
if "%BAOTIN_SSH_TEST_MODE%"=="scp" exit /b 7
exit /b 0
'@
 [IO.File]::WriteAllText((Join-Path $temp 'ssh.cmd'),$ssh.Replace("`n","`r`n"))
 [IO.File]::WriteAllText((Join-Path $temp 'scp.cmd'),$scp.Replace("`n","`r`n"))
 $env:PATH=$temp+';'+$oldPath
 if((Get-Command ssh).Source -ne (Join-Path $temp 'ssh.cmd') -or (Get-Command scp).Source -ne (Join-Path $temp 'scp.cmd')){throw 'Native test shims not selected; never invoke real SSH'}
 $key=Join-Path $temp 'fixture key';$known=Join-Path $temp 'known hosts'
 [IO.File]::WriteAllText($key,'synthetic fixture only');[IO.File]::WriteAllText($known,'synthetic host key only')
 $config=Join-Path $temp 'vps.env'
 [IO.File]::WriteAllLines($config,@('VPS_HOST=192.0.2.1','VPS_USER=baotin_deploy','VPS_PORT=2222','VPS_ROOT=/opt/baotin',('KNOWN_HOSTS_FILE='+$known)))
 $m=Get-Content (Join-Path $PSScriptRoot 'images.example.json') -Raw|ConvertFrom-Json -AsHashtable
 foreach($k in @($m.images.Keys)){$m.images[$k]=$m.images[$k].Replace('REPLACE_WITH_SCANNED_DIGEST',('a'*64))}
 $manifest=Join-Path $temp 'images.json';[IO.File]::WriteAllText($manifest,($m|ConvertTo-Json -Depth 4))
 $count=0
 foreach($mode in 'success','ssh','scp','remote'){
  $env:BAOTIN_SSH_TEST_LOG=Join-Path $temp ($mode+'.log');$env:BAOTIN_SSH_TEST_MODE=$mode
  $failed=$false
  try{$output=@(& (Join-Path $PSScriptRoot 'Deploy-SSH.ps1') -ConfigFile $config -ImageManifest $manifest -KeyFile $key)}catch{$failed=$true;if($mode -eq 'success'){throw}}
  if($failed -ne ($mode -ne 'success')){throw "Transport fixture outcome mismatch: $mode"}
  $trace=Get-Content $env:BAOTIN_SSH_TEST_LOG -Raw
  if($trace -notmatch 'StrictHostKeyChecking=yes' -or $trace -notmatch 'BatchMode=yes' -or $trace -notmatch '2222'){throw 'SSH policy options missing'}
  $sshCalls=[regex]::Matches($trace,'(?m)^SSH ').Count;$scpCalls=[regex]::Matches($trace,'(?m)^SCP ').Count
  if($mode -eq 'ssh' -and ($sshCalls -ne 1 -or $scpCalls -ne 0)){throw 'Failed connection proceeded to upload'}
  if($mode -eq 'scp' -and ($sshCalls -ne 1 -or $scpCalls -ne 1)){throw 'Failed upload proceeded to remote deployment'}
  if($mode -in 'success','remote' -and ($sshCalls -ne 2 -or $scpCalls -ne 1 -or $trace -notmatch 'prepare-secrets.sh' -or $trace -notmatch 'docker-compose.yml')){throw 'Incomplete SSH release flow'}
  $count++
 }
 "VPS_SSH_TRANSPORT PASS cases=$count upload=verified remote_dispatch=verified failure_propagation=true strict_host_key=true sockets=0"
}finally{
 $env:PATH=$oldPath;$env:BAOTIN_SSH_TEST_LOG=$oldLog;$env:BAOTIN_SSH_TEST_MODE=$oldMode
 $resolved=[IO.Path]::GetFullPath($temp)
 if(-not $resolved.StartsWith([IO.Path]::GetTempPath(),[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^baotin-ssh-transport-[a-f0-9]{32}$'){throw 'Unexpected fixture cleanup target'}
 Remove-Item -LiteralPath $resolved -Recurse -Force
}
