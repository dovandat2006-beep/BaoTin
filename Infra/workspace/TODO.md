# TODO — BảoTin team backlog

Tranche hiện tại do nhóm tích hợp giữ **API-Gateway** và **Auth-Service**. Các service dưới đây đang ở trạng thái startup scaffold; người nhận task phải đọc master plan, README, TODO và AGENTS của repo trước khi code.

| Service | Trạng thái | Backlog | Người phụ trách |
|---|---|---|---|
| Threat Service | Scaffold healthy | [`Threat-Service/TODO.md`](Threat-Service/TODO.md) | Chưa phân công |
| Model Inference Service | Scaffold healthy | [`Model-Inference-Service/TODO.md`](Model-Inference-Service/TODO.md) | Chưa phân công |
| Report Service | Scaffold healthy | [`Report-Service/TODO.md`](Report-Service/TODO.md) | Chưa phân công |
| Reputation Service | Scaffold healthy | [`Reputation-Service/TODO.md`](Reputation-Service/TODO.md) | Chưa phân công |
| Notification Service | Scaffold healthy | [`Notification-Service/TODO.md`](Notification-Service/TODO.md) | Chưa phân công |
| Search Service | Scaffold healthy | [`Search-Service/TODO.md`](Search-Service/TODO.md) | Chưa phân công |
| Campaign Service | Scaffold healthy | [`Campaign-Service/TODO.md`](Campaign-Service/TODO.md) | Chưa phân công |
| Analytics Service | Scaffold healthy | [`Analytics-Service/TODO.md`](Analytics-Service/TODO.md) | Chưa phân công |

## Thứ tự phụ thuộc khuyến nghị

1. Contracts cho endpoint/event được giao.
2. Threat + Model Inference.
3. Report + Reputation.
4. Notification + Search.
5. Campaign + Analytics.

## Quy tắc nhận task

- Ghi tên người phụ trách vào bảng và chỉ nhận một vertical slice có tiêu chí test rõ ràng.
- Không mở public route trước khi contract và authorization test được review.
- Không dùng schema, entity hoặc secret của service khác.
- PR chỉ hoàn thành khi test, health/readiness, README và TODO cùng được cập nhật.
