# Content-addressed reuse of immutable CI JAR bytes. Never writes an existing link.
function Try-ReuseVerifiedJar([string]$RuntimeRoot,[string]$PreviousDirectory,[string]$Destination,[string]$FileName,[string]$Sha256) {
    if($Sha256 -cnotmatch '^[a-f0-9]{64}$'){throw 'Immutable CI JAR SHA256 required'}
    if($FileName -cnotmatch '^[A-Za-z][A-Za-z0-9-]*-0\.0\.1-SNAPSHOT\.jar$'){throw 'Exact service JAR filename required'}
    if(-not $PreviousDirectory){return $false}
    $releases=[IO.Path]::GetFullPath((Join-Path $RuntimeRoot 'releases')).TrimEnd('\')+'\'
    $previous=[IO.Path]::GetFullPath($PreviousDirectory)
    $destinationPath=[IO.Path]::GetFullPath($Destination)
    $parent=[IO.Path]::GetDirectoryName($destinationPath)
    foreach($directory in @($previous,$parent)){
        if(-not $directory.StartsWith($releases,[StringComparison]::OrdinalIgnoreCase) -or
           [IO.Path]::GetFileName($directory) -cnotmatch '^[a-f0-9]{32}$'){return $false}
        if(-not(Test-Path -LiteralPath $directory -PathType Container)){return $false}
        for($item=Get-Item -LiteralPath $directory -Force;$null -ne $item;$item=$item.Parent){
            if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){return $false}
        }
    }
    if([IO.Path]::GetFileName($destinationPath) -cne $FileName){throw 'Destination filename mismatch'}
    if(Test-Path -LiteralPath $destinationPath){throw 'Existing artifact destination must never be overwritten'}
    $source=Join-Path $previous $FileName
    if(-not(Test-Path -LiteralPath $source -PathType Leaf)){return $false}
    if((Get-Item -LiteralPath $source -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){return $false}
    if((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Sha256){return $false}
    try{New-Item -ItemType HardLink -Path $destinationPath -Target $source -ErrorAction Stop|Out-Null}
    catch{
        if(Test-Path -LiteralPath $destinationPath){throw 'Unexpected partial artifact destination; do not download over it'}
        return $false
    }
    if((Get-FileHash -LiteralPath $destinationPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Sha256){throw 'Reused JAR differs from immutable CI evidence'}
    return $true
}
