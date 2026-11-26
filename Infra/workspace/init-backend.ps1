$services = @(
    @{ name = "Auth-Service"; pkg = "auth"; deps = "web,data-jpa,postgresql,flyway,validation,actuator,lombok,kafka,data-redis" },
    @{ name = "Threat-Service"; pkg = "threat"; deps = "web,data-jpa,postgresql,flyway,validation,actuator,lombok,kafka,data-redis" },
    @{ name = "Report-Service"; pkg = "report"; deps = "web,data-jpa,postgresql,flyway,validation,actuator,lombok,kafka,data-redis" },
    @{ name = "Reputation-Service"; pkg = "reputation"; deps = "web,data-jpa,postgresql,flyway,validation,actuator,lombok,kafka,data-redis" },
    @{ name = "Model-Inference-Service"; pkg = "modelinference"; deps = "web,data-jpa,postgresql,flyway,validation,actuator,lombok,kafka,data-redis" },
    @{ name = "Notification-Service"; pkg = "notification"; deps = "web,data-jpa,postgresql,flyway,validation,actuator,lombok,kafka,data-redis" },
    @{ name = "Search-Service"; pkg = "search"; deps = "web,data-jpa,postgresql,flyway,validation,actuator,lombok,kafka,data-redis" },
    @{ name = "Campaign-Service"; pkg = "campaign"; deps = "web,data-jpa,postgresql,flyway,validation,actuator,lombok,kafka,data-redis" },
    @{ name = "Analytics-Service"; pkg = "analytics"; deps = "web,data-jpa,postgresql,flyway,validation,actuator,lombok,kafka,data-redis" },
    @{ name = "API-Gateway"; pkg = "gateway"; deps = "cloud-gateway,actuator,lombok,data-redis-reactive" }
)

foreach ($svc in $services) {
    $name = $svc.name
    $pkg = $svc.pkg
    $deps = $svc.deps
    $url = "https://start.spring.io/starter.zip?type=gradle-project&language=java&baseDir=$name&groupId=com.baotin&artifactId=$name&name=$name&description=BaoTin+$name&packageName=com.baotin.$pkg&packaging=jar&javaVersion=21&dependencies=$deps"
    
    Write-Host "Downloading $name..."
    Invoke-WebRequest -Uri $url -OutFile "$name.zip"
    Write-Host "Extracting $name..."
    Expand-Archive -Path "$name.zip" -DestinationPath "." -Force
    Remove-Item -Path "$name.zip" -Force
}

# Create other non-Spring Boot directories
$dirs = @("Contracts", "Infra", "AI", "Frontend")
foreach ($dir in $dirs) {
    if (-not (Test-Path -Path $dir)) {
        New-Item -ItemType Directory -Path $dir | Out-Null
    }
}

Write-Host "Initialization completed."
