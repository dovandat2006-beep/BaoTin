#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$StateDirectory,[long]$ReserveBytes=1610612736)
$ErrorActionPreference='Stop'
$state=(Resolve-Path -LiteralPath $StateDirectory).Path
$workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if($state.Equals($workspace,[StringComparison]::OrdinalIgnoreCase) -or $state.StartsWith($workspace+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or (Get-Item $state).LinkType){throw 'Backups require a regular private external directory'}
if($ReserveBytes -lt 1GB){throw 'Keep at least 1 GiB reserve'}
$docker=(Get-Command docker -CommandType Application|Select-Object -First 1).Source
$python=(Get-Command python -CommandType Application|Select-Object -First 1).Source
$specs=@(
    @('jenkins-blueocean','jenkins-volumes.tar.xz'),
    @('docker','docker-volumes.tar.xz'),
    @('sonarqube','sonar-volumes.tar.xz'),
    @('baotin-sonar-db','sonar-db-volumes.tar.xz')
)
$before=@{}
foreach($spec in $specs){
    $c=@((& $docker inspect $spec[0])|ConvertFrom-Json)[0]
    if($LASTEXITCODE -or $c.State.Running){throw 'All original writers must be stopped for consistent backup'}
    $before[$spec[0]]=$c
}
$helperImage=$before['docker'].Image
$hashes=@()
foreach($spec in $specs){
    $archive=Join-Path $state $spec[1]
    & $python "$PSScriptRoot/backup-stream.py" --docker $docker --container $spec[0] --image $helperImage --archive $archive --reserve-bytes $ReserveBytes
    if($LASTEXITCODE){throw 'Consistent volume backup failed; no adoption permitted'}
    & tar -tf $archive *> $null
    if($LASTEXITCODE){throw 'Backup tar integrity failed'}
    $hashes+=@{name=$spec[1];sha256=(Get-FileHash -LiteralPath $archive).Hash;bytes=(Get-Item -LiteralPath $archive).Length}
}
foreach($spec in $specs){
    $now=@((& $docker inspect $spec[0])|ConvertFrom-Json)[0];$old=$before[$spec[0]]
    if($LASTEXITCODE -or $now.Id -ne $old.Id -or $now.State.Running -or $now.State.StartedAt -ne $old.State.StartedAt -or $now.State.FinishedAt -ne $old.State.FinishedAt){throw 'Writer state changed during backup; adoption denied'}
}
$hashes|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $state 'backup-hashes.json')
'CI_VOLUME_BACKUP PASS writers=stopped archives=4 secrets_logged=false'
