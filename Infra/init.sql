CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS threat;
CREATE SCHEMA IF NOT EXISTS report;
CREATE SCHEMA IF NOT EXISTS reputation;
CREATE SCHEMA IF NOT EXISTS campaign;
CREATE SCHEMA IF NOT EXISTS notification;
CREATE SCHEMA IF NOT EXISTS search;
CREATE SCHEMA IF NOT EXISTS analytics;

-- Create roles (users) for each service
CREATE ROLE baotin_auth WITH LOGIN PASSWORD 'pass_auth';
CREATE ROLE baotin_threat WITH LOGIN PASSWORD 'pass_threat';
CREATE ROLE baotin_report WITH LOGIN PASSWORD 'pass_report';
CREATE ROLE baotin_reputation WITH LOGIN PASSWORD 'pass_reputation';
CREATE ROLE baotin_campaign WITH LOGIN PASSWORD 'pass_campaign';
CREATE ROLE baotin_notification WITH LOGIN PASSWORD 'pass_notification';
CREATE ROLE baotin_search WITH LOGIN PASSWORD 'pass_search';
CREATE ROLE baotin_analytics WITH LOGIN PASSWORD 'pass_analytics';

-- Grant privileges
GRANT ALL PRIVILEGES ON SCHEMA auth TO baotin_auth;
GRANT ALL PRIVILEGES ON SCHEMA threat TO baotin_threat;
GRANT ALL PRIVILEGES ON SCHEMA report TO baotin_report;
GRANT ALL PRIVILEGES ON SCHEMA reputation TO baotin_reputation;
GRANT ALL PRIVILEGES ON SCHEMA campaign TO baotin_campaign;
GRANT ALL PRIVILEGES ON SCHEMA notification TO baotin_notification;
GRANT ALL PRIVILEGES ON SCHEMA search TO baotin_search;
GRANT ALL PRIVILEGES ON SCHEMA analytics TO baotin_analytics;
