<#
    .SYNOPSIS
        Nạp cấu hình BaoTin CI vào container jenkins-blueocean đang chạy.

    .DESCRIPTION
        Copy script khởi tạo vào /var/jenkins_home/init.groovy.d/ rồi restart
        Jenkins. Không tạo container mới, không đụng volume hiện có.

        Script trong init.groovy.d chạy mỗi lần Jenkins khởi động với quyền
        SYSTEM nên đặt label, credentials và tạo job mà không cần API token.

    .EXAMPLE
        .\apply-config.ps1                    # copy cấu hình + restart
        .\apply-config.ps1 -NoRestart         # chỉ copy, chưa restart
        .\apply-config.ps1 -CreateProperties  # tạo file properties từ mẫu
#>
[CmdletBinding()]
param(
    [switch]$NoRestart,
    [switch]$CreateProperties
)

$ErrorActionPreference = 'Stop'

$Container   = 'jenkins-blueocean'
# Đường dẫn tuyệt đối theo vị trí file này, không phụ thuộc thư mục đang gọi
$ScriptDir   = Split-Path -Parent $PSCommandPath
$InitDir     = Join-Path $ScriptDir 'init'
$GroovySrc   = Join-Path $InitDir 'baotin-ci.groovy'
$PropsSrc    = Join-Path $InitDir 'baotin-ci.properties.example'
$PropsActual = Join-Path $InitDir 'baotin-ci.properties'

if (-not (docker ps --format '{{.Names}}' | Select-String -SimpleMatch $Container)) {
    throw "Container '$Container' không chạy. Khởi động trước bằng container cũ của bạn."
}

function Assert-LastOk {
    param([string]$What)
    if ($LASTEXITCODE -ne 0) { throw "$What thất bại (exit $LASTEXITCODE)" }
}

# --- 1. Tạo file properties nếu chưa có ------------------------------------
if ($CreateProperties -or -not (Test-Path $PropsActual)) {
    if ($CreateProperties -and (Test-Path $PropsActual)) {
        $backup = "$PropsActual.bak"
        Copy-Item $PropsActual $backup
        Write-Host "Đã backup: $backup" -ForegroundColor DarkGray
    }
    Copy-Item $PropsSrc $PropsActual
    Write-Host "  [+] tạo $PropsActual" -ForegroundColor Green
    Write-Host "      -> điền GITHUB_TOKEN và DEPLOY_SSH_KEY rồi chạy lại script này" -ForegroundColor Yellow
}

# --- 2. Copy vào container -------------------------------------------------
docker exec -u root $Container mkdir -p /var/jenkins_home/init.groovy.d | Out-Null
Assert-LastOk 'Tạo thư mục init.groovy.d'

docker cp $GroovySrc "${Container}:/var/jenkins_home/init.groovy.d/baotin-ci.groovy" | Out-Null
Assert-LastOk 'Copy script khởi tạo'
Write-Host '  [+] copy baotin-ci.groovy' -ForegroundColor Green

if (Test-Path $PropsActual) {
    docker cp $PropsActual "${Container}:/var/jenkins_home/baotin-ci.properties" | Out-Null
    Assert-LastOk 'Copy properties'
    Write-Host '  [+] copy baotin-ci.properties' -ForegroundColor Green
}

# --- 3. Restart -------------------------------------------------------------
if ($NoRestart) {
    Write-Host "`nĐã copy. Chạy 'docker restart $Container' để áp dụng." -ForegroundColor Yellow
    return
}

Write-Host "`nĐang restart $Container..." -ForegroundColor Cyan
# Mốc thời gian ngay trước khi restart để chỉ đọc log của lần khởi động này
$Since = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss") + 'Z'
docker restart $Container | Out-Null

Write-Host 'Chờ Jenkins khởi động' -ForegroundColor DarkGray
$code = '000'
for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Seconds 3
    $code = (curl.exe -s -o NUL -w '%{http_code}' http://localhost:8080/login 2>$null)
    if ($code -eq '200') { break }
}
if ($code -ne '200') {
    Write-Host "CẢNH BÁO: Jenkins chưa trả về HTTP 200 (nhận được $code)." -ForegroundColor Red
    Write-Host "Xem log: docker logs --tail 200 $Container" -ForegroundColor Red
}

# --- 4. In kết quả ----------------------------------------------------------
Write-Host "`n--- Kết quả cấu hình ---" -ForegroundColor Cyan
docker logs --since $Since $Container 2>&1 |
    Select-String -Pattern '\[baotin-ci\]' |
    ForEach-Object { $_.Line -replace '.*\[baotin-ci\]\s*', '  ' }

$jobs = docker exec $Container sh -c "ls -d /var/jenkins_home/jobs/baotin/jobs/*/ 2>/dev/null | wc -l" 2>$null
Write-Host "`nSố job trong folder 'baotin': $jobs" -ForegroundColor Cyan
Write-Host "Mở: http://localhost:8080/job/baotin/" -ForegroundColor Cyan