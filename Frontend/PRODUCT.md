# Product

<!-- impeccable:product-schema 1 -->

## Platform

**Adaptive, cross-platform.** One Expo React Native codebase targets Web, Android, and iOS. The product must adapt navigation, density, input methods, and available space to each platform while preserving the same information architecture and business meaning.

## Users

Người dùng phổ thông tại Việt Nam cần kiểm tra thông tin đáng ngờ và tố giác lừa đảo. Họ có thể đang dùng web, Android hoặc iOS; thường không có kiến thức an ninh mạng chuyên sâu và cần hiểu kết quả bằng ngôn ngữ đời thường.

Primary user situations:

- Vừa nhận SMS, đường dẫn, email hoặc thông tin liên hệ đáng ngờ.
- Đang cân nhắc chuyển tiền, cung cấp OTP, đăng nhập hoặc gọi lại một số điện thoại.
- Muốn kiểm tra một cá nhân, tài khoản hoặc chiến dịch đã được cộng đồng ghi nhận hay chưa.
- Đã gửi tố giác và muốn biết hồ sơ đang ở trạng thái nào.
- Muốn theo dõi khi mức rủi ro của một đối tượng thay đổi.

Do not assume the user knows terms such as phishing, spoofing, domain reputation, model confidence, or threat intelligence. Explain the consequence and next safe action in plain Vietnamese.

## Product Purpose

BaoTin giúp người dùng:

1. Kiểm tra URL và nội dung SMS đáng ngờ.
2. Tra cứu thông tin đã được công khai hoặc ghi nhận về tên miền, số điện thoại, tài khoản ngân hàng, email, tài khoản mạng xã hội, từ khóa và chiến dịch liên quan.
3. Hiểu mức rủi ro, lý do chính và hành động nên thực hiện tiếp theo.
4. Xem lịch sử kiểm tra, phân biệt kết quả tại thời điểm kiểm tra với mức rủi ro hiện tại.
5. Gửi tố giác kèm bằng chứng và theo dõi tiến trình xử lý.
6. Theo dõi thay đổi rủi ro của đối tượng khi tính năng và dữ liệu tương ứng được hỗ trợ.

BaoTin hỗ trợ quyết định an toàn hơn; ứng dụng không thay thế ngân hàng, cơ quan thực thi pháp luật, nhà mạng hoặc chuyên gia an ninh mạng.

## Positioning

BaoTin kết hợp:

- **Quy tắc:** các điều kiện và dấu hiệu có thể kiểm tra theo logic xác định.
- **Tín hiệu AI:** hỗ trợ phân tích URL hoặc nội dung SMS trong phạm vi model thực sự được tích hợp.
- **Dữ liệu uy tín và cộng đồng:** thông tin tra cứu, tố giác và kết quả xác minh được hệ thống ghi nhận.

AI là một nguồn tín hiệu hỗ trợ, không phải cơ quan xác minh và không tự quyết định trạng thái tố giác. Không trình bày dự đoán của AI như sự thật đã được xác minh.

## Operating Context

Người dùng thường mở BaoTin ngay sau khi nhận được một cuộc gọi, tin nhắn, đường dẫn hoặc yêu cầu chuyển tiền đáng ngờ. Tác vụ kiểm tra phải dễ tìm, dễ hiểu và hoàn thành nhanh trên điện thoại; không yêu cầu người dùng đi qua dashboard, biểu đồ hoặc nhiều bước không cần thiết.

Product priorities, in order:

1. Giúp người dùng bắt đầu kiểm tra ngay.
2. Trình bày kết quả dễ hiểu và không gây hiểu lầm.
3. Đưa ra hành động tiếp theo có ích.
4. Cho phép tra cứu, xem lịch sử, tố giác và theo dõi mà không làm lu mờ tác vụ kiểm tra.
5. Cung cấp quy trình kiểm duyệt rõ ràng cho người có quyền quản trị.

## Product Model and Terminology

Use these concepts consistently across the product. Do not treat them as interchangeable:

| Concept | Meaning | Product behavior |
|---|---|---|
| **Kiểm tra (Scan)** | Phân tích trực tiếp nội dung người dùng nhập. | Hiện chỉ cung cấp luồng URL và SMS theo phạm vi model/API hiện có. |
| **Tra cứu (Lookup)** | Tìm thông tin đã có trong dữ liệu BaoTin về một đối tượng. | Hỗ trợ các loại đối tượng mà public API contract cung cấp, chẳng hạn tên miền, số điện thoại, tài khoản ngân hàng, email, tài khoản mạng xã hội hoặc chiến dịch. |
| **Kết quả (Finding)** | Thông tin hệ thống trả về sau kiểm tra hoặc tra cứu. | Có nguồn, thời điểm và mức độ chắc chắn phù hợp; không tự nâng thành kết luận xác minh. |
| **Tố giác (Report)** | Hồ sơ do người dùng gửi để phản ánh một đối tượng hoặc hành vi đáng ngờ. | Có bằng chứng và trạng thái xử lý theo state machine của backend. |
| **Bằng chứng (Evidence)** | Tệp hoặc thông tin người dùng đính kèm cho tố giác. | Luồng thật hỗ trợ tối đa 5 tệp JPG/PNG/WEBP/PDF, mỗi tệp tối đa 10 MB; tải lên sau khi tạo báo cáo, theo quyền và trạng thái của contract. |
| **Lịch sử (History)** | Các lần kiểm tra trước đây của người dùng. | Giữ snapshot kết quả tại thời điểm kiểm tra và hiển thị riêng mức rủi ro hiện tại nếu có dữ liệu mới. |
| **Theo dõi (Monitoring)** | Theo dõi thay đổi của một đối tượng theo thời gian. | Chỉ hiển thị trạng thái, lần kiểm tra gần nhất và thông báo khi backend/API thực sự hỗ trợ. |
| **Kiểm duyệt (Review)** | Quy trình xử lý tố giác dành cho người có quyền. | Hiển thị trạng thái và thao tác đúng theo quyền và state machine được định nghĩa trong contract. |

### Scan is not lookup

A scan analyzes submitted content. A lookup searches information already known to BaoTin. Do not add scan tabs for phone numbers, bank accounts, email addresses, or social accounts merely because those object types exist in lookup. Do not imply that the URL/SMS AI models classify every lookup category.

## Capabilities and Constraints

### 1. Check suspicious content

- Supported scan types in the current frontend scope: **URL** and **SMS/message content**.
- The user supplies the content and explicitly starts the check.
- The result should identify the content type, risk state, concise reasons, and recommended next action when those fields are returned by the API.
- SMS analysis is a **temporary result**. It must not create or imply a durable reputation profile for the phone number, sender, or message author.
- Do not claim to inspect the entire device, inbox, browser, call history, or clipboard automatically.
- Do not imply that a URL was visited or that a message was delivered to a remote service unless the implementation and contract say so.

### 2. Look up a suspicious entity

- The lookup experience may cover domains/URLs, phone numbers, bank accounts, email addresses, social accounts, keywords, and campaigns where supported by the public API Gateway contract.
- Search results must distinguish a known record from an empty search result. “Không tìm thấy dữ liệu” does not mean “an toàn”.
- Filters and category labels must reflect supported API values. Do not create categories or risk filters that return no meaningful data.
- Keep the original query visible in the result context, with sensitive values masked where appropriate.

### 3. Explain risk responsibly

- Show the risk label in plain Vietnamese and pair it with the main reasons and a useful action.
- Risk levels and their canonical values must come from the existing API contract. Human-readable labels may be localized in the frontend without changing backend enums.
- If the API provides no result, conflicting signals, stale data, or insufficient evidence, communicate that limitation explicitly.
- Never claim “an toàn tuyệt đối”, “đã xác minh” or “lừa đảo chắc chắn” unless the corresponding authoritative state is explicitly present in the contract.
- Do not invent confidence percentages, AI accuracy, threat counts, source counts, report totals, or verification badges.
- Do not use color as the only way to communicate risk; always include text and, where useful, a short explanation.

### 4. Scan history

- History records represent completed scan events, not a mutable list of current entity reputations.
- Preserve the **snapshot at scan time**: content type, scan time, submitted subject (masked where appropriate), and the result returned at that time.
- When newer reputation data exists, show **current risk** as a separate field or section. Never overwrite the historical snapshot with the latest state.
- A history item must not silently change its original result when an entity's current risk changes.
- Empty, loading, unavailable, and failed history states must be distinguishable.

### 5. Reports and evidence

- Users can submit a report about a suspicious entity or incident, with a description and supporting evidence where supported.
- The live report workflow supports a maximum of **5 evidence files**, JPG/PNG/WEBP/PDF, each from 1 byte to 10 MB. Keep the limits visible before adding files.
- Live evidence upload, authenticated download and removal use the Gateway through `src/api/reports.ts`; `ReportWorkspace` adds evidence after successful report creation. Preserve these working operations and their status/permission checks.
- Only the explicit demo `ReportsScreen` / `ReportComposer` simulates attachment counts. Its local drafts and simulated files are not server submissions.
- Report creation and review must follow the existing state machine and public API contracts. Do not invent status values, transitions, approval rules, or reviewer permissions.
- A report submitted by a user is an allegation, not a verified threat. Only the authoritative review workflow can change its verification state.
- Show the current report status, relevant explanation, and next action available to the current user. Do not expose reviewer-only actions to ordinary users.

### 6. Monitoring

- Users can follow risk changes for supported entities.
- Monitoring must communicate what is being followed, its current known state, and the last successful update time when provided.
- Do not claim continuous or real-time monitoring when the implementation uses periodic checks or when update timing is unknown.
- Do not fabricate notifications, change events, monitored totals, or last-checked timestamps.
- Follow/unfollow, notification preferences, and delivery behavior must use existing contracts and permissions. Do not create frontend-only promises of background monitoring.

### 7. Administration and review

- Administrative screens are for operational work, not a second analytics dashboard.
- Prioritize the review queue, report details, evidence, status, decision rationale, and available next action.
- Statistics must be sourced from API data and clearly indicate loading, stale, unavailable, or failed states.
- Do not show placeholder totals as live operational metrics.
- Respect role and permission information from the existing auth/API layer. Do not infer privileges from a route being visible.

## Primary User Journeys

### Journey A — Check a suspicious URL or SMS

1. User opens BaoTin and immediately sees the check task.
2. User selects URL or SMS, then enters or pastes the content.
3. User starts the check and receives a clear loading state that prevents accidental duplicate submission where appropriate.
4. The result explains the risk label, the main evidence/reasons returned, and the next recommended action.
5. The completed scan can appear in history according to the existing API behavior.

Success means the user understands what was checked, what the result does and does not prove, and what to do next. Do not add an AI chat step to this journey.

### Journey B — Look up an entity

1. User enters an identifier or keyword.
2. User chooses or filters the result category only when the contract supports it.
3. BaoTin shows matching public/community information with risk, source context, and timestamps when available.
4. If no record is found, the interface says that no matching information was found; it must not label the entity safe.
5. User can continue to a report or monitoring action only when that action is supported for the selected entity.

### Journey C — Submit a report

1. User chooses the relevant entity or enters the details required by the report contract.
2. User provides the target, category, title and description required by the live report form.
3. The interface validates required fields and explains that evidence is added after creation.
4. User reviews the information and submits once; creation uses an idempotency key.
5. BaoTin confirms the service-returned report and actual state, then permits up to 5 evidence uploads when the report is editable. File limits are shown before upload. The demo wizard remains a separate local-only flow.

Do not show a success confirmation before the mock/API operation returns success. Prevent accidental duplicate submissions through an explicit pending state and the idempotency behavior, if any, defined by the contract.

### Journey D — Follow a report or risk change

1. User opens the relevant report or monitored entity.
2. BaoTin shows the latest state and the timestamp supplied by the service.
3. The user can see what changed only when the service provides a change record or comparable data.
4. If an update cannot be retrieved, say so and preserve the last known information with its timestamp.

### Journey E — Review reports as an administrator

1. Authorized reviewer opens the report queue.
2. Reviewer filters or opens a report using supported states and fields.
3. Reviewer inspects the report description and available evidence.
4. Reviewer takes only an allowed state-machine action and records a reason when required by the contract.
5. BaoTin displays the resulting state returned by the service.

## Product Principles

1. **Task before dashboard.** The most common and urgent action must be visible without navigating through secondary content.
2. **Evidence before assertion.** A risk label is never the entire result; show why it was assigned.
3. **Actionable, not alarmist.** Tell users what to do next without panic language or exaggerated warnings.
4. **Honest uncertainty.** Unknown, unavailable, stale, and not-found are distinct states; none means safe.
5. **AI is assistive.** AI output is a signal, not a final authority or report-verification mechanism.
6. **History is immutable context.** Preserve the result as it was when the user ran the check.
7. **Progress is traceable.** Reports and monitoring show real states and real timestamps.
8. **Privacy by default.** Avoid unnecessary exposure of message text, phone numbers, account numbers, email addresses, and evidence.
9. **Consistency without forcing sameness.** Keep information architecture and meaning consistent while adapting controls and navigation to each platform.
10. **Function over decoration.** A component must help users understand, decide, navigate, or act.

## Frontend Architecture and Integration Constraints

### Application boundary

- Use one Expo React Native codebase for Web, Android, and iOS.
- Normal runs use **live public API Gateway adapters**. `src/lib/apiConfig.ts` defaults to the public HTTPS Gateway when no origin is supplied; errors never authorize a switch to demo data.
- Preserve the existing **typed API adapters** in `src/api/`: `baotin.ts`, `auth.ts`, `account.ts`, `reports.ts` and `analytics.ts`, with shared transport in `client.ts`. Do not replace connected features with mock implementations.
- Keep presentation components independent from transport details. Screens should consume typed application-level data/actions rather than calling `fetch` or constructing endpoints directly.
- Keep mock and live sources explicitly separated. Shared interfaces are a design goal where applicable, not a claim that all current demo factories implement `BaoTinApi`: several use UI-shaped records, and live reports have their own `ReportApi`. Preserve current typed boundaries during redesign.
- Keep request/response mapping and contract-specific enum handling inside the adapter layer.

### Implementation inventory — source inspection, 2026-11-12

“Live” below means wired to real Gateway requests in this checkout, not proof that a particular deployment, provider or physical device has passed acceptance.

| Capability | Implemented live path | Demo / limitations |
|---|---|---|
| URL and SMS checking | `ScanScreen` → `createBaoTinApi().scan`; POST `/api/v1/scans/url` or `/text`; `presentScan` renders server snapshots, rules and degraded-AI warnings. Guests can check. | `createMockScanResult` in `src/lib/risk.ts` is not wired into `ScanScreen`. Even explicit demo mode sends scan requests to the same-origin API; it is not a completely offline app. Additional legacy `ScanType` values are not supported scan modes. |
| Public lookup | `LiveSearchScreen` → anonymous Gateway search; query, risk/type filters and pagination. Current filters are URL, PHONE, BANK_ACCOUNT, EMAIL, CAMPAIGN and OTHER; optional masked message `content` is rendered. | `SearchScreen` uses `filterMockSearch` only in demo. A visible filter or transport fixture does not prove deployed coverage. No dedicated social-account filter exists. |
| Personal history | `ScanHistoryScreen` → authenticated scan history, filters, pagination, snapshot expansion, individual deletion and confirmed delete-all. Masked message previews come from the server. | `HistoryScreen` is not mounted by `AppShell`; its snapshot/current-risk comparison is not the current live view. `src/api/mock/history.ts` and the source-selection helper do not replace the live screen. A demo identity gets no server history. |
| Reports, evidence and review | `ReportWorkspace` → `ReportApi`; URL or MESSAGE_TEMPLATE targets, creation/detail, uploads/downloads/removal, resubmission and versioned moderation transitions. | `ReportsScreen`, `ReportComposer` and `src/data/mock.ts` supply explicit local demo drafts and simulated evidence counts only. Their broader target/category choices do not extend live contracts. |
| Watchlist and notifications | `AppShell` → `BaoTinApi`; URL add/remove, risk data, preferences, notification list/unread count/read/delete. Web polls notifications every 30 seconds while visible; browser permission enables local notifications. | Demo uses local arrays/preferences. Notification activation opens the reports/watchlist section, not a guaranteed record-detail deep link. Automatic FCM provisioning and closed-app/native push delivery remain separate acceptance work. |
| Account and authentication | `AuthScreen`, `AuthRecoveryPanel`, `AccountWorkspace` and nested panels → Auth/Account adapters: password login/register, MFA, recovery, profile, sessions, provider login/linking and device token registration/revocation. Web refresh uses HttpOnly cookies/CSRF; native refresh uses SecureStore; access tokens stay in memory. | Demo role selection is local simulation, never backend authorization. Google/Facebook provider consent, autofill and physical-device behavior require their own acceptance. Device registration takes an externally provisioned FCM token. |
| Administration | ADMIN-only `AdminWorkspace`; Users uses `AccountApi` with step-up. Its normal Statistics branch uses `AdminModerationScreen` and `ReportApi` for five status counts and an actionable `ReportWorkspace` queue. MODERATOR reviews under Reports. | `AdminDashboardScreen` has a real Analytics adapter branch when no Report API is passed, plus an explicit demo branch. Do not describe the normal report queue as an Analytics projection or replace API failures with sample metrics. |

Sources: `App.tsx`, the named components in `src/screens/`, `src/api/contracts.ts`, `src/api/reports.ts`, `src/api/history.ts`, `src/lib/browserPush.ts`, and `src/api/mock/`.

**Risk semantics:** `src/lib/searchVerdict.ts` currently maps content warnings and optional scores to “Có rủi ro”, “Không có rủi ro”, or “Chưa có đánh giá”. A suspicious warning or scored non-LOW level takes precedence over a benign label; absent those, BENIGN yields the second label, positive scored values yield the first, zero yields the second, and missing assessment yields the third. Preserve this existing mapping during visual work. These lookup labels are not report verification or guarantees of safety; the cautious-copy principles above remain the product direction, and any semantic change needs an explicit task.

**Explicit demo switch:** `EXPO_PUBLIC_DEMO_MODE=1` requires no simultaneous Gateway origin. `AppShell` selects demo search/reports/admin and local watchlist/notification data only in that mode. `src/api/mock/` contains test factories, not a universal runtime replacement for every adapter. Never downgrade a live branch because demo data is easier to render.

### Protected boundaries

The following are **out of scope** for the frontend work described by this product context:

- Backend services and their business logic.
- Public API Gateway contracts and shared contract definitions.
- Infrastructure, deployment, database, and service configuration.
- Authentication and authorization implementation changes.
- Internationalization implementation changes.

Do not modify backend service code, Contracts, or Infra to make a UI easier to implement. Do not silently change an endpoint, payload, enum, permission, or state transition. When the UI needs information that the contract does not provide, handle the limitation honestly and record the gap rather than fabricating a field.

### Mock data rules

- Use deterministic, clearly identified fixtures for development and visual testing.
- Keep mock behavior realistic: loading, success, empty, validation, timeout, and service-error paths should be testable.
- Never mix mock records with live records without a clear environment boundary.
- Never display mock statistics, fake verification, or simulated uploads as real production data.
- Where a mock implements a live adapter interface, preserve its types and semantic meaning. Existing UI-shaped demo fixtures must remain labelled and separate; do not cast them into live records or broaden live capabilities from demo enums.

## Cross-platform Experience

### Web

- Support pointer, keyboard, and screen-reader use.
- Use responsive navigation and content width; do not simply stretch a mobile screen to desktop width.
- Important actions must not depend on hover.
- Preserve browser conventions for links, focus, back navigation, and external destinations.

### Android and iOS

- Optimize the primary check journey for one-handed use and on-screen keyboards.
- Respect safe areas, system bars, keyboard insets, platform back behavior, and text scaling.
- Use touch-friendly controls and platform-appropriate interaction patterns without changing product terminology or state meaning.
- Avoid web-only hover affordances and tiny dense filter controls.

### Shared behavior

- Keep the same core navigation destinations, risk meaning, report state, and data semantics across platforms.
- Adapt layout and control placement to available space; do not create unrelated web and mobile products.
- Preserve user input after recoverable failures where safe and technically possible.
- Make loading, empty, success, partial-data, offline/unavailable, validation, and error states explicit.

## Accessibility and Inclusion

Target **WCAG 2.2 AA** where applicable, while preserving native platform accessibility conventions.

- Full keyboard navigation and visible focus on Web.
- Screen-reader labels and logical reading order for all platforms.
- Minimum touch target: **44 × 44 CSS px/pt**, with larger targets preferred for primary mobile actions.
- Text and meaningful UI boundaries must have sufficient contrast in light and dark themes.
- Do not use color alone to communicate risk, selection, status, or validation.
- Support dynamic text sizing and avoid clipping Vietnamese diacritics or long labels.
- Respect reduced-motion preferences; motion must not be required to understand state changes.
- Associate field labels, helper text, and validation errors programmatically.

## Content and Language

- Primary product language: Vietnamese.
- Use natural, direct, neutral Vietnamese appropriate for a general audience in Vietnam.
- Prefer familiar actions such as “Kiểm tra”, “Tra cứu”, “Tố giác”, “Theo dõi”, “Xem lịch sử”.
- Explain technical terms only when necessary and at the point where they matter.
- Avoid sensational language such as “Cực kỳ nguy hiểm!!!”, unsupported certainty, marketing claims, and generic AI slogans.
- Use concise sentences. State the result first, then the reason, then the recommended action.
- Preserve the product name exactly as `BaoTin`.

Examples of preferred result language:

- “Có dấu hiệu đáng ngờ” rather than a categorical claim unsupported by evidence.
- “Chưa phát hiện dấu hiệu đáng ngờ” rather than “An toàn tuyệt đối”.
- “Chưa có dữ liệu phù hợp” rather than “An toàn”.
- “Không thể cập nhật dữ liệu lúc này” rather than implying that the last known result is current.

## Brand Commitments

- Exact product name: `BaoTin`. Do not write `Bảo Tin`, `BảoTin`, or `Bao Tin`.
- **Font Awesome is the required application icon system.** Use the project's existing Font Awesome integration consistently across Web, Android, and iOS. Do not replace it with Material Symbols, SF Symbols, emoji, Unicode glyphs, or a second icon pack.
- Use icons only when they improve recognition or scanning. Important actions and risk states must also have text labels or accessible names.
- Do not assume a paid Font Awesome family or icon is installed. Prefer icons available in the existing project dependency; do not add a new icon package solely to decorate a screen.
- The supplied Kaspersky screenshots are layout and interaction references only. Do not copy their branding, exact styling, feature set, or pixel layout.

## Evidence on Hand

- `M:\BaoTin\BaoTin_Backend_Architecture_Master_Plan.md`

These paths describe user-provided reference material on the user's machine. They may not exist in the current Codex workspace. Do not claim to have inspected a file unless it is actually available in the working environment. If a referenced file is unavailable, proceed from the product context and existing implementation rather than inventing its contents.

## Definition of a Good Product Experience

A screen is ready for review only when:

- Its primary user task is obvious without reading a long introduction.
- Labels and actions use the product's Vietnamese terminology consistently.
- The screen distinguishes real data, mock data, unknown information, stale information, and errors.
- Risk is explained with evidence and a safe next action; it is not communicated by color alone.
- The screen does not imply unsupported scanning, monitoring, verification, or upload capabilities.
- Loading, empty, validation, error, and success states have been considered.
- It respects platform input methods, keyboard behavior, safe areas, and accessibility needs.
- It follows `DESIGN.md` for visual tokens and component behavior.
- It preserves existing routes, API contracts, permissions, and business logic.

## Guidance for Hallmark and Impeccable

Treat this file as the source of truth for **what BaoTin is, who it serves, what it can do, and what it must not claim**.

- Hallmark may restructure flows and screens to improve task clarity, provided it preserves the product model, scope, data semantics, and protected boundaries in this file.
- Impeccable may critique, audit, and polish the implementation, provided its recommendations do not introduce unsupported features, fake data, false security claims, or a different product direction.
- Use `DESIGN.md` as the source of truth for visual language, tokens, components, icon usage, and responsive design rules.
- If a proposed design conflicts with this file, preserve product truth and ask for no new backend capability by default.
- If `PRODUCT.md` and `DESIGN.md` appear to conflict, product scope and behavioral truth come from `PRODUCT.md`; visual implementation rules come from `DESIGN.md`. Resolve direct contradictions in the files rather than leaving competing instructions for the agent.
- Do not implement every idea in the reference screenshots. Design only the features and states supported by the actual product scope and contracts.

## Remote integration update — 2026-11-16

The user-requested merge of PR6 retains the live Analytics overview alongside the actionable report queue. AdminWorkspace exposes named Người dùng, Tố giác and Tổng quan views when ReportApi is available; without it the existing Analytics fallback remains. The imported trends adapter supports7/14/30day windows through Gateway. A trends failure is an explicit soft-failure and does not substitute demo totals or remove the existing summary/queue. These are source integration facts, not acceptance of real-provider consent, native delivery or every privileged endpoint. The earlier dated inventory remains a historical inspection checkpoint.
