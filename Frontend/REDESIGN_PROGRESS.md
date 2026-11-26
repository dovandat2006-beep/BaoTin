# BaoTin frontend redesign — final progress

Updated: 2026-11-16. Scope: all **18** targets in the attached redesign directive.

## Status
- Hallmark redesign → independent Impeccable critique → polish → audit/verification completed for every named target.
- Foundation uses a combined independent review. Review scores describe pre-polish snapshots, not final re-scores.
- Initial redesign deployment checkpoint code: `256f8173661018fe3b4ecf98e828a4ec036d6964`.
- PR [#6](https://github.com/vietluccuu/Frontend/pull/6) merged as `7eee94d` after dependency-audit/verify passed. Latest main and local redesign were combined without losing incoming Analytics overview/trends or local changes. Two overlapping files were resolved in a separate worktree; final local pull was fast-forward, unresolved conflicts0. Two untracked previews retained original SHA256.
- Jenkins **Frontend19 SUCCESS**, **FullStack25 SUCCESS**; approved promotion deployed the reviewed code. Independent checks: **11/11 readiness UP**, public HTTP200, all20 runtime web files match Frontend19 archive byte-for-byte.
- Public rendered matrix: **12/12** across320/375/414/768/1024/1440 and actual light/dark. Real guest URL and SMS scans returned200 and rendered service results, without fixtures.

## Stage ledger
H=Hallmark; C=independent critique; P=polish; V=verification. Dated stage artifacts are under `.redesign-artifacts/`.

| Target | H | C | P | V / next |
|---|---|---|---|---|
| AppNav.tsx | done | foundation review done | done | final combined foundation60 pass; earlier target evidence retained |
| TopBar.tsx | done | foundation review done | done | final combined foundation60 pass; earlier target evidence retained |
| App.tsx shell | done | foundation review done | done | final combined foundation60 pass; handler boundary audited |
| ScanScreen.tsx | done | independent A/B30/40 | done: error focus+scroll | full CI lanes passed, see scan-refinement-20261116 |
| ScanComposer.tsx | done | independent A/B recorded | done: ARIA/keyboard/privacy helper | full CI lanes passed |
| RiskResult.tsx | done | independent A/B30/40 | done: consumer details/time/action/full URL | full CI lanes passed |
| RiskBadge.tsx | done; no new visual defect | independent A/B21/24 | done: compact accessible context | contrast all8>4.5, full CI lanes passed |
| LiveSearchScreen.tsx | done | independent A/B28/40,detector0 | done: filters/order/headings/copy | full CI lane pass; live-search-20261116 |
| ScanHistoryScreen.tsx | done | independent A/B27/40,detector0 | done: stale/emptyerror,disclosure,detailorder,deletehierarchy | CIpass;scan-history-20261116 |
| ReportWorkspace.tsx | done | independent A/B29/40,detector0 | done: receipt recovery,evidencecontext,validation,a11y | CIpass+rollback150;report-workspace-20261116 |
| WatchlistScreen.tsx | done | independent A/B29/40; Bbrowserlimit documented | done: pending/recovery/context/confirmations/switch | all CI lanes+12fixture/rollback150 PASS;watchlist-20261116 |
| AccountWorkspace.tsx | done | independent A/B27/40 | done: draft/capability/pending/sessioncontext/tabs | all CI+12quality/rollback150 pass;account-workspace-20261116 |
| AccountSecurityPanel.tsx | done | independent A/B29/40,detector0 | done: focusedtasks,factorchoices,copy,validation,errorfocus | allCI+24quality/rollback150/hashproof pass;account-security-20261116 |
| AccountProviderLinks.tsx | done | independent A/B29/40,detector0 | done: prerequisite,pending,receipt,waitingguard | allCI+12quality/rollback150/hashproof pass;provider-links-20261116 |
| NotificationDevicesPanel.tsx | done | independent A/B31/40,detector0 | done: helpers,inlineerror,receipt,choice semantics | allCI+12quality/rollback150/hashproof pass;notification-devices-20261116 |
| AdminWorkspace.tsx | done | independentA/B31/40,detector0 | done: alignment/livebranchlabel | allCI+12quality/rollback150/hashproof pass;admin-workspace-20261116 |
| AdminUsersScreen.tsx | done | independentA/B31/40,detector0 | done: disclosureARIA,copy,confirm,lockerror | allCI+12quality/rollback150/hashproof pass;admin-users-20261116 |
| AdminModerationScreen.tsx | done | independentA/B32/40,detector0 | done: terminology,refreshscope,freshness | allCI+12quality/rollback150/hashproof pass;admin-moderation-20261116 |

## Final verification
- Typecheck0; Jest **154/154** (22suites); tooling **48/48**; Python package verifier **4/4**; Expo Doctor **20/20**; dependency audit **0 blocking**.
- Web and Android/iOS Hermes exports passed. Native bytecode is not an installable/device acceptance claim.
- Integrated regression: foundation60; scoped quality156; demo54passed+36explicit live-onlyskips; reports25; live Search/no-demo30; admin boundary10; account/OAuth52. Counts are suite executions, not additive unique journeys.
- Existing `npm run web` development workflow started on18081; final current-main Scan/shell journey24passed.
- GitHub CI for published code256f817: dependency-audit and verify SUCCESS (run43903816166).
- One initial public414dark page.goto timeout and a separate PowerShell HEAD timeout were retained, not hidden. Unchanged full public matrix rerun12passed; direct IPv4 HEAD/GET200. No application assertion was removed.

## Preservation boundaries
- Expo React Native, Material3, Roboto, centralized semantic theme roles and Font Awesome retained.
- No backend/Contracts/Infra repository source edits. Existing Jenkins release job was used; only Frontend source pin/approval key changed operationally, with backup. Other job pins, guard and pipeline source stayed unchanged.
- Original auth/session/routing handlers retained; AuthScreen/AuthRecoveryPanel excluded. Existing shell draft behavior predates the attached directive and was preserved.
- App.tsx adds existing `loadPreferences` retry callback prop and memo dependency since pre-Watchlist baseline; this small recovery wiring is disclosed, not called byte-identical/pure styling.
- API/lib/Auth files match merged-main7eee94d; PR6's explicitly requested typed Analytics additions are distinguished from the redesign.
- Report state/version/idempotency/evidence, history snapshots, risk meanings and live/demo boundaries preserved.

## Final artifacts and rollback
Authoritative final index: `.redesign-artifacts/final-20261116/VERIFICATION.txt` (not the obsolete root VERIFICATION.txt).
- MODIFIED_FILE.zip; DIFF_FILE.patch; VERIFICATION.txt; executable ROLLBACK.sh.
- Composite rollback restores merged-main7eee94d, preserving PR6, not an older pre-PR state. Separate probe passed typecheck and136tests/21suites; working source remains redesigned.
- Earlier stage rollback scripts are guarded chronological snapshots; do not apply them directly against superseding shared test files. Final composite is bound to current source hashes.
- Detailed source/evidence audits, source hashes, pre-pull74-file backup, CI logs and public screenshots are retained in the final directory. Historical execution log is preserved there rather than mixed into current status.

## Explicit limits
- Android SDK exists but adb reports no attached devices/emulators. Windows has no iOS simulator. Physical keyboard/safe-area/TalkBack/VoiceOver acceptance remains unperformed.
- Browser fixtures prove client behavior/adapters, not real OAuth consent, privileged Gateway authorization or push delivery. Production guest URL/SMS scan proof is separate and real.
- ESLint is not configured; no ESLint pass is claimed.
- Final documentation-only close-out may be newer than deployed code256f817; it does not change runtime source or imply a second deployment.

## User follow-up — account caption
The authenticated TopBar retains the FontAwesome user icon and accessible account name, but no longer renders “Cá nhân”. Guest “Khách”, account action and touch target64×56 remain unchanged. Local proof: typecheck154Jest/tooling48,foundation60/account52,Web+nativeHermes,Doctor20/20 and all standardCI lanes passed. Rendered375light/1440dark authenticated app bars were inspected. Scoped rollback154/hash proof is under `.redesign-artifacts/topbar-label-20261116/`. This source checkpoint records the verified candidate; final Jenkins/deployment state for the follow-up is recorded in that directory's VERIFICATION.txt and the final evidence index, not predicted by this commit.
