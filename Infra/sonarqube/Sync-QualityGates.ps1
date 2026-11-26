<#
    .SYNOPSIS
        Đồng bộ quality gate "overall code" của SonarQube cho các project BaoTin.

    .DESCRIPTION
        Gate mặc định "Sonar way" chỉ đo trên New Code. Với các project BaoTin
        (version luôn là 0.0.1-SNAPSHOT, phân tích không có dữ liệu SCM blame ổn định)
        SonarQube không dựng được tập New Code, nên mọi condition của "Sonar way" đều
        không có giá trị và gate luôn trả OK một cách vô hiệu.

        Script này tạo các gate đo trên Overall Code với ngưỡng "ratchet" — lấy đúng
        mức hiện tại làm mốc, mục đích là chặn hồi quy chứ không phải tuyên bố code đã
        đạt chuẩn. Muốn siết, sửa $GateSpecs rồi chạy lại; gate FAIL làm pipeline fail
        vì script gate phía pipeline đọc verdict từ server, không cần sửa Jenkinsfile.

        Script idempotent: chạy lại nhiều lần cho cùng một kết quả.

    .PARAMETER SonarUrl
        URL SonarQube nhìn từ máy chạy script. Mặc định http://localhost:9001; giá trị
        SONAR_HOST_URL trong properties là hostname nội bộ docker (http://sonarqube:9000)
        nên không dùng trực tiếp ở đây.

    .PARAMETER PropertiesPath
        File chứa SONAR_TOKEN. Mặc định ../jenkins/init/baotin-ci.properties.

    .EXAMPLE
        .\Sync-QualityGates.ps1
#>
[CmdletBinding()]
param(
    [string]$SonarUrl = 'http://localhost:9001',
    [string]$PropertiesPath,
    [string]$TokenFile = (Join-Path $env:USERPROFILE '.baotin/sonarqube-gate-admin.token')
)

$ErrorActionPreference = 'Stop'

$endpoint=[uri]$SonarUrl
if($endpoint.Scheme -ne 'http' -or $endpoint.DnsSafeHost -notin @('localhost','127.0.0.1','::1') -or
   $endpoint.Port -notin 9000,9001 -or $endpoint.UserInfo -or $endpoint.Query -or $endpoint.Fragment -or $endpoint.AbsolutePath -ne '/'){
    throw 'Identified local SonarQube endpoint required'
}
$status=Invoke-RestMethod ($SonarUrl.TrimEnd('/')+'/api/system/status') -NoProxy -MaximumRedirection 0 -TimeoutSec 10
if($status.status -ne 'UP' -or -not $status.version){throw 'SonarQube endpoint not ready; no credentials read'}

# --- Ngưỡng ratchet: sửa ở đây, không sửa rải rác trong Jenkinsfile ---------
$GateSpecs = [ordered]@{
    # 8 service scaffold: gần như không có code thật nên condition coverage vô nghĩa,
    # và lane release cũng không chạy test nên không có coverage để đo.
    # blocker hiện tại = 1 (S2699 trên test contextLoads) -> mốc 1.
    'baotin-scaffold' = @{
        projects   = @(
            'baotin_analytics-service'
            'baotin_campaign-service'
            'baotin_model-inference-service'
            'baotin_notification-service'
            'baotin_report-service'
            'baotin_reputation-service'
            'baotin_search-service'
            'baotin_threat-service'
        )
        conditions = @(
            @{ metric = 'bugs';               op = 'GT'; error = '0' }
            @{ metric = 'vulnerabilities';    op = 'GT'; error = '0' }
            @{ metric = 'blocker_violations'; op = 'GT'; error = '1' }
        )
    }
    # Lane release không chạy test nên phân tích CI không có coverage.
    # Mốc hiện tại: bugs 2, critical 8.
    'baotin-api-gateway' = @{
        projects   = @('baotin_api-gateway')
        conditions = @(
            @{ metric = 'bugs';                op = 'GT'; error = '2' }
            @{ metric = 'vulnerabilities';     op = 'GT'; error = '0' }
            @{ metric = 'critical_violations'; op = 'GT'; error = '8' }
        )
    }
    # Lane release không chạy test nên phân tích CI không có coverage.
    # Mốc hiện tại: bugs 6, critical 35.
    'baotin-auth-service' = @{
        projects   = @('baotin_auth-service')
        conditions = @(
            @{ metric = 'bugs';                op = 'GT'; error = '6' }
            @{ metric = 'vulnerabilities';     op = 'GT'; error = '0' }
            @{ metric = 'critical_violations'; op = 'GT'; error = '35' }
        )
    }
    # Frontend vẫn chạy test trong Jenkinsfile của nó (LCOV từ ci/build-web.sh)
    # nên giữ condition coverage. Mốc hiện tại: coverage 84.9.
    'baotin-frontend' = @{
        projects   = @('baotin_frontend')
        conditions = @(
            @{ metric = 'vulnerabilities'; op = 'GT'; error = '0' }
            @{ metric = 'bugs';            op = 'GT'; error = '0' }
        )
    }
}

# --- Token ----------------------------------------------------------------
if($PropertiesPath){
    $tokenLine = Select-String -LiteralPath $PropertiesPath -Pattern '^\s*SONAR_TOKEN\s*=' | Select-Object -First 1
    if (-not $tokenLine) { throw 'SONAR_TOKEN missing' }
    $token = ($tokenLine.Line -split '=', 2)[1].Trim()
}else{
    $workspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
    $path=(Resolve-Path -LiteralPath $TokenFile).Path
    if($path.StartsWith($workspace+'\',[StringComparison]::OrdinalIgnoreCase) -or (Get-Item $path).LinkType -or (Get-Item $path).Length -gt 1024){throw 'Bounded regular external Sonar token file required'}
    $allowed=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18','S-1-5-32-544')
    foreach($rule in (Get-Acl $path).Access){if($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin $allowed){throw 'Restrict token-file ACL'}}
    $token=[IO.File]::ReadAllText($path).Trim()
}
if($token -notmatch '^[A-Za-z0-9_-]{20,256}$'){throw 'Invalid Sonar token format'}

$headers = @{
    Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${token}:"))
}

function Invoke-Sonar {
    param([string]$Method, [string]$Path)
    $uri = $SonarUrl.TrimEnd('/') + $Path
    try {
        return Invoke-RestMethod -Uri $uri -Method $Method -Headers $headers -NoProxy -MaximumRedirection 0 -TimeoutSec 20
    } catch {
        throw 'SonarQube API operation failed; diagnostics withheld'
    }
}

$esc = { param($s) [uri]::EscapeDataString($s) }

Write-Host "SonarQube: $SonarUrl" -ForegroundColor Cyan

# --- Tạo/đồng bộ gate ------------------------------------------------------
foreach ($name in $GateSpecs.Keys) {
    $spec = $GateSpecs[$name]
    $show = $null
    try { $show = Invoke-Sonar GET "/api/qualitygates/show?name=$(& $esc $name)" } catch { $show = $null }

    if ($show) {
        $gateId = $show.id
        Write-Host "  [=] gate '$name'" -ForegroundColor DarkGray
    } else {
        $created = Invoke-Sonar POST "/api/qualitygates/create?name=$(& $esc $name)"
        $gateId = $created.id
        Write-Host "  [+] tạo gate '$name'" -ForegroundColor Green
        # Gate vừa tạo được SonarQube seed sẵn các condition "new code" của gate mặc
        # định. Phải đọc lại để vòng lặp dưới bỏ hết condition không nằm trong spec —
        # không đọc lại thì gate sẽ giữ rác new_* (vô hiệu vì không có new code).
        $show = Invoke-Sonar GET "/api/qualitygates/show?name=$(& $esc $name)"
    }

    $current = @($show.conditions)

    # Bỏ condition không còn trong danh sách mong muốn (so theo metric).
    foreach ($c in $current) {
        if (-not ($spec.conditions | Where-Object { $_.metric -eq $c.metric })) {
            Invoke-Sonar POST "/api/qualitygates/delete_condition?id=$($c.id)" | Out-Null
            Write-Host "      [-] bỏ $($c.metric)" -ForegroundColor Yellow
        }
    }

    foreach ($want in $spec.conditions) {
        $have = $current | Where-Object { $_.metric -eq $want.metric } | Select-Object -First 1
        if (-not $have) {
            Invoke-Sonar POST "/api/qualitygates/create_condition?gateId=$gateId&metric=$($want.metric)&op=$($want.op)&error=$($want.error)" | Out-Null
            Write-Host "      [+] $($want.metric) $($want.op) $($want.error)"
        } elseif ($have.op -ne $want.op -or [string]$have.error -ne [string]$want.error) {
            Invoke-Sonar POST "/api/qualitygates/update_condition?id=$($have.id)&metric=$($want.metric)&op=$($want.op)&error=$($want.error)" | Out-Null
            Write-Host "      [~] $($want.metric): $($have.op) $([string]$have.error) -> $($want.op) $($want.error)" -ForegroundColor Yellow
        } else {
            Write-Host "      [=] $($want.metric) $($want.op) $($want.error)" -ForegroundColor DarkGray
        }
    }

    foreach ($project in $spec.projects) {
        Invoke-Sonar POST "/api/qualitygates/select?gateId=$gateId&projectKey=$(& $esc $project)" | Out-Null
    }
}

# --- Kiểm chứng ------------------------------------------------------------
Write-Host "`n--- Kiểm chứng gán gate ---" -ForegroundColor Cyan
$mismatch = 0
$total = 0
foreach ($name in $GateSpecs.Keys) {
    $actualConditions=@((Invoke-Sonar GET "/api/qualitygates/show?name=$(& $esc $name)").conditions)
    if($actualConditions.Count -ne $GateSpecs[$name].conditions.Count){throw 'Gate condition count mismatch'}
    foreach($want in $GateSpecs[$name].conditions){
        $matching=@($actualConditions|Where-Object {$_.metric -eq $want.metric -and $_.op -eq $want.op -and [string]$_.error -eq [string]$want.error})
        if($matching.Count -ne 1){throw 'Gate threshold/metric mismatch'}
    }
    foreach ($project in $GateSpecs[$name].projects) {
        $total++
        $actual = (Invoke-Sonar GET "/api/qualitygates/get_by_project?project=$(& $esc $project)").qualityGate.name
        if ($actual -ne $name) {
            Write-Host "  [x] $project -> '$actual' (mong đợi '$name')" -ForegroundColor Red
            $mismatch++
        } else {
            Write-Host "  [v] $project -> $actual" -ForegroundColor Green
        }
    }
}

if ($mismatch -gt 0) { throw "$mismatch project gán sai gate" }
Write-Host "`nSONAR_GATES_SYNCED projects=$total gates=$($GateSpecs.Count)" -ForegroundColor Green
