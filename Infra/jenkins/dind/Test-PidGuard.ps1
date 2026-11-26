#Requires -Version 7.4
param([string]$EntrypointFile,[string]$TempDirectory=(Join-Path $env:TEMP ('baotin-pid-test-'+[guid]::NewGuid().ToString('N'))),
      [switch]$UseDockerShell=$true)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath($TempDirectory)
if(Test-Path -LiteralPath $root){throw 'Choose a fresh guard-test directory'}
New-Item -ItemType Directory -Path $root|Out-Null
try{
    $guard=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'containerd-pid-guard.sh')).Replace("`r`n","`n")
    if($EntrypointFile){
        $entry=[IO.File]::ReadAllText($EntrypointFile)
        $match=[regex]::Match($entry,'(?s)# BAOTIN_CONTAINERD_PID_GUARD_BEGIN\n(.*?)\n# BAOTIN_CONTAINERD_PID_GUARD_END')
        if(-not $match.Success -or $match.Groups[1].Value -ne $guard){throw 'Entrypoint lacks the exact tested PID guard'}
    }
    [IO.File]::WriteAllText((Join-Path $root 'guard.sh'),$guard)
    $test=@'
#!/bin/sh
set -eu
cd "$(dirname "$0")"
. ./guard.sh
mkdir -p fixture/proc/63
p="$PWD/fixture/containerd.pid"; proc="$PWD/fixture/proc"
_baotin_clear_stale_containerd_pid "$p" "$proc"; test ! -e "$p"
printf '63' > "$p"; printf 'dockerd\n' > "$proc/63/comm"
_baotin_clear_stale_containerd_pid "$p" "$proc"; test ! -e "$p"
test "$(cat "$proc/63/comm")" = dockerd
printf '63' > "$p"; printf 'containerd\n' > "$proc/63/comm"
_baotin_clear_stale_containerd_pid "$p" "$proc"; test "$(cat "$p")" = 63
printf '63' > "$p"; rm "$proc/63/comm"
_baotin_clear_stale_containerd_pid "$p" "$proc"; test ! -e "$p"
printf '63x' > "$p"
if _baotin_clear_stale_containerd_pid "$p" "$proc"; then exit 1; fi
test "$(cat "$p")" = 63x
printf '' > "$p"
if _baotin_clear_stale_containerd_pid "$p" "$proc"; then exit 1; fi
test -f "$p"; rm "$p"
mkdir "$p"
if _baotin_clear_stale_containerd_pid "$p" "$proc"; then exit 1; fi
test -d "$p"; rmdir "$p"
printf '63' > fixture/foreign.pid; ln -s "$PWD/fixture/foreign.pid" "$p"
if _baotin_clear_stale_containerd_pid "$p" "$proc"; then exit 1; fi
test "$(cat fixture/foreign.pid)" = 63; test -L "$p"
rm "$p"; ln -s "$PWD/fixture/missing.pid" "$p"
if _baotin_clear_stale_containerd_pid "$p" "$proc"; then exit 1; fi
test -L "$p"; test ! -e fixture/missing.pid
echo 'DIND_PID_GUARD_TESTS PASS cases=9 stale=removed live=preserved absent=noop malformed=denied path=denied dangling_symlink=denied signals=none'
'@
    $file=Join-Path $root 'test.sh';[IO.File]::WriteAllText($file,$test.Replace("`r`n","`n")+"`n")
    if($UseDockerShell){
        # Git Bash may emulate symlinks as copies on Windows. Use the actual
        # target BusyBox sh/filesystem for the symlink preservation assertion.
        $image=(docker --context desktop-linux inspect docker --format '{{.Image}}').Trim()
        if($LASTEXITCODE -or $image -notmatch '^sha256:[a-f0-9]{64}$'){throw 'Existing dind image identity required'}
        docker --context desktop-linux run --rm --network none --cap-drop ALL --security-opt no-new-privileges --memory 32m `
            --mount "type=bind,source=$root,target=/source,readonly" --entrypoint sh $image `
            -c 'mkdir /tmp/guard-test && cp /source/guard.sh /source/test.sh /tmp/guard-test/ && sh /tmp/guard-test/test.sh'
    }else{
        & 'C:\Program Files\Git\bin\bash.exe' $file.Replace('\','/')
    }
    if($LASTEXITCODE){throw 'PID guard regression failed'}
}finally{
    $resolved=(Resolve-Path -LiteralPath $root).Path
    if($resolved -ne $root -or (Get-Item -LiteralPath $resolved).LinkType -or $resolved.Length -lt 16){throw 'Unexpected test cleanup target'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
