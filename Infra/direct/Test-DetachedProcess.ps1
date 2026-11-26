#Requires -Version 7.4
[CmdletBinding()]
param([string]$Node=(Get-Command node).Source)
$ErrorActionPreference='Stop'
$root=Join-Path $env:TEMP ('baotin-detach-check-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $root|Out-Null
$child=$null;$childTicks=$null
try {
    foreach($mode in 'Baseline','Modified'){
        $stateFile=Join-Path $root ($mode+'.json')
        $parentFile=Join-Path $root ($mode+'.ps1')
        $helper=(Join-Path $PSScriptRoot 'DetachedProcess.ps1').Replace("'","''")
        $code=@'
$ErrorActionPreference='Stop'
$psi=[Diagnostics.ProcessStartInfo]::new('__NODE__');$psi.UseShellExecute=$false;$psi.CreateNoWindow=$true;$psi.WorkingDirectory='__ROOT__'
$psi.ArgumentList.Add('-e');$psi.ArgumentList.Add('console.log("FIXTURE_READY");setInterval(()=>{},1000)')
__LAUNCH__
[IO.File]::WriteAllText('__STATE__',(@{pid=$p.Id;startTicks=$p.StartTime.ToUniversalTime().Ticks}|ConvertTo-Json))
'PARENT_FINISHED'
'@
        $launch=if($mode -eq 'Baseline'){'$p=[Diagnostics.Process]::Start($psi)'}else{". '$helper';`$p=Start-BaoTinDetachedProcess `$psi '__ROOT__/fixture'"}
        $code=$code.Replace('__LAUNCH__',$launch).Replace('__NODE__',$Node.Replace("'","''")).Replace('__ROOT__',$root.Replace("'","''")).Replace('__STATE__',$stateFile.Replace("'","''"))
        [IO.File]::WriteAllText($parentFile,$code)
        $psi=[Diagnostics.ProcessStartInfo]::new((Get-Command pwsh).Source);$psi.UseShellExecute=$false;$psi.CreateNoWindow=$true;$psi.RedirectStandardOutput=$true;$psi.RedirectStandardError=$true
        foreach($arg in @('-NoProfile','-File',$parentFile)){$psi.ArgumentList.Add($arg)}
        $parent=[Diagnostics.Process]::Start($psi);$read=$parent.StandardOutput.ReadToEndAsync();$errors=$parent.StandardError.ReadToEndAsync()
        if(-not $parent.WaitForExit(20000)){throw 'Fixture parent did not exit'}
        if($parent.ExitCode -ne 0){throw 'Fixture parent launch failed; inspect isolated test only'}
        $record=Get-Content $stateFile -Raw|ConvertFrom-Json;$child=Get-Process -Id $record.pid;$childTicks=$record.startTicks
        Start-Sleep -Seconds 2
        if($mode -eq 'Baseline'){
            if($read.IsCompleted){throw 'Expected original inherited-pipe behavior was not reproduced'}
            'DETACHED_BASELINE parent=exited output_pipe=held child=alive'
        }else{
            if(-not $read.IsCompleted -or $read.Result -notmatch 'PARENT_FINISHED'){throw 'Detached child still holds parent output pipe'}
            if(-not(Get-Process -Id $child.Id -ErrorAction SilentlyContinue)){throw 'Detached child died with parent'}
            $stream=[IO.File]::Open((Join-Path $root 'fixture.out.log'),[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
            $reader=[IO.StreamReader]::new($stream)
            try{if(-not $reader.ReadToEnd().Contains('FIXTURE_READY')){throw 'Child output not captured in owned file'}}finally{$reader.Dispose()}
            'DETACHED_MODIFIED PASS parent=exited output_pipe=closed child=alive log=file elevated=unchanged'
        }
        $owned=Get-Process -Id $child.Id -ErrorAction SilentlyContinue;if($owned -and $owned.StartTime.ToUniversalTime().Ticks -eq $childTicks){Stop-Process -Id $owned.Id -Force};$child=$null
    }
}finally{
    if($child){$owned=Get-Process -Id $child.Id -ErrorAction SilentlyContinue;if($owned -and $owned.StartTime.ToUniversalTime().Ticks -eq $childTicks){Stop-Process -Id $owned.Id -Force}}
    $resolved=[IO.Path]::GetFullPath($root)
    if(-not $resolved.StartsWith([IO.Path]::GetFullPath($env:TEMP).TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetFileName($resolved) -notmatch '^baotin-detach-check-[a-f0-9]{32}$'){throw 'Unexpected detached-fixture cleanup path'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
