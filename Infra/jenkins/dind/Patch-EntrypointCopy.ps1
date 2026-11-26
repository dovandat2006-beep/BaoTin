#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$OriginalFile,[Parameter(Mandatory)][string]$OutputFile,
      [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedSha256)
$ErrorActionPreference='Stop'
$source=(Resolve-Path -LiteralPath $OriginalFile).Path
$target=[IO.Path]::GetFullPath($OutputFile)
if($source -eq $target -or (Get-Item -LiteralPath $source).LinkType -or
   (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $ExpectedSha256.ToUpperInvariant()){
    throw 'Patch requires a distinct copy of the hash-verified regular original'
}
$entry=[IO.File]::ReadAllText($source).Replace("`r`n","`n")
$anchor='if [ "$1" = ''dockerd'' ]; then'
if(($entry -split [regex]::Escape($anchor)).Count -ne 2 -or $entry.Contains('BAOTIN_CONTAINERD_PID_GUARD_BEGIN')){
    throw 'Unexpected vendor entrypoint; no patch written'
}
$guard=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'containerd-pid-guard.sh')).Replace("`r`n","`n")
$insertion="$anchor`n# BAOTIN_CONTAINERD_PID_GUARD_BEGIN`n$guard`n# BAOTIN_CONTAINERD_PID_GUARD_END`n_baotin_clear_stale_containerd_pid /var/run/docker/containerd/containerd.pid /proc`n"
[IO.File]::WriteAllText($target,$entry.Replace($anchor,$insertion))
Write-Output 'DIND_ENTRYPOINT_COPY_PATCH PASS original_hash=verified live_mutation=false'
