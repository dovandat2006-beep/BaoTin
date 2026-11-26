#Requires -Version 7.4
[CmdletBinding()]
param([string]$DeploymentScript=(Join-Path $PSScriptRoot 'Deploy-Jars.ps1'),[switch]$Baseline)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'JarArtifacts.ps1')
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($DeploymentScript,[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Deployment script does not parse'}
$loops=@($ast.FindAll({param($n) $n -is [Management.Automation.Language.ForEachStatementAst] -and $n.Variable.VariablePath.UserPath -eq 'name' -and $n.Extent.Text.Contains("'artifact/'+`$route")},$true))
if($loops.Count -ne 1){throw 'Exact artifact staging loop required'}
$stage=[scriptblock]::Create($loops[0].Extent.Text)
$fixture=Join-Path $env:TEMP ('baotin-jar-reuse-'+[guid]::NewGuid().ToString('N'))
if(Test-Path -LiteralPath $fixture){throw 'Fresh fixture required'}
$root=$fixture;$previous=Join-Path $root ('releases/'+('a'*32));$jar='API-Gateway-0.0.1-SNAPSHOT.jar'
New-Item -ItemType Directory -Path $previous -Force|Out-Null
$bytes=[Text.Encoding]::UTF8.GetBytes('original immutable fixture JAR bytes')
$next=[Text.Encoding]::UTF8.GetBytes('changed immutable fixture JAR bytes')
$source=Join-Path $previous $jar;[IO.File]::WriteAllBytes($source,$bytes)
$hash=(Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant()
$nextHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($next)).ToLowerInvariant()
$links=[Collections.Generic.List[string]]::new();$checks=0
function Require([bool]$Condition,[string]$Message){if(-not $Condition){throw $Message};$script:checks++}
function New-Release { $p=Join-Path $root ('releases/'+[guid]::NewGuid().ToString('N'));Microsoft.PowerShell.Management\New-Item -ItemType Directory -Path $p|Out-Null;return $p }
function Invoke-RestMethod([string]$Uri){if($Uri.EndsWith('release-evidence.json')){return @{jarSha256=$script:expectedHash}};return @{result='SUCCESS';building=$false}}
function Fetch([string]$Route,[string]$File){$script:fetchCalls++;[IO.File]::WriteAllBytes($File,$script:downloadBytes)}
function Run-Stage([string]$Expected,[byte[]]$Downloaded){
 $script:expectedHash=$Expected;$script:downloadBytes=$Downloaded;$script:fetchCalls=0
 $release=New-Release;$reuseDirectory=$previous;$reusedJars=0;$downloadedJars=0
 $names=@('API-Gateway');$builds=@{'API-Gateway'=41};$http=@{};$controller=[uri]'http://127.0.0.1:8080/'
 . $stage
 return (Join-Path $release $jar)
}
try{
 $destination=Run-Stage $hash $bytes
 if($Baseline){
  if($script:fetchCalls -ne 0){Write-Output 'BASELINE_JAR_STAGE FAIL existingVerifiedJarDownloaded=1 hardlinkReuse=false';exit 1}
  throw 'Expected baseline download behavior was not observed'
 }
 Require ($script:fetchCalls -eq 0 -and (Get-Item -LiteralPath $destination).LinkType -eq 'HardLink') 'Unchanged exact-CI JAR was downloaded instead of reused'
 Require ((Get-FileHash -LiteralPath $destination).Hash.ToLowerInvariant() -eq $hash) 'Reused bytes differ'
 $rejected=[IO.Path]::GetDirectoryName($destination)
 if(-not $rejected.StartsWith($fixture+'\',[StringComparison]::OrdinalIgnoreCase) -or $rejected -eq $previous){throw 'Unexpected rejected-release fixture'}
 Remove-Item -LiteralPath $rejected -Recurse -Force
 Require ((Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant() -eq $hash) 'Rejected-release cleanup changed the original shared JAR'
 $changed=Run-Stage $nextHash $next
 Require ($script:fetchCalls -eq 1 -and -not (Get-Item -LiteralPath $changed).LinkType) 'Changed CI JAR must download independently'
 Require ((Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant() -eq $hash) 'Original JAR was modified'
 $failed=$false;try{Run-Stage $nextHash $bytes|Out-Null}catch{$failed=$true}
 Require $failed 'Corrupt downloaded artifact was accepted'
 $failed=$false;try{Run-Stage 'not-a-sha256' $bytes|Out-Null}catch{$failed=$true}
 Require ($failed -and $script:fetchCalls -eq 0) 'Invalid CI hash must fail before downloading'
 $missing=New-Release;$dest=Join-Path (New-Release) $jar
 Require (-not(Try-ReuseVerifiedJar $root $missing $dest $jar $hash)) 'Missing previous JAR must use ordinary retrieval'
 Require (-not(Try-ReuseVerifiedJar $root $fixture $dest $jar $hash)) 'Out-of-release source directory was read'
 $failed=$false;try{Try-ReuseVerifiedJar $root $previous $dest '../private.jar' $hash|Out-Null}catch{$failed=$true}
 Require $failed 'Traversal filename was accepted'
 [IO.File]::WriteAllText($dest,'existing destination sentinel');$sentinel=(Get-FileHash -LiteralPath $dest).Hash
 $failed=$false;try{Try-ReuseVerifiedJar $root $previous $dest $jar $hash|Out-Null}catch{$failed=$true}
 Require ($failed -and (Get-FileHash -LiteralPath $dest).Hash -eq $sentinel) 'Existing destination was overwritten'
 $junction=Join-Path $root ('releases/'+('c'*32));Microsoft.PowerShell.Management\New-Item -ItemType Junction -Path $junction -Target $previous|Out-Null;$links.Add($junction)
 Require (-not(Try-ReuseVerifiedJar $root $junction (Join-Path (New-Release) $jar) $jar $hash)) 'Reparse source ancestor was followed'
 Require (-not(Try-ReuseVerifiedJar $root $previous (Join-Path $junction $jar) $jar $hash)) 'Reparse destination ancestor was followed'
 function New-Item {throw 'Synthetic hardlink unsupported'}
 try{Require (-not(Try-ReuseVerifiedJar $root $previous (Join-Path (New-Release) $jar) $jar $hash)) 'Unsupported hardlink must preserve ordinary retrieval'}finally{Remove-Item -LiteralPath Function:\New-Item}
 $partial=Join-Path (New-Release) $jar
 function New-Item([string]$ItemType,[string]$Path,[string]$Target,[string]$ErrorAction){Microsoft.PowerShell.Management\New-Item -ItemType HardLink -Path $Path -Target $Target|Out-Null;throw 'Synthetic partial result'}
 $failed=$false;try{Try-ReuseVerifiedJar $root $previous $partial $jar $hash|Out-Null}catch{$failed=$true}finally{Remove-Item -LiteralPath Function:\New-Item}
 Require ($failed -and (Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant() -eq $hash) 'Partial link failure must not download over shared bytes'
 Write-Output "JAR_ARTIFACT_REUSE PASS checks=$checks unchanged=hardlink changed=download corrupt=denied missing=download invalidHash=denied reparse=denied partialLink=failClosed originalHash=preserved realNetwork=false realProcessesStopped=0"
}finally{
 foreach($link in $links){[IO.Directory]::Delete($link)}
 if([IO.Path]::GetFullPath($fixture) -ne $fixture -or [IO.Path]::GetFileName($fixture) -notmatch '^baotin-jar-reuse-[a-f0-9]{32}$'){throw 'Unexpected fixture cleanup target'}
 if(@(Get-ChildItem -LiteralPath $fixture -Recurse -Force -Attributes ReparsePoint).Count){throw 'Fixture reparse cleanup guard'}
 Remove-Item -LiteralPath $fixture -Recurse -Force
}
exit 0
