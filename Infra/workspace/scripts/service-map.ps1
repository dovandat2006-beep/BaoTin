# =============================================================================
#  BaoTin — Service Map (single source of truth cho CI/CD generator)
#  Mọi script sinh file CI/CD đều đọc từ đây.
# =============================================================================

# EnvKey : tên env var trong .env  (PORT_<EnvKey>, IMAGE_TAG_<EnvKey>)
# DbPass  : tên biến mật khẩu DB, phải khớp với Infra/init.sql
$script:BaoTinServices = @(
    [pscustomobject]@{ Dir='API-Gateway';           Image='api-gateway';           EnvKey='API_GATEWAY';           Port=8080; Schema=$null;       DbPass=$null
                       Description='Edge gateway: routing, rate limiting, JWT validation' },
    [pscustomobject]@{ Dir='Auth-Service';          Image='auth-service';          EnvKey='AUTH_SERVICE';          Port=8081; Schema='auth';       DbPass='DB_PASS_AUTH'
                       Description='User, login (password/Google/Facebook), RBAC, JWT/refresh' },
    [pscustomobject]@{ Dir='Threat-Service';        Image='threat-service';        EnvKey='THREAT_SERVICE';        Port=8082; Schema='threat';     DbPass='DB_PASS_THREAT'
                       Description='SMS/URL inspection orchestrator, scan history, threat target' },
    [pscustomobject]@{ Dir='Report-Service';        Image='report-service';        EnvKey='REPORT_SERVICE';        Port=8083; Schema='report';     DbPass='DB_PASS_REPORT'
                       Description='Reports, evidence, moderation, appeals' },
    [pscustomobject]@{ Dir='Reputation-Service';    Image='reputation-service';    EnvKey='REPUTATION_SERVICE';    Port=8084; Schema='reputation'; DbPass='DB_PASS_REPUTATION'
                       Description='Risk / reputation score aggregation' },
    [pscustomobject]@{ Dir='Model-Inference-Service'; Image='model-inference-service'; EnvKey='MODEL_INFERENCE_SERVICE'; Port=8085; Schema=$null;   DbPass=$null
                       Description='ONNX model inference, emits confidence signal only' },
    [pscustomobject]@{ Dir='Notification-Service';  Image='notification-service';  EnvKey='NOTIFICATION_SERVICE';  Port=8086; Schema='notification'; DbPass='DB_PASS_NOTIFICATION'
                       Description='Push / email notification and preferences' },
    [pscustomobject]@{ Dir='Search-Service';        Image='search-service';        EnvKey='SEARCH_SERVICE';        Port=8087; Schema='search';     DbPass='DB_PASS_SEARCH'
                       Description='Search and read projection (PostgreSQL FTS)' },
    [pscustomobject]@{ Dir='Campaign-Service';      Image='campaign-service';      EnvKey='CAMPAIGN_SERVICE';      Port=8088; Schema='campaign';   DbPass='DB_PASS_CAMPAIGN'
                       Description='Scam campaign detection and grouping' },
    [pscustomobject]@{ Dir='Analytics-Service';     Image='analytics-service';     EnvKey='ANALYTICS_SERVICE';     Port=8089; Schema='analytics';  DbPass='DB_PASS_ANALYTICS'
                       Description='Business statistics and aggregate metrics' }
)

# Thư mục gốc workspace
$script:BaoTinRoot = Split-Path -Parent $PSScriptRoot

# Label của Jenkins agent dùng cho build
$script:AgentLabel = 'baotin-docker'

# Nhánh được deploy tự động
$script:DeployBranch = 'main'