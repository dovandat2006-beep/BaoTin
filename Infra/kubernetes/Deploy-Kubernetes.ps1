#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][ValidateSet('k8s','k3s')][string]$Profile,
      [Parameter(Mandatory)][string]$ConfigFile,[Parameter(Mandatory)][string]$ImageManifest,
      [Parameter(Mandatory)][string]$KubeConfig,[Parameter(Mandatory)][string]$StateDirectory,
      [string]$BuildsFile,[string]$JarEnvFile,
      [switch]$ValidateOnly)
$ErrorActionPreference='Stop'
$python=Get-Command python -ErrorAction Stop
$arguments=@('-B',(Join-Path $PSScriptRoot 'deploy.py'),'--profile',$Profile,'--config',$ConfigFile,
    '--images',$ImageManifest,'--kubeconfig',$KubeConfig,'--state-directory',$StateDirectory)
if($ValidateOnly){$arguments+='--validate-only'}
# Offline input validation precedes credential reads or any cluster requests.
& $python.Source @($arguments|Where-Object {$_ -ne '--validate-only'}) --validate-only
if($LASTEXITCODE){throw 'Offline Kubernetes input validation failed'}
if([bool]$BuildsFile -ne [bool]$JarEnvFile){throw 'Both immutable builds and Jenkins configuration are required together'}
if($BuildsFile){& (Join-Path $PSScriptRoot 'Test-JenkinsImageBinding.ps1') -BuildsFile $BuildsFile -JarEnvFile $JarEnvFile -ImageManifest $ImageManifest -ConfigFile $ConfigFile}
if($ValidateOnly){return}
& $python.Source @arguments
if($LASTEXITCODE){throw 'Kubernetes deployment gate failed; inspect sanitized result, not credentials'}
