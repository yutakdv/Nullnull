// Assembled message keys resolve, in both directions.
//
// WHY THIS EXISTS. 29 call sites build a key from a runtime value
// (`t(`trip.lock.${lock}` as MessageKey)`). The cast is the only thing that
// makes that compile, and it checks nothing: the key is a string the compiler
// never compares against `messages`. Two of the 29 carry a runtime guard
// (OptimizationRunScreen.tsx:149 falls back to `run.failure.unknown`,
// problem-message.ts:56 gates on HAS_FIGMA_COPY). The other 27 have none.
//
// WHAT A MISS ACTUALLY DOES, measured rather than assumed. I18nProvider.tsx:121
// is `interpolate(messages[locale][key], values, locale)`, and interpolate
// opens with `if (!values) return template`. So:
//
//   t(key)          missing -> returns undefined -> React renders NOTHING
//   t(key, {...})   missing -> TypeError reading 'replace' of undefined
//
// No assembled key passes values today, so every one of these fails the SILENT
// way: a blank where a label should be, with nothing in the console. That is
// what this file is for — the loud path needs no help.
//
// WHY THE TABLE IS WRITTEN OUT BY HAND. Deriving the value sets automatically
// is the method that failed three times while this was being measured, and each
// failure PASSED QUIETLY rather than erroring:
//
//   - `replace.relation.` looked like it was missing NONE/CHECKING/UNKNOWN,
//     because it was measured against `RelationState` (5 values). The field that
//     call site reads is `RelatedPlace.relation`, which is "EXACT" | "SIMILAR".
//   - `candidates.match.` looked like it was missing EMPTY/READY, from a regex
//     that swept up unrelated `state:` fields.
//   - `ProblemCode` read as 9 values instead of 23, from matching a shorter
//     `code:` union in another schema first.
//
// All three came from measuring an ENUM BY NAME instead of THE TYPE OF THE
// FIELD THE CALL SITE READS. So each row below names its call site, and the
// value set is bound to that field's type via `satisfies` — if the contract
// changes the field, this file stops compiling instead of silently passing.
import { describe, expect, it } from 'vitest';
import type { components } from '@nullnull/api-client';
import { INTEREST_GROUPS } from '../../app/trip-create/wizard.js';
import { messages } from '../messages.js';

type Schemas = components['schemas'];

/** Every value the call site can put into the key, for one prefix. */
interface Coverage {
  /** The key prefix the screen builds. */
  prefix: string;
  /** file:line of the `as MessageKey` this row covers. */
  site: string;
  values: readonly string[];
}

// `error.*.message` is deliberately absent from this table. 17 of the 23
// ProblemCodes have no `.message` key, and that is the design rather than a
// gap: problem-message.ts:55 gates on HAS_FIGMA_COPY and falls back to the
// server's `problem.detail`, so a code without confirmed Figma copy shows a
// real sentence instead of a blank. `error.*.cta` IS covered — it is ungated,
// so a code without one would render nothing.
const COVERAGE: readonly Coverage[] = [
  {
    prefix: 'trip.lock.',
    site: 'LockRow.tsx:137,148,177 · ReplaceSheet.tsx:111',
    values: [
      'MUST_VISIT',
      'DATE',
      'TIME',
      'RESERVATION',
    ] satisfies readonly Schemas['ConstraintType'][],
  },
  {
    prefix: 'candidates.match.',
    site: 'CandidatesScreen.tsx:311',
    // CandidateMatchResult.state. Not RelationState, which shares four of its
    // names and has a fifth.
    values: [
      'EXACT',
      'SIMILAR',
      'NONE',
      'CHECKING',
      'UNKNOWN',
    ] satisfies readonly Schemas['CandidateMatchResult']['state'][],
  },
  {
    prefix: 'replace.relation.',
    site: 'ReplaceSheet.tsx:211',
    // TWO values, not the five of `RelationState`. The call site reads
    // `RelatedPlace.relation`, which the contract narrows to the two a
    // suggestion can actually be. Adding the other three here would pin copy
    // for states this screen cannot receive — and would make the reverse
    // direction below demand keys nothing renders.
    values: ['EXACT', 'SIMILAR'] satisfies readonly Schemas['RelatedPlace']['relation'][],
  },
  {
    prefix: 'deletion.status.',
    site: 'DeletionSection.tsx:144',
    values: [
      'ACCEPTED',
      'RUNNING',
      'COMPLETED',
      'PARTIAL_FAILED',
      'FAILED',
    ] satisfies readonly Schemas['DeletionRequestStatus']['status'][],
  },
  {
    prefix: 'profile.history.scope.',
    site: 'ProfileScreen.tsx:304',
    values: ['ITEM', 'DAY', 'TRIP'] satisfies readonly Schemas['OptimizationScope'][],
  },
  {
    prefix: 'profile.history.decision.',
    site: 'ProfileScreen.tsx:291',
    // The decision verbs, which differ from the statuses they produce:
    // APPLY -> APPLIED, KEEP -> KEPT, REVERT -> REVERTED.
    values: ['APPLY', 'KEEP', 'REVERT'],
  },
  {
    prefix: 'state.',
    site: 'DataGuideScreen.tsx:37',
    values: [
      'LIVE',
      'FORECAST',
      'REPLAY',
      'QUALITATIVE',
      'STALE',
      'UNAVAILABLE',
    ] satisfies readonly Schemas['SourceState'][],
  },
  {
    prefix: 'dataGuide.state.',
    site: 'DataGuideScreen.tsx:65',
    values: [
      'LIVE',
      'FORECAST',
      'REPLAY',
      'QUALITATIVE',
      'STALE',
      'UNAVAILABLE',
    ] satisfies readonly Schemas['SourceState'][],
  },
  {
    prefix: 'trip.planning.',
    site: 'TripEditForm.tsx:319',
    values: [
      'NOTHING',
      'MUST_VISIT_ONLY',
      'MOSTLY_PLANNED',
    ] satisfies readonly Schemas['PlanningLevel'][],
  },
  {
    prefix: 'interest.',
    site: 'TripWizardScreen.tsx:372 · InterestsSection.tsx:172',
    // FE-owned vocabulary: the contract carries it as
    // `x-nullnull-interest-codes`, an extension, so the generated client has no
    // union to bind to. INTEREST_GROUPS is the list the chips render from, which
    // makes it the domain of this key by construction.
    values: INTEREST_GROUPS.flatMap((group) => [...group.codes]),
  },
  {
    prefix: 'wizard.interests.',
    site: 'TripWizardScreen.tsx:364 · InterestsSection.tsx:164',
    values: INTEREST_GROUPS.map((group) => group.id),
  },
  {
    prefix: 'manual.daypart.',
    site: 'ManualStopsStep.tsx:134 · ConfirmStopsStep.tsx:125',
    // wizard.ts:68 `type Daypart`. Local, not from the contract: the wizard
    // draft is client state and `startTime` is never sent.
    values: ['MORNING', 'AFTERNOON'],
  },
  {
    prefix: 'trip.error.',
    site: 'TripEditForm.tsx:331',
    // trip-edit.ts draftError(), which returns one of exactly these or null.
    values: ['title-empty', 'title-too-long', 'range-reversed', 'range-too-long'],
  },
];

const LOCALES = ['ko-KR', 'en-US'] as const;

describe('every assembled message key resolves', () => {
  // Non-empty guards first. Every assertion below is over a loop, so an empty
  // table or an empty value set would make them all pass vacuously — which is
  // exactly how a coverage check ends up measuring nothing while staying green.
  it('has rows to check', () => {
    expect(COVERAGE.length).toBeGreaterThan(0);
  });

  it('gives every row at least one value', () => {
    for (const { prefix, values } of COVERAGE) {
      expect(values.length, `${prefix} has no values to check`).toBeGreaterThan(0);
    }
  });

  it.each(LOCALES)('%s has a key for every value a screen can assemble', (locale) => {
    const table = messages[locale] as Record<string, string | undefined>;
    const missing: string[] = [];
    for (const { prefix, values, site } of COVERAGE) {
      for (const value of values) {
        if (table[`${prefix}${value}`] === undefined) {
          missing.push(`${prefix}${value} (${site})`);
        }
      }
    }
    // A missing key renders as nothing at all, so this is the direction that
    // catches a screen going quietly blank.
    expect(missing).toEqual([]);
  });

  it.each(LOCALES)(
    '%s has no key under these prefixes that no value reaches',
    (locale) => {
      const declared = new Set(
        COVERAGE.flatMap(({ prefix, values }) =>
          values.map((value) => `${prefix}${value}`),
        ),
      );
      // Longest prefix first: `profile.history.decision.APPLY` also starts with
      // `profile.history.`, and attributing it to the shorter row would report it
      // as dead copy.
      const prefixes = [...COVERAGE].sort((a, b) => b.prefix.length - a.prefix.length);
      const dead: string[] = [];
      for (const key of Object.keys(messages[locale])) {
        const owner = prefixes.find((row) => key.startsWith(row.prefix));
        if (owner === undefined) continue;
        // The remainder has to be a single segment: `profile.history.scope.ITEM`
        // belongs to the scope row, while `profile.history.title` is ordinary
        // copy that happens to share a prefix with no value behind it.
        const rest = key.slice(owner.prefix.length);
        if (rest.includes('.')) continue;
        // ...and it has to LOOK like a value slot. These prefixes are also used
        // for ordinary literal copy: `t('trip.lock.apply', { lock })` and
        // `t('candidates.match.error')` are direct calls with a written-out key,
        // not assembled ones, and reporting them as dead would be a false
        // positive that pushes the next reader to delete live copy. Measured, not
        // assumed: all 12 the first run flagged are `t('<literal>')` call sites.
        //
        // The two are told apart by shape, because every value in the table above
        // comes from a contract enum or a code vocabulary and those are
        // SCREAMING_CASE, while hand-written sibling copy is camelCase. The one
        // row whose values are not (`trip.error.*`, kebab-case from draftError)
        // has no sibling copy under its prefix, so nothing is skipped by this.
        if (!/^[A-Z][A-Z0-9_]*$/.test(rest) && !owner.prefix.startsWith('trip.error.')) {
          continue;
        }
        if (!declared.has(key)) dead.push(`${key} (${owner.prefix} in ${owner.site})`);
      }
      // The other direction: copy nothing can reach is copy that was translated,
      // reviewed and shipped for a value the screen never produces.
      expect(dead).toEqual([]);
    },
  );
});
