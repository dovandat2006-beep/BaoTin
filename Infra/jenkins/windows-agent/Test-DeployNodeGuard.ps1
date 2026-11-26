#Requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Username,[Parameter(Mandatory)][string]$TokenFile)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../Test-JenkinsPipelines.ps1') -Username $Username -TokenFile $TokenFile -PipelineFiles @((Join-Path $PSScriptRoot '../../Jenkinsfile'))
$source=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'DeployNodeGuard.groovy'))
$test=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'Test-DeployNodeGuard.groovy'))
foreach($text in @($source,$test)){if($text.Contains("'''")){throw 'Unexpected Groovy test quoting'}}
$code="try { def shell=new GroovyShell(jenkins.model.Jenkins.get().pluginManager.uberClassLoader,new Binding([BAOTIN_GUARD_TEST_ONLY:true,out:out])); shell.evaluate('''$source'''); shell.evaluate('''$test''') } catch(Throwable e) { println 'GUARD_TEST_FAILED'; return null }"
$r=Request 'scriptText' 'POST' @{script=$code} $headers
$expected='DEPLOY_NODE_GUARD PASS checks=25 reviewed_sha=required replay=denied other_jobs=denied unrelated_nodes=unchanged mutation=false'
if($r.StatusCode -ne 200 -or ([string]$r.Content).Trim() -ne $expected){throw 'Deploy guard regressions failed; raw diagnostics withheld'}
Write-Output $expected
