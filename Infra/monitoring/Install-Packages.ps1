[CmdletBinding()]
param([Parameter(Mandatory)][string]$RuntimeRoot)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath($RuntimeRoot)
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if($root.StartsWith($workspace,[StringComparison]::OrdinalIgnoreCase)){throw 'Runtime must be outside the source workspace'}
if(Test-Path -LiteralPath $root){
    $item=Get-Item -LiteralPath $root
    while($item){if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Reparse path rejected'};$item=$item.Parent}
}
$drive=[IO.DriveInfo]::new([IO.Path]::GetPathRoot($root))
if($drive.AvailableFreeSpace -lt 3GB){throw 'At least 3 GiB free space is required for verified packages and runtime data'}
New-Item -ItemType Directory -Path (Join-Path $root 'downloads'),(Join-Path $root 'bin') -Force|Out-Null
foreach($package in (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'packages.json') -Raw|ConvertFrom-Json)){
    $destination=Join-Path $root ('bin/'+$package.directory)
    if(Test-Path -LiteralPath $destination){throw 'Package destination exists; verify/reuse it rather than overwriting'}
    $archive=Join-Path $root ('downloads/'+$package.name+'.zip')
    Invoke-WebRequest -Uri $package.url -OutFile $archive -TimeoutSec 600
    if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $package.sha256){throw 'Package SHA256 mismatch'}
    Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $root 'bin')
    "VERIFIED $($package.name) $($package.version)"
}
