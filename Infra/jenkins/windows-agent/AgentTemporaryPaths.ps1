function Resolve-AgentTemporaryPaths([string]$TempRoot,[string]$CacheRoot) {
    $workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
    foreach($path in @($TempRoot,$CacheRoot)){
        if(-not $path){continue}
        if(-not [IO.Path]::IsPathFullyQualified($path)){throw 'Agent cache/temp paths must be absolute'}
        $full=[IO.Path]::GetFullPath($path)
        if($full -eq [IO.Path]::GetPathRoot($full) -or $full -eq $workspace -or $full.StartsWith($workspace+'\',[StringComparison]::OrdinalIgnoreCase)){
            throw 'Agent cache/temp paths must be dedicated external directories'
        }
        $parent=$full
        while($parent){
            if(Test-Path -LiteralPath $parent){
                $item=Get-Item -LiteralPath $parent -Force
                if(-not $item.PSIsContainer -or $item.LinkType){throw 'Agent cache/temp paths must not traverse file or link targets'}
            }
            $parent=Split-Path $parent -Parent
        }
    }
    $result=@{}
    if($TempRoot){$full=[IO.Path]::GetFullPath($TempRoot);$result.TEMP=$full;$result.TMP=$full}
    if($CacheRoot){$full=[IO.Path]::GetFullPath($CacheRoot);$result.npm_config_cache=Join-Path $full 'npm';$result.GRADLE_USER_HOME=Join-Path $full 'gradle'}
    return $result
}
