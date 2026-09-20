---
aliases:
  - "UI 레이아웃 일관성 구현 계획"
doc_type: plan
status: active
area: frontend
tags:
  - nullnull/plan
  - nullnull/frontend
---

# UI Layout Consistency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify Nullnull's page gutters, top app bar, bottom action safe zones, responsive content widths, and trip-selection UX without changing server contracts or replacing existing screen content.

**Architecture:** Keep screen-specific composition and introduce only semantic layout aliases plus strengthened shared `NavBar` and `BottomCta` contracts. Route-width classification stays in `AppShell`, while copy and interaction fixes remain inside their owning feature. Generated design tokens remain untouched.

**Tech Stack:** React 19, TypeScript, React Router, CSS Modules, Vitest/Testing Library, Playwright.

**Spec:** `docs/design/DESIGN.md` sections 6 and app-shell rules; user-approved UI audit in the current task.

## Global Constraints

- Frontend-only: edit `apps/web/**` and this plan only; never inspect or edit `apps/api/**`, `apps/ai/**`, or `infra/**`.
- Treat `packages/api-client/**`, `packages/contracts/**`, `docs/api/**`, and `docs/contracts/**` as read-only.
- Preserve every pre-existing modification. Do not reset, clean, stage, commit, push, or switch branches.
- Do not edit or regenerate `apps/web/src/design/tokens.css` or `tokens.json`; they already contain user work.
- Keep Pretendard and the existing color system.
- Preserve the explicitly approved 12px top padding on `/feed`.
- Horizontal content gutter is 16px at 360–430px. NavBar is 56px; touch controls are at least 44px.
- Bottom fixed actions own bottom safe-area exactly once and never cover the final focusable element.

## Review Focus

- A secondary action under the primary CTA must retain a 44px target and at least 16px breathing room above the device safe area.
- A 360px viewport and 200% zoom must not gain horizontal scrolling or hide the final focus target behind a fixed action.
- Korean and English trip-selection copy must remain understandable without exposing internal “representative” terminology.
- A pending representative-trip update must announce progress and block competing navigation.
- Entering paste import from the wizard must preserve dates and interests already collected.

---

### Task 1: Semantic layout contract and shared chrome

**Files:**

- Modify: `apps/web/src/styles.css`
- Modify: `apps/web/src/shared/ui/components/NavBar.module.css`
- Modify: `apps/web/src/shared/ui/components/BottomCta.tsx`
- Modify: `apps/web/src/shared/ui/components/BottomCta.module.css`
- Modify: `apps/web/src/shared/ui/components/__tests__/components.test.tsx`
- Modify: `apps/web/src/app/trip-create/TripWizardScreen.module.css`

**Interfaces:**

- Produces semantic CSS aliases for page gutter, app-bar height, content widths, and bottom-action heights.
- Produces `BottomCtaProps.secondaryKind?: 'action' | 'note'` and a `data-secondary-kind` geometry hook.
- Existing call sites without secondary content remain source-compatible.

- [ ] Add failing component tests asserting the fixed marker and explicit `action`/`note` secondary variants.
- [ ] Run `npm run test -- components` and confirm the new secondary-variant assertion fails.
- [ ] Add semantic aliases outside generated tokens:

```css
:root {
  --layout-page-gutter: var(--space-4);
  --layout-app-bar-height: 56px;
  --layout-touch-min: 44px;
  --layout-primary-min: 52px;
  --layout-content-form-max: 560px;
  --layout-content-detail-max: 720px;
  --layout-content-browse-max: 960px;
  --layout-bottom-primary: 110px;
  --layout-bottom-note: 112px;
  --layout-bottom-action: 136px;
}
```

- [ ] Make `NavBar` 56px high, keep 44px controls centered, align the back glyph with the 16px content line, and give title/actions explicit line heights.
- [ ] Add `secondaryKind`, render it as `data-secondary-kind`, and replace fixed secondary height with primary/note/action minimum heights plus `env(safe-area-inset-bottom)`.
- [ ] Reserve matching primary/note/action heights in `TripWizardScreen.module.css`.
- [ ] Run `npm run test -- components wizard-screen import-paste` and confirm all pass.

### Task 2: Route frame widths and page spacing

**Files:**

- Modify: `apps/web/src/app/AppShell.tsx`
- Modify: `apps/web/src/app/AppShell.module.css`
- Modify: `apps/web/src/app/__tests__/app-shell.test.tsx`
- Modify: `apps/web/src/app/trip-select/TripSelectScreen.module.css`
- Modify: `apps/web/src/app/live/LiveScreen.module.css`
- Modify: `apps/web/src/app/post/PostScreen.module.css`
- Modify: `apps/web/src/app/NotFoundScreen.tsx`
- Create: `apps/web/src/app/NotFoundScreen.module.css`

**Interfaces:**

- Produces `contentWidth(pathname): 'form' | 'detail' | 'browse'` inside `AppShell` and `data-content-width` on the scrolling main element.
- Forms/onboarding/profile/select use 560px, trip/detail/edit flows use 720px, feed uses 960px.

- [ ] Add failing shell tests for route width classification and NotFound's styled page frame.
- [ ] Run `npm run test -- app-shell` and confirm the new data-attribute assertion fails.
- [ ] Add width classification without moving route state into CSS or duplicating horizontal screen padding.
- [ ] Normalize `/trips/select` and `/live` top rhythm to 16px while preserving `/feed` at 12px.
- [ ] Add bottom safe-area padding to the sticky post action surface.
- [ ] Style NotFound with the same 16px page gutter, typography, and 44px recovery target.
- [ ] Run `npm run test -- app-shell data-guide post` and confirm all pass.

### Task 3: Trip-selection UX writing and states

**Files:**

- Modify: `apps/web/src/app/trip-select/TripSelectScreen.tsx`
- Modify: `apps/web/src/app/trip-select/TripSelectScreen.module.css`
- Modify: `apps/web/src/i18n/messages.ts`
- Modify: `apps/web/src/app/__tests__/app-shell.test.tsx`
- Modify: `apps/web/e2e/shell.spec.ts`

**Interfaces:**

- Selected preference uses `aria-pressed`; route-current semantics remain exclusive to navigation.
- Pending preference mutation exposes a polite live status and disables the create-trip link via `aria-disabled` plus click prevention.
- Copy uses “피드 기준” / “Feed trip”, and candidate count receives a locale-specific unit.

- [ ] Add failing tests for selected semantics, pending announcement/navigation lock, and KO/EN copy keys.
- [ ] Run the focused app-shell tests and confirm failures against current `aria-current` and copy.
- [ ] Change copy to:

```text
KO: 내 여행 / 여행을 선택하면 피드 기준 여행으로 설정되고 일정 화면으로 이동해요.
KO badge: 피드 기준 / 담아둔 장소 {count}곳
EN: My trips / Choose a trip to use for your feed, then open its itinerary.
EN badge: Feed trip / {count} saved places
```

- [ ] Add `role="status"` pending copy ending in `…`, prevent create navigation while pending, and keep failure recovery actionable.
- [ ] Update keyboard E2E expectations without weakening navigation coverage.
- [ ] Run `npm run test -- app-shell feed`.

### Task 4: Flow integrity, date consistency, and full verification

**Files:**

- Modify: `apps/web/src/app/trip-create/InputMethodStep.tsx`
- Modify: `apps/web/src/app/trip-create/TripWizardScreen.tsx`
- Modify: `apps/web/src/app/trip-create/ImportPasteScreen.tsx`
- Modify: `apps/web/src/app/trip-create/__tests__/wizard-screen.test.tsx`
- Modify: `apps/web/src/app/trip-create/__tests__/import-paste.test.tsx`
- Create: `apps/web/src/shared/i18n/trip-period.ts`
- Create: `apps/web/src/shared/i18n/__tests__/trip-period.test.ts`
- Modify: `apps/web/src/app/feed/FeedScreen.tsx`
- Modify: `apps/web/src/app/trip-select/TripSelectScreen.tsx`
- Modify: `apps/web/src/app/profile/ProfileScreen.tsx`
- Modify: `apps/web/src/shared/ui/components/TripPicker.tsx`
- Modify: `apps/web/e2e/trip-create.spec.ts`
- Modify: `apps/web/e2e/responsive.spec.ts`

**Interfaces:**

- Produces `formatTripPeriod(startDate, endDate, locale, style)` using `Intl.DateTimeFormat` and an en dash.
- Wizard paste navigation carries `WizardDraft` in router state; import falls back to `EMPTY_DRAFT` on direct entry or refresh and merges parsed dates over preserved interests.

- [ ] Add a failing focus test proving the input-method heading receives focus after the transition.
- [ ] Add a failing flow test proving dates/interests survive wizard → paste → confirm.
- [ ] Add failing KO/EN period-format tests and remove raw ISO and `~` rendering.
- [ ] Add `id="wizard-heading"`, pass a typed navigation state to `/start/import`, and merge that state without adding a server field.
- [ ] Replace local period formatters in feed, trip selector, profile, and TripPicker with the shared utility.
- [ ] Run `npm run test -- wizard-screen import-paste app-shell feed profile trip-picker trip-period`.
- [ ] Run Playwright checks for fixed CTA clearance, `/trips/select` keyboard selection, 360px, 200% zoom, KO/EN, and `/start/import` flow preservation.
- [ ] Run `npm run lint -- --quiet`, `npm run typecheck`, `npm run typecheck:e2e`, `npm run test`, and `npm run build`.
- [ ] Run `git diff --check` and confirm `git status --short -- apps/api apps/ai infra` remains empty.

## Execution Rulings

- Execute in the current `frontend` checkout because the requested UI is built on extensive uncommitted user work that an isolated worktree would omit. Cost if wrong: the change must be separated manually during final commit review.
- Do not commit per task because repository instructions require explicit user authorization for commits. Tests and the progress ledger are the task boundaries instead.
- Do not run `verify:ci` because generated `tokens.css` already has user modifications; use focused checks plus full lint/type/test/build without regeneration.
