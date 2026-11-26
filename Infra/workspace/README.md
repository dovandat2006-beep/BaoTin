# BảoTin — Backend System

![Architecture](https://img.shields.io/badge/Architecture-Microservices-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen)
![Java](https://img.shields.io/badge/Java-21-orange)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15%2B-blue)
![Kafka](https://img.shields.io/badge/Kafka-Event%20Driven-red)

> **Hệ thống đánh giá và cảnh báo rủi ro lừa đảo trực tuyến (Scam/Phishing).**

Đây là hệ thống backend của BảoTin, được thiết kế theo kiến trúc Microservices sử dụng Spring Boot, Kafka, PostgreSQL và Redis. Dự án được chia thành nhiều service độc lập để đảm bảo khả năng mở rộng và dễ dàng bảo trì.

## 🏗 Cấu trúc Multi-Repository

Hệ thống được tổ chức thành các thư mục độc lập (hoạt động như các repository riêng biệt) trong cùng một workspace:

### 🌟 Core Services (Spring Boot)
* `API-Gateway`: Cổng giao tiếp duy nhất (Single entry point) cho Web/Mobile, routing, rate limiting và JWT validation tại edge.
* `Auth-Service`: Quản lý người dùng, login (Mật khẩu, Google, Facebook), RBAC, phân quyền và JWT/Refresh session.
* `Threat-Service`: Service trung tâm điều phối kiểm tra SMS/URL, quản lý Scan History, Threat Target, chuẩn hóa dữ liệu.
* `Report-Service`: Quản lý hệ thống báo cáo (Report), bằng chứng (Evidence), kiểm duyệt (Moderation) và kháng cáo.
* `Reputation-Service`: Đánh giá rủi ro, tính toán Risk Score / Reputation Score dựa trên báo cáo cộng đồng và AI signals.
* `Model-Inference-Service`: Tích hợp mô hình AI (ONNX) để phát hiện lừa đảo (chỉ đóng vai trò cung cấp signal).
* `Notification-Service`: Quản lý thông báo Push/Email, preferences.
* `Search-Service`: Xử lý tìm kiếm và read projection (sử dụng PostgreSQL Full Text Search).
* `Campaign-Service`: Phát hiện và nhóm các chiến dịch lừa đảo.
* `Analytics-Service`: Thống kê, aggregate metrics nghiệp vụ.

### 🛠 Các thành phần khác
* `AI`: Nơi chứa code training, evaluation, tokenizer và xuất model ONNX.
* `Contracts`: Chứa OpenAPI specs, Event Schemas (AsyncAPI) dùng chung giữa các service.
* `Infra`: Chứa các script Docker Compose (PostgreSQL, Kafka, Redis, MinIO) phục vụ local development.
* `Frontend`: Source code Expo Web + React Native cho Web, Android và iOS.

## 🚀 Công nghệ sử dụng

* **Ngôn ngữ & Framework:** Java 21, Spring Boot 3.x, Spring Cloud Gateway.
* **Cơ sở dữ liệu:** PostgreSQL (1 database vật lý duy nhất, mỗi service sở hữu một schema riêng biệt `auth.*`, `threat.*`...). Quản lý migration bằng Flyway.
* **Message Broker / Event-Driven:** Apache Kafka. Dùng cho giao tiếp bất đồng bộ giữa các service.
* **Caching:** Redis.
* **Storage:** MinIO (S3-compatible) lưu trữ bằng chứng (Evidence) cho Report.
* **AI Runtime:** ONNX Runtime chạy trực tiếp trong Model-Inference-Service.
* **Build Tool:** Gradle.

## 💡 Thiết kế Kiến trúc Đặc Biệt

1. **One Database - Multi Schemas:** Thay vì vận hành 10 database rời rạc, dự án dùng chung 1 PostgreSQL vật lý (`baotin`). Tuy nhiên, mỗi service có một schema riêng (`auth`, `report`, `threat`...) và **không** cho phép JOIN xuyên schema hay khóa ngoại. ID được tham chiếu dưới dạng Logical UUID. 
2. **Transactional Outbox / Inbox Pattern:** Mọi thao tác cần cập nhật DB và gửi Kafka Message đều phải thông qua Outbox table trong cùng 1 database transaction để đảm bảo tính nhất quán (Consistency). Các consumer dùng Inbox pattern để xử lý duplicate (Idempotency).
3. **AI as a Signal, Not Decision Maker:** Model AI không quyết định một report có lừa đảo hay không, nó chỉ phân tích nội dung (SMS, URL) và cung cấp mức độ tự tin (Confidence score) cho `Reputation-Service` xử lý tổng hợp.

## 🚦 Bắt đầu (Getting Started)

### Yêu cầu hệ thống
* JDK 21+
* Docker & Docker Compose
* Gradle (hoặc dùng `./gradlew` tích hợp sẵn trong mỗi thư mục)

### Hướng dẫn chạy local
1. Khởi động hạ tầng (PostgreSQL, Kafka, Redis, MinIO) bằng Docker Compose (Trong thư mục `Infra`).
2. Build và chạy các service theo thứ tự:
   * Chạy `Auth-Service`, `Threat-Service`, v.v... qua lệnh:
     ```bash
     cd Auth-Service
     ./gradlew bootRun
     ```
   * Chạy `API-Gateway` cuối cùng để nhận requests.

## 📜 Quy ước chung (Convention)
* Toàn bộ các ID Public giữa các service đều sử dụng **UUID**.
* API trả về định dạng thời gian **ISO-8601 UTC** (`Instant` trong Java).
* Event Driven chỉ sử dụng cho Domain Event (Ví dụ: `ReportCreated`, `RiskScoreUpdated`). Tra cứu trực tiếp vẫn phải dùng REST nội bộ hoặc thông qua API-Gateway.

## 🔄 CI/CD

Mỗi service có pipeline riêng: GitHub Actions (`.github/workflows/ci-cd.yml`) và
Jenkins (`Jenkinsfile`). Cả hai đều build bằng Gradle, đẩy image lên `ghcr.io` rồi
deploy qua SSH bằng `Infra/deploy/deploy.sh`.

* Hướng dẫn đầy đủ: **[CI-CD.md](CI-CD.md)**
* Riêng phần Jenkins: **[Infra/jenkins/README.md](Infra/jenkins/README.md)**
