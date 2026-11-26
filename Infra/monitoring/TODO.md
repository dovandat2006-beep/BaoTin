# Monitoring backlog

- [x] Prometheus + Blackbox trên tuyến Windows primary,12 health/HTTPS probes.
- [x] Grafana dashboard provisioned và alert rules, fixture negative tests.
- [x] Hidden-console launcher; chỉ bật plugin Prometheus; cleanup đúng plugin con.
- [ ] Micrometer Prometheus registry và management endpoint riêng từng service;
  cần test authorization, JDK21 wrapper và release qua pipeline từng repo.
- [ ] Chọn kênh Alertmanager/email/Telegram và secret routing ngoài Git.
- [ ] Exporters hạ tầng PostgreSQL/Redis/Kafka và business metrics theo ownership.
- [ ] Profile monitoring riêng cho Docker/Kubernetes; không giả định target loopback
  của Windows áp dụng cho network/container/cluster khác.
