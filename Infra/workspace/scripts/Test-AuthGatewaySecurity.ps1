#Requires -Version 7.4
[CmdletBinding()]
param([string]$OutputRoot=(Join-Path $PSScriptRoot '..\.changes\auth-security-gates\gate-tests'))
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$bash='C:\Program Files\Git\bin\bash.exe'
$fixture=Join-Path $root ('.changes\baotin-gate-fixture-'+[guid]::NewGuid().ToString('N'))
$vulnerableTag='baotin/security-vulnerable-fixture:'+([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture -Force|Out-Null
New-Item -ItemType Directory -Path $OutputRoot -Force|Out-Null
function Run-Gate([string]$Path,[string]$Image,[string]$Name,[int]$Expected){
    & $bash -c 'cd "$1" && export MSYS_NO_PATHCONV=1 DOCKER_CONTEXT=desktop-linux IMAGE_REFERENCE="$2" && sh ci/security-gates.sh' -- ($Path.Replace('\','/')) $Image
    $code=$LASTEXITCODE
    $dest=Join-Path $OutputRoot $Name;New-Item -ItemType Directory -Path $dest -Force|Out-Null
    foreach($report in @('gitleaks.json','dependencies.json','image.json','sbom.cdx.json')){$src=Join-Path $Path "build/security/$report";if(Test-Path $src){Copy-Item $src $dest -Force}}
    if($code -ne $Expected){throw "Gate case $Name returned $code instead of $Expected"}
    Write-Output "SECURITY_CASE PASS case=$Name exit=$code expected=$Expected"
}
try{
    Run-Gate (Join-Path $root 'Auth-Service') 'baotin/auth-service:security-check' 'auth-positive' 0
    Run-Gate (Join-Path $root 'API-Gateway') 'baotin/api-gateway:security-check' 'gateway-positive' 0
    New-Item -ItemType Directory -Path (Join-Path $fixture 'ci'),(Join-Path $fixture 'build/libs') -Force|Out-Null
    Copy-Item (Join-Path $root 'Auth-Service/ci/security-gates.sh') (Join-Path $fixture 'ci/security-gates.sh')
    $jar=Join-Path $fixture 'build/libs/fixture-SNAPSHOT.jar'
    Copy-Item (Join-Path $root 'Auth-Service/build/libs/Auth-Service-0.0.1-SNAPSHOT.jar') $jar
    $key=Join-Path $fixture 'synthetic-private.pem';$rsa=[Security.Cryptography.RSA]::Create(2048)
    try{[IO.File]::WriteAllText($key,$rsa.ExportPkcs8PrivateKeyPem())}finally{$rsa.Dispose()}
    Run-Gate $fixture 'baotin/auth-service:security-check' 'secret-denied' 1
    Remove-Item -LiteralPath $key
    # An explicit vulnerable vendor artifact avoids dependence on mutable release-check
    # tags: a normal release must not silently turn a negative test into a positive one.
    $url='https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/1.83/bcprov-jdk18on-1.83.jar'
    $imageDir=Join-Path $fixture 'vulnerable-image';New-Item -ItemType Directory -Path $imageDir|Out-Null
    $vulnerableJar=Join-Path $imageDir 'vulnerable.jar';Invoke-WebRequest $url -OutFile $vulnerableJar
    $expected=(Invoke-WebRequest "$url.sha256").Content.Trim()
    if((Get-FileHash $vulnerableJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected.ToLowerInvariant()){throw 'Vendor fixture checksum mismatch'}
    [IO.File]::WriteAllText((Join-Path $imageDir 'Dockerfile'),"FROM baotin/auth-service:security-check`nCOPY vulnerable.jar /app/vulnerable-fixture.jar`nLABEL baotin.security.fixture=$vulnerableTag`n")
    docker --context desktop-linux build --quiet -t $vulnerableTag $imageDir|Out-Null;if($LASTEXITCODE -ne 0){throw 'Vulnerable fixture image build failed'}
    $vulnerableImage=(docker --context desktop-linux image inspect $vulnerableTag --format '{{.Id}}').Trim();if($LASTEXITCODE -ne 0 -or $vulnerableImage -notmatch '^sha256:[a-f0-9]{64}$'){throw 'Fixture immutable image ID missing'}
    Copy-Item $vulnerableJar $jar -Force
    Run-Gate $fixture 'baotin/auth-service:security-check' 'vulnerable-jar-denied' 1
    Copy-Item (Join-Path $root 'Auth-Service/build/libs/Auth-Service-0.0.1-SNAPSHOT.jar') $jar -Force
    Run-Gate $fixture $vulnerableImage 'vulnerable-image-denied' 1
    [IO.File]::WriteAllText($jar,'not a Java archive')
    Run-Gate $fixture 'baotin/auth-service:security-check' 'empty-scan-denied' 3
    Write-Output 'SECURITY_TESTS PASS cases=6 positive=2 secret=denied vulnerable_jar=denied vulnerable_image=denied empty_scan=denied'
}finally{
    docker --context desktop-linux image rm $vulnerableTag 2>$null|Out-Null
    $resolved=(Resolve-Path -LiteralPath $fixture).Path;$boundary=Join-Path $root '.changes'
    if(-not $resolved.StartsWith($boundary+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe fixture cleanup'};Remove-Item -LiteralPath $resolved -Recurse -Force
}
