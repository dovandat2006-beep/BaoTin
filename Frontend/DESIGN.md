---
name: "BaoTin Material 3 Product System"
description: "A calm, evidence-first security utility for checking phishing links and scam messages across web, Android, and iOS."
colors:
  seed: "#006B5F"
  light:
    primary: "#006B5F"
    on-primary: "#FFFFFF"
    primary-container: "#B4EDE2"
    on-primary-container: "#00201B"
    secondary: "#4B635D"
    on-secondary: "#FFFFFF"
    secondary-container: "#CDE8DF"
    on-secondary-container: "#071F1A"
    tertiary: "#456179"
    on-tertiary: "#FFFFFF"
    tertiary-container: "#CDE5FF"
    on-tertiary-container: "#001D32"
    error: "#BA1A1A"
    on-error: "#FFFFFF"
    error-container: "#FFDAD6"
    on-error-container: "#410002"
    background: "#F6FAF7"
    on-background: "#171D1A"
    surface: "#F6FAF7"
    surface-dim: "#D7DED9"
    surface-bright: "#F6FAF7"
    surface-container-lowest: "#FFFFFF"
    surface-container-low: "#F0F5F1"
    surface-container: "#EAF0EC"
    surface-container-high: "#E4EAE6"
    surface-container-highest: "#DDE4E0"
    on-surface: "#171D1A"
    on-surface-variant: "#414943"
    outline: "#707A73"
    outline-variant: "#C0C9C2"
    inverse-surface: "#2C322E"
    inverse-on-surface: "#EDF2ED"
    inverse-primary: "#80D5C4"
    scrim: "#000000"
  dark:
    primary: "#80D5C4"
    on-primary: "#00372F"
    primary-container: "#005047"
    on-primary-container: "#B4EDE2"
    secondary: "#B1CCC2"
    on-secondary: "#1C352E"
    secondary-container: "#334B44"
    on-secondary-container: "#CDE8DF"
    tertiary: "#AFCBE6"
    on-tertiary: "#173349"
    tertiary-container: "#2E4960"
    on-tertiary-container: "#CDE5FF"
    error: "#FFB4AB"
    on-error: "#690005"
    error-container: "#93000A"
    on-error-container: "#FFDAD6"
    background: "#0F1512"
    on-background: "#DEE4DF"
    surface: "#0F1512"
    surface-dim: "#0F1512"
    surface-bright: "#353B37"
    surface-container-lowest: "#0A100D"
    surface-container-low: "#171D19"
    surface-container: "#1B221E"
    surface-container-high: "#252C27"
    surface-container-highest: "#303732"
    on-surface: "#DEE4DF"
    on-surface-variant: "#BEC9C1"
    outline: "#89938C"
    outline-variant: "#404943"
    inverse-surface: "#DEE4DF"
    inverse-on-surface: "#2C322E"
    inverse-primary: "#006B5F"
    scrim: "#000000"
  semantic:
    success-light: "#205C39"
    success-container-light: "#E8F4EA"
    on-success-container-light: "#123B25"
    success-dark: "#B8E3C3"
    success-container-dark: "#173823"
    on-success-container-dark: "#D2F5D8"
    warning-light: "#7A4C00"
    warning-container-light: "#FFF0CE"
    on-warning-container-light: "#3D2600"
    warning-dark: "#F2C56E"
    warning-container-dark: "#3E2C0C"
    on-warning-container-dark: "#FFE2A8"
    danger-light: "#BA1A1A"
    danger-container-light: "#FFDAD6"
    on-danger-container-light: "#410002"
    danger-dark: "#FFB4AB"
    danger-container-dark: "#93000A"
    on-danger-container-dark: "#FFDAD6"
    unknown-light: "#414943"
    unknown-container-light: "#EAF0EC"
    unknown-dark: "#BEC9C1"
    unknown-container-dark: "#303732"
typography:
  family: "Roboto, system-ui, -apple-system, BlinkMacSystemFont, sans-serif"
  display-large: "36px / 44px, 600"
  display-medium: "32px / 40px, 600"
  headline-large: "32px / 40px, 600"
  headline-medium: "28px / 36px, 600"
  headline-small: "24px / 32px, 600"
  title-large: "22px / 28px, 600"
  title-medium: "16px / 24px, 600"
  title-small: "14px / 20px, 600"
  body-large: "16px / 24px, 400"
  body-medium: "14px / 20px, 400"
  body-small: "12px / 16px, 400"
  label-large: "14px / 20px, 500"
  label-medium: "12px / 16px, 500"
  label-small: "11px / 16px, 500"
rounded:
  extra-small: "4px"
  small: "8px"
  medium: "12px"
  large: "16px"
  extra-large: "28px"
  full: "9999px"
spacing:
  1: "4px"
  2: "8px"
  3: "12px"
  4: "16px"
  5: "20px"
  6: "24px"
  8: "32px"
  10: "40px"
  12: "48px"
  16: "64px"
components:
  primary-button:
    background: "var(--md-sys-color-primary)"
    foreground: "var(--md-sys-color-on-primary)"
    height: "48px"
    radius: "{rounded.medium}"
    horizontal-padding: "20px"
  secondary-button:
    background: "var(--md-sys-color-secondary-container)"
    foreground: "var(--md-sys-color-on-secondary-container)"
    height: "48px"
    radius: "{rounded.medium}"
    horizontal-padding: "20px"
  outlined-button:
    background: "transparent"
    foreground: "var(--md-sys-color-primary)"
    border: "1px solid var(--md-sys-color-outline)"
    height: "48px"
    radius: "{rounded.medium}"
    horizontal-padding: "20px"
  text-field:
    background: "var(--md-sys-color-surface-container-lowest)"
    foreground: "var(--md-sys-color-on-surface)"
    border: "1px solid var(--md-sys-color-outline)"
    min-height: "56px"
    radius: "{rounded.medium}"
    horizontal-padding: "16px"
  card:
    background: "var(--md-sys-color-surface-container-low)"
    foreground: "var(--md-sys-color-on-surface)"
    radius: "{rounded.large}"
    padding: "24px"
  dialog:
    background: "var(--md-sys-color-surface-container-high)"
    foreground: "var(--md-sys-color-on-surface)"
    radius: "{rounded.extra-large}"
  status-chip:
    radius: "{rounded.full}"
    padding: "4px 10px"
  icon:
    family: "Font Awesome"
    default-size: "24px"
    inline-size: "20px"
---

# BaoTin — Material 3 Design System

## 1. Product and design intent

BaoTin is a practical security utility for people who need to decide whether a message or link deserves caution. It is not a cybersecurity marketing site, an analytics showcase, or an AI chatbot interface.

**Design direction: Calm, evidence-first security.** The interface should feel dependable, direct, and easy to understand under uncertainty. Its job is to help people check content, understand why a result matters, and choose a safe next action.

### Product priorities

1. Make checking a URL or SMS the fastest and clearest task.
2. Explain findings in plain Vietnamese before exposing technical detail.
3. Make search, history, reporting, and monitoring easy to reach without competing with the primary task.
4. Make administrative review traceable and operationally clear.
5. Preserve privacy: user-submitted messages, phone numbers, URLs, and account details must not be casually exposed in lists or notifications.

### Product truth constraints

- The current detection scope is URL and SMS/message content. Do not invent additional scan modes unless the backend actually supports them.
- Never invent detection percentages, model confidence, threat counts, report totals, monitored coverage, or verification labels.
- Do not claim a message or website is absolutely safe. Prefer **“Chưa phát hiện dấu hiệu đáng ngờ”** over **“An toàn tuyệt đối”**.
- Distinguish model output from verified public intelligence. A user report is not automatically a confirmed threat.
- Do not imply real-time monitoring if the implementation uses scheduled polling. Show the actual last-checked time and configured interval when available.
- Use the existing routes, API contracts, permissions, and business logic. A visual redesign must not silently change product behavior.

### Implementation baseline versus design target

`PRODUCT.md` records the source-inspected live/demo inventory and its acceptance limits. Normal application runs use real Gateway adapters; only explicit demo branches use local fixtures. Preserve live scan/history, public lookup, account, URL watchlist/notifications, reports/evidence and moderation workflows. In the current normal admin Statistics path, report counts and review actions come from `ReportApi`, not an empty Analytics projection. Visual work must not replace these paths with mock data.

This document remains the approved Material 3 visual target, not a claim that every existing component already implements it. Runtime colors are centralized in `color-tokens.json`, consumed by `src/theme/palettes.ts` and `tailwind.config.js`; `tokens.css` is the web token export. The application uses Expo React Native, NativeWind 4 and Tailwind 3. Existing `accent`, `panel`, `ink` and related aliases differ from some approved M3 role values below. Preserve the approved palette, Roboto, spacing, shapes and adaptive rules; reconcile implementations deliberately in a separate scoped UI task rather than rewriting this specification to match unfinished or legacy styling.

Likewise, System/Light/Dark selection, keyboard-aware adaptive navigation, per-mode scan drafts and other behavioral presentation guidance below are design requirements, not assertions that these are already implemented. Documentation reconciliation does not change application code, routes, risk mappings, API payloads or authorization.

## 2. Non-negotiable design principles

### 2.1 Material 3, not a generic dashboard theme

Use Material Design 3 component behavior, semantic color roles, typography hierarchy, shape scale, and adaptive navigation. Do not approximate M3 by simply applying teal to generic cards and buttons.

The design system is platform-neutral. Implement it with the project's existing UI framework and the closest official or well-maintained M3 components available for that platform. Do not add a second component library merely because its name contains “Material”. If the project uses MUI, theme and adapt the existing components; do not assume MUI automatically provides complete Material 3 behavior.

### 2.2 One visual language

- Use the named tokens in this file. Do not introduce one-off colors, font families, radii, or spacing values in page CSS.
- Light and dark themes must use the same semantic roles. Never invert colors mechanically.
- Pine/teal is the brand interaction color. Green, amber, and red communicate risk or system status only.
- Use surface tones and spacing to create hierarchy before adding borders, cards, or shadows.
- Keep screens task-led. Different tasks may have different page structures; do not force every route into the same title + filter row + card grid template.

### 2.3 No AI-generated-looking UI

Do not use:

- Gradient backgrounds, glassmorphism, neon glows, decorative blur, or ornamental security patterns.
- A giant centered hero, marketing slogan, floating dashboard decoration, or generic “AI-powered” badge inside authenticated product screens.
- A row of identical statistic cards when simple aligned values or a compact table is clearer.
- A card around every group, nested cards, or a card inside another card.
- Rounded pills for every control. Reserve full-pill shapes for status chips and compact filters where appropriate.
- Emoji, Unicode glyphs, Material Symbols, SF Symbols, or mixed icon libraries as application icons. Use Font Awesome exclusively.
- Fake charts, fake activity, dummy records, fabricated avatars, or placeholder metrics in a finished screen.
- Excessively large empty-state illustrations, oversized headings, or unexplained blank regions.
- Decorative icon tiles above every heading, eyebrow labels on every page, all-uppercase tracked labels, or a differently colored word inside every heading.
- Clickable-looking controls that have no implemented action.

Use real product data, precise labels, useful empty states, and restrained motion. If an area has no data, explain what belongs there and what the user can do next.

## 3. Color system

### 3.1 Brand seed and color generation

Brand seed: **BaoTin Pine `#006B5F`**.

The palette below is the approved initial static scheme. Treat these values as named Material 3 roles, not as colors to copy ad hoc into individual components. When regenerating the theme with Material Theme Builder or Material Color Utilities, retain the same seed and semantic intent, then update this file and the implementation tokens together.

Use the role pairs as intended: `primary` with `on-primary`, `primary-container` with `on-primary-container`, and the same rule for secondary, tertiary, error, and surface roles. Do not place `on-surface` text on an unrelated container simply because it looks visually close.

### 3.2 Light theme

| Role | Hex | Use |
|---|---:|---|
| `primary` | `#006B5F` | Main actions, active navigation, links, focus |
| `on-primary` | `#FFFFFF` | Text/icons on primary |
| `primary-container` | `#B4EDE2` | Selected supporting surfaces, restrained highlights |
| `on-primary-container` | `#00201B` | Text/icons on primary container |
| `secondary` | `#4B635D` | Secondary controls and low-emphasis accents |
| `on-secondary` | `#FFFFFF` | Text/icons on secondary |
| `secondary-container` | `#CDE8DF` | Tonal secondary controls |
| `on-secondary-container` | `#071F1A` | Text/icons on secondary container |
| `tertiary` | `#456179` | Rare supporting distinction, not a second brand color |
| `on-tertiary` | `#FFFFFF` | Text/icons on tertiary |
| `tertiary-container` | `#CDE5FF` | Tertiary supporting surface |
| `on-tertiary-container` | `#001D32` | Text/icons on tertiary container |
| `error` | `#BA1A1A` | Destructive actions and system errors |
| `on-error` | `#FFFFFF` | Text/icons on error |
| `error-container` | `#FFDAD6` | Error surface |
| `on-error-container` | `#410002` | Text/icons on error container |
| `background` / `surface` | `#F6FAF7` | Main application canvas |
| `surface-container-lowest` | `#FFFFFF` | Inputs or the clearest contained surface |
| `surface-container-low` | `#F0F5F1` | Navigation and subtle grouping |
| `surface-container` | `#EAF0EC` | Default contained surface |
| `surface-container-high` | `#E4EAE6` | Dialogs and stronger grouping |
| `surface-container-highest` | `#DDE4E0` | Highest neutral container emphasis |
| `on-surface` | `#171D1A` | Main text and icons |
| `on-surface-variant` | `#414943` | Supporting text and secondary icons |
| `outline` | `#707A73` | Input/control boundaries |
| `outline-variant` | `#C0C9C2` | Dividers and low-emphasis boundaries |
| `inverse-surface` | `#2C322E` | Inverse surfaces such as snackbars |
| `inverse-on-surface` | `#EDF2ED` | Content on inverse surface |
| `inverse-primary` | `#80D5C4` | Primary accent on inverse surface |

### 3.3 Dark theme

| Role | Hex | Use |
|---|---:|---|
| `primary` | `#80D5C4` | Main actions, active navigation, links, focus |
| `on-primary` | `#00372F` | Text/icons on primary |
| `primary-container` | `#005047` | Selected supporting surfaces |
| `on-primary-container` | `#B4EDE2` | Text/icons on primary container |
| `secondary` | `#B1CCC2` | Secondary controls and low-emphasis accents |
| `on-secondary` | `#1C352E` | Text/icons on secondary |
| `secondary-container` | `#334B44` | Tonal secondary controls |
| `on-secondary-container` | `#CDE8DF` | Text/icons on secondary container |
| `tertiary` | `#AFCBE6` | Rare supporting distinction |
| `on-tertiary` | `#173349` | Text/icons on tertiary |
| `tertiary-container` | `#2E4960` | Tertiary supporting surface |
| `on-tertiary-container` | `#CDE5FF` | Text/icons on tertiary container |
| `error` | `#FFB4AB` | Destructive actions and system errors |
| `on-error` | `#690005` | Text/icons on error |
| `error-container` | `#93000A` | Error surface |
| `on-error-container` | `#FFDAD6` | Text/icons on error container |
| `background` / `surface` | `#0F1512` | Main application canvas; do not use pure black |
| `surface-container-lowest` | `#0A100D` | Lowest contained surface; use sparingly |
| `surface-container-low` | `#171D19` | Navigation and subtle grouping |
| `surface-container` | `#1B221E` | Default contained surface |
| `surface-container-high` | `#252C27` | Dialogs and stronger grouping |
| `surface-container-highest` | `#303732` | Highest neutral container emphasis |
| `on-surface` | `#DEE4DF` | Main text and icons |
| `on-surface-variant` | `#BEC9C1` | Supporting text and secondary icons |
| `outline` | `#89938C` | Input/control boundaries |
| `outline-variant` | `#404943` | Dividers and low-emphasis boundaries |
| `inverse-surface` | `#DEE4DF` | Inverse surfaces such as snackbars |
| `inverse-on-surface` | `#2C322E` | Content on inverse surface |
| `inverse-primary` | `#006B5F` | Primary accent on inverse surface |

### 3.4 Risk and feedback colors

Risk colors are semantic application tokens layered on top of the M3 scheme. They must never be used for ordinary navigation, decoration, or generic emphasis.

| State | Light foreground / container | Dark foreground / container | Required meaning |
|---|---|---|---|
| Low risk / no clear signal | `#205C39` / `#E8F4EA` | `#B8E3C3` / `#173823` | No suspicious signal detected by the current check; not a guarantee of safety |
| Medium risk | `#7A4C00` / `#FFF0CE` | `#F2C56E` / `#3E2C0C` | Suspicious indicators exist; user should verify before acting |
| High risk | `#BA1A1A` / `#FFDAD6` | `#FFB4AB` / `#93000A` | Strong phishing/scam indicators or a confirmed high-risk state |
| Unknown / unavailable | `#414943` / `#EAF0EC` | `#BEC9C1` / `#303732` | Insufficient data, scan failure, or unclassified result |

Every risk state must include a text label and a short explanation. Never communicate risk using color alone. A risk badge is not a substitute for the result headline or recommended action.

### 3.5 Theme behavior

- Default to the operating-system theme on first launch.
- Provide `System`, `Light`, and `Dark` choices in settings; persist the user's selection.
- Apply the chosen theme to every route, dialog, menu, chart, table, input, toast, and native status/navigation region where supported.
- Dark mode uses the dark surface hierarchy above. Do not simply invert the light theme or use pure black backgrounds.
- Dynamic Android color may be offered only as an explicit optional mode. It must not alter risk semantics, reduce contrast, or make BaoTin's brand identity unpredictable. Keep the approved fixed theme as the cross-platform default.

Implementation token names should follow the M3 role names, for example `--md-sys-color-primary`, `--md-sys-color-on-primary`, `--md-sys-color-surface`, `--md-sys-color-surface-container-low`, `--md-sys-color-on-surface`, and `--md-sys-color-outline`. Map the light and dark values to the same aliases through the app theme provider or `[data-theme]`; components must reference aliases, never hard-code a light-mode hex value. Keep risk aliases separate, for example `--bt-risk-low-foreground` and `--bt-risk-low-container`.

## 4. Typography

Use **Roboto** as the primary typeface to align with Material 3 and preserve predictable Vietnamese rendering. Use the platform system fallback only when Roboto is unavailable. Do not introduce Inter, Poppins, Montserrat, or a decorative display font.

Use the type scale below as a hierarchy, not as a requirement to display every level on every screen.

| M3 role | Size / line height | Weight | Typical use |
|---|---:|---:|---|
| Display large | 36 / 44px | 600 | Rare primary result statement or major account state |
| Display medium | 32 / 40px | 600 | Main screen heading on wide layouts |
| Headline large | 32 / 40px | 600 | Primary page heading |
| Headline medium | 28 / 36px | 600 | Major section heading |
| Headline small | 24 / 32px | 600 | Secondary screen heading |
| Title large | 22 / 28px | 600 | Dialog or result title |
| Title medium | 16 / 24px | 600 | Group, list, or card title |
| Title small | 14 / 20px | 600 | Compact subsection title |
| Body large | 16 / 24px | 400 | Main reading content and explanations |
| Body medium | 14 / 20px | 400 | Supporting content and table rows |
| Body small | 12 / 16px | 400 | Timestamps and secondary metadata |
| Label large | 14 / 20px | 500 | Buttons and prominent labels |
| Label medium | 12 / 16px | 500 | Form labels, tabs, compact metadata |
| Label small | 11 / 16px | 500 | Dense metadata only |

Rules:

- Use sentence case. Avoid all-uppercase labels and artificial letter spacing.
- Use tabular numerals for counts, dates, and statistics where supported.
- Keep long explanatory paragraphs to a readable measure of roughly 60–75 characters per line.
- Do not use 12px body text for essential instructions. Body copy should normally be 14–16px.
- Keep Vietnamese wording concise and natural. Do not use technical model terminology in user-facing copy unless the user explicitly opens a technical details view.

## 5. Spacing, shape, and elevation

### 5.1 Spacing

Use a 4px base unit and favor the following rhythm:

- 4–8px: icon-to-label gaps and tightly related metadata.
- 12px: related controls and compact field groups.
- 16px: standard component spacing and compact page gutters.
- 20–24px: related sections and contained content padding.
- 32px: major section separation and desktop content gutters.
- 40–48px: separation between independent page regions.
- 64px: only for genuinely distinct page regions, not routine form spacing.

Do not fix large blank areas with decorative content. Use the available space to improve readability, a useful list-detail layout, or a clear explanation.

### 5.2 Shape

Use the defined shape scale consistently:

- Extra small: 4px — small labels or compact utility surfaces.
- Small: 8px — compact menus and minor contained elements.
- Medium: 12px — text fields and standard buttons.
- Large: 16px — cards and contained working sections.
- Extra large: 28px — dialogs and larger modal surfaces.
- Full: 9999px — status chips and selected filter chips only.

Do not make every component fully rounded. Navigation rows and data tables remain structurally square.

### 5.3 Elevation and borders

- The default interface is flat. Use surface-container roles before shadows.
- Do not add decorative drop shadows to resting cards, fields, or page sections.
- Use elevation only for actual overlays: menus, dialogs, sheets, popovers, and floating controls.
- Use `outline` for interactive boundaries and `outline-variant` for passive separators.
- Avoid drawing a border around every section. Prefer spacing, alignment, and surface tone.

## 6. Icon system

### Primary icon family

Use **Font Awesome exclusively** for application icons across Web, Android and iOS, through the existing `@fortawesome/react-native-fontawesome` integration. Material Design 3 remains the visual language for semantic colors, typography, spacing, shapes, state feedback and adaptive layouts; it does not require Material Symbols.

Use installed free solid icons and the existing free brand icons for Google/Facebook authentication. Do not introduce Material Symbols, SF Symbols, emoji, Unicode glyphs or a second application icon pack. Native operating-system dialogs retain their platform-provided chrome; this is not permission to mix icon families in BaoTin-rendered UI. Keep the existing 24px standalone / 20px inline design sizing guidance, with optical adjustments appropriate to the control and a full accessible touch target.

### Approved semantic mapping

These Font Awesome exports preserve existing meanings; entries not currently rendered are vocabulary for supported controls, not new feature requirements. A check icon alone never asserts authoritative verification.

| Meaning | Font Awesome export |
|---|---|
| Kiểm tra | `faShield` |
| Tra cứu | `faMagnifyingGlass` |
| Lịch sử | `faClockRotateLeft` |
| Báo cáo / tố giác | `faFileShield / faFlag` |
| Theo dõi | `faEye` |
| Quản trị | `faGaugeHigh` |
| URL | `faLink` |
| Tin nhắn | `faMessage` |
| Kết quả chưa có dấu hiệu đáng ngờ | `faCircleCheck` |
| Cảnh báo mức trung bình | `faTriangleExclamation` |
| Rủi ro cao / lỗi | `faCircleExclamation` |
| Thông tin | `faCircleInfo` |
| Sao chép | `faCopy` |
| Làm mới | `faRotate` |
| Bộ lọc | `faFilter` |
| Sắp xếp | `faSort` |
| Tuỳ chọn khác | `faEllipsisVertical` |
| Cài đặt | `faGear` |
| Thông báo | `faBell` |
| Tài khoản | `faUser` |
| Thiết bị | `faMobileScreen` |
| Bảo mật tài khoản | `faShield` |
| Mật khẩu / khoá | `faLock / faKey` |
| Đóng / huỷ | `faXmark` |
| Quay lại | `faArrowLeft` |

Icon rules:

- Icons must clarify an action or object. Do not add an icon to every label just to fill space.
- Never use an icon without a tooltip or accessible label when the control's meaning is not obvious.
- Do not use a shield icon as the default illustration for every security feature.
- Do not use icons as a substitute for text labels in important risk states.
- Keep icon size and baseline alignment consistent within a component.

## 7. Responsive and adaptive layout

Design for the **available application window**, not for a device label. The layout must adapt when a tablet rotates, a foldable opens, split-screen is activated, or a browser window is resized.

### 7.1 Width classes

| Width class | Window width | Layout behavior |
|---|---:|---|
| Compact | `< 600px` | Single content pane; bottom navigation; 16px page gutters |
| Medium | `600–839px` | One primary pane with optional supporting pane; navigation rail where useful; 24px gutters |
| Expanded | `840–1199px` | Two-pane layouts where they improve a task; navigation rail or drawer; 32px gutters |
| Large | `1200–1599px` | Full desktop shell; permanent drawer; 32–40px gutters |
| Extra large | `>= 1600px` | Centered content with a maximum width; do not stretch paragraphs and forms across the display |

Use fluid constraints between breakpoints. Do not implement layouts as a set of rigid fixed-width device templates.

### 7.2 Application shell

**Web / large screens**

- Use a permanent navigation drawer approximately 240–256px wide.
- Use a top app bar approximately 64px high.
- The main work area is centered and capped at 1280px; use up to 1360px only for dense admin tables.
- Use 32–40px horizontal page gutters depending on available width.
- Keep the page title, primary action, and content aligned to one content grid.
- A collapsed navigation rail may be used when users need more horizontal workspace, but do not create both a sidebar and an additional duplicate horizontal navigation menu.

**Tablet / medium screens**

- Prefer a navigation rail of approximately 80px when there are several top-level destinations.
- Use a drawer for secondary destinations or when the number of items does not fit comfortably.
- Use one primary content pane by default. Add a supporting pane only for meaningful list-detail or form-result workflows.

**Phone / compact screens**

- Use a bottom navigation bar for the primary destinations.
- Keep no more than four primary destinations visible; place less frequent destinations such as Theo dõi and Quản trị under a clearly labeled `Thêm` destination or an appropriate secondary menu.
- Use 16px horizontal content gutters and respect top/bottom safe areas.
- Hide or adapt bottom navigation while the keyboard is open so the input and submit action remain accessible.
- Use one content column. Stack any desktop split view in task order: input first, result/detail second.

### 7.3 Navigation model

The top-level information architecture is:

1. **Kiểm tra** — primary URL and SMS checking task.
2. **Tra cứu** — public lookup of reported domains, phone numbers, email addresses, banks, campaigns, and other supported records.
3. **Lịch sử** — the current user's past checks.
4. **Báo cáo** — submit and track scam reports.
5. **Theo dõi** — manage monitored entities and notification preferences.
6. **Quản trị** — restricted operational and moderation tools, visible only to authorized roles.

Do not show unauthorized destinations as disabled navigation items. Hide them or show a clear permission boundary only when access discovery is relevant.

Do not repeat `Xem lịch sử` as a prominent button on the checking screen when Lịch sử is already a primary destination. A contextual link is acceptable only when it is genuinely useful and visually secondary.

### 7.4 Content width and columns

- Standard reading/form pages: max-width 960–1120px.
- Main checking workbench: max-width 1280px.
- Dense administration pages: max-width 1360px.
- Do not stretch a short form to the full browser width.
- Use a 12-column or equivalent fluid grid on expanded screens; use 8px-based gaps.
- A second column is justified only when it contains related information that users need while completing the main task.

## 8. Component rules

### 8.1 Buttons

Use Material 3 action hierarchy:

- **Filled primary:** one highest-priority action per task region, such as `Kiểm tra ngay` or `Gửi báo cáo`.
- **Filled tonal:** a secondary action that still needs a visible surface.
- **Outlined:** lower-priority alternate actions.
- **Text:** contextual actions, inline links, and low-emphasis commands.
- **Icon button:** compact utility actions only; provide accessible names and tooltips on desktop.

Rules:

- Standard interactive height is 48px (or at least the platform's recommended touch target).
- A page may have more than one action, but only one should visually dominate each task region.
- Do not place multiple full-width primary buttons next to one another.
- Destructive actions must use the danger/error semantic treatment and require confirmation when the consequence is irreversible.
- Use labels that say what will happen. Avoid vague labels such as `Thực hiện`, `Xử lý`, or `OK` when a more precise action exists.

### 8.2 Form fields

- Use visible labels above fields; placeholder text is not a label.
- Standard field height is 56px. Multiline fields grow with content and have a useful minimum height.
- Show validation next to the relevant field, not only in a global toast.
- Preserve user-entered content when a recoverable request fails.
- Do not use a red border without an explanatory message.
- Password fields need show/hide controls, appropriate autocomplete, and clear password requirements.
- Use a single clear submit action for each form.

### 8.3 Tabs, segmented buttons, and filters

- Use tabs for switching between peer content sections such as Hồ sơ, Bảo mật, Phiên, and Thiết bị.
- Use segmented buttons for a small, mutually exclusive selection such as URL versus SMS.
- Use filter chips only for compact, repeatable filters. Do not turn every form choice into a chip.
- On narrow screens, allow filters to scroll horizontally or move into a filter sheet; never wrap into a chaotic multi-row chip wall.
- Make the selected state unmistakable through surface, label, and indicator—not color alone.

### 8.4 Cards, lists, and tables

- Use cards only when content forms a meaningful independent unit, such as a scan result, monitored target, or security setting group.
- Use surface-container-low for subtle grouping; reserve outlined cards for content that needs a boundary.
- Do not wrap a complete page in one giant card.
- Use list rows for histories, monitored entities, sessions, and report queues. Put secondary actions in an overflow menu.
- Use tables on desktop for genuinely tabular admin data. On mobile, transform rows into readable stacked summaries rather than forcing horizontal scrolling for essential information.
- Provide sorting, filtering, pagination, and clear column labels only where the dataset needs them.

### 8.5 Dialogs, sheets, menus, and snackbars

- Use a dialog for a short, consequential confirmation or focused decision.
- Use a bottom sheet on compact screens for contextual filters or secondary actions.
- Use a side sheet on wide screens only when it preserves context and does not obscure the primary task.
- Use menus for low-frequency actions, not for essential navigation.
- Use snackbars for brief non-blocking feedback. Important failures need an inline explanation and recovery action.
- Do not stack multiple dialogs or show a toast for every successful field update.

### 8.6 Charts and statistics

- Show a chart only when it reveals a real trend, comparison, or distribution that is harder to understand in a table.
- Every chart needs a period, unit, legend where required, and meaningful empty/error state.
- Do not use pie charts for many categories or decorative sparklines without interpretation.
- Put exact values in an accessible table or summary, not only in chart graphics.
- Never fabricate values to make an admin dashboard look populated.

## 9. Screen-specific information architecture

Each screen should have a structure that fits its job. Shared shell and tokens stay consistent; page composition should not be mechanically identical.

### 9.1 Kiểm tra lừa đảo — primary workbench

**Purpose:** Let a user submit a URL or SMS and understand the result without navigating away.

**Wide layout**

- Page heading: `Kiểm tra lừa đảo`.
- Main content uses two related panes, approximately 58–62% for input and 38–42% for result, with a 24–32px gap.
- The left pane contains the URL/SMS segmented selector, the appropriate field, a concise privacy/format helper, and one filled primary action.
- The right pane is the result region. Before a scan, show a compact empty state such as `Kết quả sẽ hiển thị tại đây` and one line explaining that the user should run a check first.
- Do not use a heavy vertical divider. Alignment, spacing, and surface tone are enough.

**Compact layout**

- Show the input task first and the result below it.
- Keep the primary action reachable without covering the keyboard.
- After checking, scroll the result into view or focus it accessibly; do not unexpectedly navigate to another route.

**Input behavior**

- URL mode: one-line URL field with normalization and clear validation.
- SMS mode: multiline message field with a sensible height and resize/scroll behavior.
- Changing the selected mode must not silently submit or erase text. Preserve draft content per mode when feasible.
- State clearly what content is sent for analysis and link to the privacy policy where available.

**Result anatomy**

1. Plain-language headline: `Có dấu hiệu lừa đảo`, `Cần thận trọng`, `Chưa phát hiện dấu hiệu đáng ngờ`, or `Chưa thể kết luận` according to actual backend output.
2. Risk state with both a text label and semantic color.
3. Short explanation of what was detected.
4. Evidence or suspicious indicators, only when the backend can provide meaningful evidence.
5. Recommended next action: do not open, do not provide OTP/password, verify through an official channel, or another action grounded in the result.
6. Source/check metadata only when available and useful.

Do not show a raw model score or percentage as the main result. Do not make a low-risk result sound like a guarantee. Do not automatically open a submitted URL or load its remote preview.

### 9.2 Tra cứu — public threat lookup

**Purpose:** Find public information about a domain, phone number, email, bank, campaign, or other supported entity.

- Start with one prominent search field and a clear example of accepted query types in its placeholder or helper text.
- Put result-type and risk filters directly below the search field. Keep the default `Tất cả` state visible.
- Show query summary and result count only when returned by the API.
- Result rows should lead with entity name/value and risk status, then show a concise reason, category, and last-updated/verification source when known.
- Distinguish `Người dùng báo cáo`, `Đang xác minh`, and `Đã xác minh` only when these are actual backend states.
- Search empty state: `Bạn muốn tra cứu gì?` plus `Nhập ít nhất 2 ký tự để bắt đầu.`
- No-result state: explain that no matching public record was found; do not imply that the entity is safe.
- Error state: preserve the query and offer a single `Thử lại` action.

Do not leave a large empty canvas after the filters. Use the remaining space for a compact search prompt or actual results.

### 9.3 Lịch sử — personal scan history

- Lead with the page title and a useful time/type filter, not a dashboard hero.
- Each row shows type (URL/SMS), a safe abbreviated representation of the input, result status, and timestamp.
- Mask sensitive content by default. Reveal full content only in a deliberate detail view and according to privacy rules.
- Provide search/filter only if the amount of history makes them useful.
- Empty state should explain that completed checks will appear here and link back to `Kiểm tra`.
- Support loading, pagination/infinite loading, unavailable, and deletion-confirmation states.

### 9.4 Báo cáo — submit and track a report

- Separate the act of submitting a report from viewing the user's existing reports.
- The form should ask only for the evidence needed by the backend. Explain required versus optional fields.
- Explain how submitted information will be used and whether it may be made public, according to the actual privacy policy.
- After submission, show a clear receipt/reference only if the backend returns one.
- Report status must use exact backend states. Never convert `pending` into `verified` through friendly wording.
- A duplicate or failed submission must preserve the entered details and explain the next step.

### 9.5 Theo dõi — monitored entities

- Show the monitored URL/entity, current state, notification preference, and last checked time when available.
- Make pause, resume, and remove actions explicit and confirm destructive removal.
- Provide a clear add-monitor action only if the backend feature exists.
- Explain the monitoring cadence. Do not label it real-time unless it truly is.
- Empty state should explain what monitoring does and the conditions under which BaoTin sends an alert.

### 9.6 Quản trị — moderation and operations

**Purpose:** Help authorized staff review evidence and take traceable decisions. This is an operational workspace, not a decorative analytics dashboard.

- Keep the admin area under one consistent navigation destination. Use secondary tabs for peer sections such as `Báo cáo`, `Người dùng`, and `Thống kê` when these routes exist.
- The page heading comes before filters and data. Avoid unexplained segmented controls floating above the title.
- Put one page-level refresh action in the toolbar or next to the relevant section—not separate redundant refresh buttons for the same dataset.
- Show loading/error state near the affected dataset. If a request times out, show a concise explanation, preserve existing data if safe, and offer one retry action.
- Display counts as simple aligned metrics or a compact summary row. Use cards only when the metrics need independent context.
- Report moderation should prioritize a queue/list with status, submitted time, category, and evidence summary. Selecting a report opens a detail pane or route with the full evidence and decision history.
- Decisions such as verify, request more information, or reject must ask for a reason when required by the workflow. Record the actor, time, and reason if the backend supports it.
- Charts must represent actual API data and indicate the selected reporting period.
- Permission errors must be distinct from network errors and empty datasets.

### 9.7 Tài khoản và bảo mật

Use a focused settings layout, not a long stack of every possible action.

Recommended sections:

- `Hồ sơ`: identity and editable profile fields.
- `Bảo mật`: password, two-step verification, recovery codes, and verification methods.
- `Phiên`: active sessions with device, location approximation if supported, and last activity.
- `Thiết bị`: trusted/registered devices and their status.

Behavior:

- Use tabs on wide layouts and a compact secondary navigation pattern on phones.
- Show current security state before showing actions.
- Keep the password-change form inside the password section; do not display empty fields for a task the user has not selected.
- Keep two-step verification setup as a guided flow. Show recovery codes only at the point they are generated, explain that they should be stored safely, and do not expose them again unnecessarily.
- Confirm session revocation and other security-sensitive operations.
- After password change, communicate the real session effect. Do not claim all devices were signed out unless the server confirms it.
- Use inline feedback for validation and a success confirmation only after the server reports success.

### 9.8 Global app bar and account actions

- Keep the top bar quiet: app identity, current page context where useful, and a small number of genuinely global actions.
- Notifications appear only when notification functionality exists and the unread state is accurate.
- Account/avatar opens a menu for profile, preferences, help, and sign out.
- Theme selection belongs in preferences or a compact account/settings menu.
- `Tải APK` is not a permanent primary action inside every authenticated screen. Place app installation on the public download page or in an account/help location where it is contextually relevant. On iOS, do not show an APK action.
- Do not duplicate settings, history, or navigation destinations in both the sidebar and the app bar.

## 10. Interaction states and feedback

Every interactive component must have these states where applicable:

1. Default
2. Hover (web/pointer only)
3. Focus-visible (keyboard/accessibility)
4. Pressed/active
5. Disabled
6. Loading
7. Error
8. Success

Not every component needs a visible success state, but every state transition must be defined. Do not simulate loading for a fixed arbitrary duration; reflect the real request state.

### Loading

- Use a progress indicator on the control or region that is loading.
- Keep the user's input visible and unchanged.
- Prevent duplicate submissions while a request is pending.
- Use skeletons only when the content structure is known and loading is not nearly instantaneous.

### Empty states

An empty state must answer:

1. What is absent?
2. Why is it absent, if known?
3. What useful action can the user take next?

Keep empty states compact and contextual. Use a small relevant icon only when it adds meaning; no decorative illustration is required.

### Errors and recovery

- State what failed in plain language.
- Distinguish validation, network, authorization, rate-limit, and server errors when the UI can know the difference.
- Keep user input and filters intact after recoverable errors.
- Offer one clear next action, usually `Thử lại`, `Đăng nhập lại`, or `Sửa thông tin` depending on the cause.
- Do not show raw stack traces, SQL errors, model exceptions, or backend response dumps to users.

### Motion

- Use short, purposeful transitions for selection, expansion, dialogs, and navigation.
- Prefer Material motion patterns and platform-native navigation transitions.
- Respect `prefers-reduced-motion` and the operating-system reduced-motion setting.
- Do not use parallax, scroll-jacking, animated backgrounds, bouncing buttons, or ornamental motion.

## 11. Accessibility, privacy, and localization

### Accessibility

- Target WCAG 2.2 AA contrast: at least 4.5:1 for normal text and 3:1 for large text and meaningful UI graphics/boundaries.
- Do not use color as the only indication of risk, selection, validation, or status.
- Provide visible keyboard focus and logical tab order on web.
- Use semantic headings, labels, landmarks, and accessible names for icon-only actions.
- Support screen readers and dynamic text sizing. Do not clip Vietnamese labels at larger text sizes.
- Touch targets should be at least 48×48dp on Android and at least 44×44pt on iOS; shared cross-platform controls should aim for 48px where practical.
- Ensure dialogs, sheets, dropdowns, and menus manage focus correctly and can be dismissed using platform-standard behavior.

### Privacy and security UX

- Mask phone numbers, email addresses, and message contents in list previews unless full values are necessary for the task.
- Never place complete user-submitted SMS or suspicious URLs in push notification previews by default.
- Do not use color, animation, or urgency copy to pressure users into an action.
- Do not open submitted suspicious links automatically.
- Provide a clear explanation before sharing or publishing a report, when that action exists.
- Do not promise data retention, encryption, verification, or deletion behavior that is not implemented.

### Localization

- Primary locale: Vietnamese (`vi-VN`).
- Keep user-facing copy natural, direct, and consistent. Use the same terms for each risk/status state across screens.
- Format dates, times, and numbers according to the user's locale and timezone.
- Allow for longer Vietnamese strings and dynamic text; do not hard-code widths based on English labels.

## 12. Platform-specific behavior

### Android

- Use Material 3 components and system bars that coordinate with the selected theme.
- Respect edge-to-edge layout, system-bar insets, predictive/system back behavior, and Android text scaling.
- Use the adaptive navigation pattern appropriate to the current window size class.
- Support Android keyboard, autofill, password manager, and share intents where relevant.

### iOS

- Preserve iOS safe areas, home-indicator spacing, keyboard behavior, swipe-back navigation, and system text scaling.
- Use native iOS behavior for system permissions, share sheets, authentication, and destructive confirmations where appropriate.
- Keep the shared BaoTin palette, typography hierarchy, and content architecture. Do not force Android-only navigation or system chrome onto native iOS screens.
- If native components are used, map Material semantic tokens to their closest native equivalents rather than drawing custom replicas of system controls.

### Web

- Support keyboard navigation, visible focus, browser zoom, and responsive resizing.
- Use semantic HTML and preserve browser-native input behavior unless there is a clear usability reason to customize it.
- On wide displays, keep forms and reading content constrained. Do not stretch every section to the full viewport width.
- Use hover only as an enhancement; every action must remain discoverable by keyboard and touch.
- Do not simulate a desktop browser bar or mobile device frame inside the product.

## 13. Implementation contract for coding agents

This file is a product design constraint, not permission to rewrite the application.

1. Inspect the existing routes, components, styles, app structure, permissions, and API behavior before making UI changes.
2. Preserve existing business logic, event handlers, API contracts, authentication, authorization, and data flow unless the task explicitly requests a functional change.
3. Reuse the existing framework and component primitives. Do not introduce a new design-system dependency without a concrete technical need.
4. Define the palette, typography, spacing, shape, elevation, and risk-state tokens once. Reference those tokens from all components; no page-specific magic values.
5. Implement both light and dark themes in the same pass. Check every route, modal, menu, chart, input, and status state in both themes.
6. Build each page around its task. Keep the shared shell consistent while allowing different information architecture and composition for checking, lookup, moderation, and settings.
7. Do not remove functional controls, routes, form fields, or handlers during a visual redesign. If an element is redundant, verify whether its function is available elsewhere before removing it.
8. Do not invent records, charts, activity, notifications, success states, or backend capabilities to make screenshots look complete.
9. Test the real rendered application, not only static markup. Verify keyboard use, touch targets, loading/error/empty states, and long Vietnamese content.
10. Fix layout overflow, clipped text, overlapping sticky elements, keyboard obstruction, and broken safe areas before visual polish.

### Hallmark + Impeccable workflow

Use the two skills sequentially with different responsibilities. Do not run them as competing art directors on the same pass.

**Hallmark — structural redesign and anti-slop floor**

- Invoke Hallmark explicitly in redesign mode for this existing product, for example: `$hallmark redesign <app-or-route-path>`.
- Treat this `DESIGN.md` as the locked visual system. The pine M3 palette, Roboto type scale, Font Awesome icon rules, component behavior, and product constraints must not be replaced by a catalog theme that conflicts with this file.
- Use Hallmark to improve page composition and remove generic AI patterns while preserving real routes, copy intent, handlers, forms, and product behavior.
- Build page structure from each task. Do not turn every route into the same dashboard template.
- Keep all factual copy and data honest. Never create fake statistics, trust claims, or example records in production UI.
- Before handoff, review the rendered result at 320px, 375px, 414px, 768px, and wide desktop widths. Resolve contrast, focus, reduced-motion, and broken mobile layouts.

**Impeccable — critique, audit, and refinement**

- After the structural redesign is implemented, use `$impeccable critique` on the affected screen to identify the highest-impact hierarchy and usability problems.
- Use `$impeccable audit` to check accessibility, responsive behavior, theming, and implementation quality.
- Apply focused refinement commands such as `$impeccable layout`, `$impeccable quieter`, `$impeccable typeset`, `$impeccable adapt`, `$impeccable harden`, and `$impeccable polish` only when their findings apply.
- Impeccable must preserve this file's approved tokens and the product constraints above. Do not run `$impeccable document` against the old UI just to recapture it; that would risk documenting the current visual problems as the desired system.
- If the implementation reveals a genuinely necessary design-token change, update this file deliberately and update every platform/theme mapping together.

**Final review gate**

Before calling the redesign complete, inspect screenshots of the actual application and verify:

- Philosophy: does the product feel like a trustworthy security utility rather than a marketing page?
- Hierarchy: can a user identify the next action and interpret a risk result immediately?
- Execution: are alignment, typography, spacing, controls, and responsive behavior deliberate?
- Specificity: do page structures fit BaoTin's real tasks and data?
- Restraint: have redundant controls, decorative elements, and unnecessary cards been removed?
- Variety: do distinct tasks have appropriately distinct layouts while sharing one coherent design system?

A visual pass is not complete until functional behavior and the rendered UI have both been checked.

## 14. Reference links

- [Material 3 — Color system and color roles](https://m3.material.io/styles/color/the-color-system)
- [Android Developers — Window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes)
- [Android Developers — Layout and navigation patterns](https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns)
- [Hallmark — Anti-AI-slop redesign skill for Codex](https://github.com/nutlope/hallmark)
- [Impeccable — Context files](https://impeccable.style/docs/context/)
- [Impeccable — Document command](https://impeccable.style/docs/document/)
