// Every place a screen names carries its credit (CMP-ATT-001).
//
// The matrix asks for "DOM/visual coverage 100%", and that number is the trap:
// it is measured against whatever KTO data happens to be on screen, so with the
// catalog gate closed there are zero targets and 100% passes while proving
// nothing. This file measures the code instead, and fails if it finds nothing
// to measure.
//
// WHAT IT MEASURES, AND WHY IT CHANGED. The first version compared two sets of
// FILES: those that read `.sourceAttribution` and those that render
// DataAttribution. A file passed as a whole, so a second row in the same file
// could name a place with no credit — the trip wizard's manual step credited
// its search results and not the stops they became — and a file that showed
// places without ever reading `sourceAttribution` (the paste import) was not a
// target at all. Once screens drew credits through PlaceAttribution, no screen
// read `.sourceAttribution` any more and that scan measured zero files, which
// its own guard below refused.
//
// This version asks the type checker. A SITE is every `.name` read on a value
// whose type carries `sourceAttribution` — a PlaceSummary or PlaceDetail,
// whatever the variable is called. A site is credited when the same file
// renders `<PlaceAttribution place={…}>` for the same expression (or an array
// holding it). Every other site must be in EXEMPT below, with the reason.
//
// Matching is by (file, expression), so two units that share an expression —
// the post chip and the checklist row above it are both `place` — are one
// group. COUNTS pins how many sites and credits each group has: a new site in
// an exempt file, or a unit whose credit is deleted while a sibling keeps the
// same expression credited, changes a count and turns this red, and a person
// re-reads the group before updating the number. The per-unit render tests
// (FE-603-T5) are what tell sharing units apart; this is the outer boundary.
//
// What it cannot see: a name taken by destructuring (`const { name } = place`),
// and server prose that names a place (a proposal's `summary`) — neither is a
// `.name` read. A name passed on as a string (a sheet's context line) is judged
// where it is first read, which is where EXEMPT names it.
import { relative, resolve } from 'node:path';
import ts from 'typescript';
import { beforeAll, describe, expect, it } from 'vitest';

const ROOT = process.cwd();
const SRC = resolve(ROOT, 'src');

interface Site {
  file: string;
  expr: string;
  line: number;
}

/**
 * Sites that name a place without drawing its credit, and why that is right.
 *
 * The rule, as the R4 coordinator wrote it:
 *
 *   장소를 보여 주는 단위는 그 장소의 출처를 그립니다. 목록 행, 카드, 장소를
 *   대표하는 칩이나 버튼, 지도 표지 묶음, 최적화 제안 카드, 상세 머리가 여기에
 *   듭니다.
 *   UI 피드백은 제외합니다. 상태/aria-live 문구, 확인 대화상자, 이미 출처가
 *   붙은 카드를 가리키는 시트의 문맥 줄, 드래그 잔상이 여기에 듭니다. 제외한
 *   자리는 test에 이유와 함께 명시 목록으로 둡니다. 그래야 새 자리가 조용히
 *   빠지지 않습니다.
 *
 * Each reason names its category from that rule and where the words end up, so
 * a reader can check the claim against the screen.
 */
const EXEMPT: readonly { file: string; expr: string; reason: string }[] = [
  {
    file: 'app/trip-create/wizard-attempt.ts',
    expr: '(pickasPendingPick).place',
    reason:
      'storage validation only: checks a recovered place name is a string; the recovery screen credits every rendered place',
  },
  {
    file: 'app/feed/FeedScreen.tsx',
    expr: 'card.primaryPlace',
    reason:
      'sheet context line: the trip picker (TripPicker.tsx:145) names the feed card it was opened from, which FeedPostCard credits',
  },
  {
    file: 'app/trip/ItemMoveControls.tsx',
    expr: 'item.place',
    reason:
      'status and accessible names: the move controls’ labels, the aria-live reorder status, and the MoveDaySheet context line (MoveDaySheet.tsx:178), for a stop TripScreen credits',
  },
  {
    file: 'app/trip/ItemMoveControls.tsx',
    expr: 'choice.place',
    reason:
      'status: the aria-live sentence announcing a replacement; ReplaceSheet credits each option beside its button',
  },
  {
    file: 'app/trip/RemoveItemControl.tsx',
    expr: 'item.place',
    reason:
      'confirm dialog and status: the remove dialog, its accessible names, and the removal status (TripScreen.tsx:379), for a stop TripScreen credits',
  },
  {
    file: 'app/trip/trip-edit.ts',
    expr: 'item.place',
    reason:
      'confirmation notice: the date-range form’s list of stops a shorter range would drop (TripEditForm.tsx:268), shown before the change is confirmed, like a confirm dialog',
  },
  {
    file: 'app/trip/useTripDragReorder.ts',
    expr: 'item.place',
    reason:
      'status: the aria-live sentence announcing where a dragged stop landed, for a stop TripScreen credits',
  },
  {
    file: 'app/trip/useTripDragReorder.ts',
    expr: 'current.item.place',
    reason:
      'drag ghost: the aria-hidden preview that follows the pointer (TripScreen.tsx:512)',
  },
];

/**
 * Sites credited by another file. Not an exemption: the credit exists, it is
 * just drawn by the parent, and the test checks that it still is.
 */
const CREDITED_ELSEWHERE: readonly {
  file: string;
  expr: string;
  by: { file: string; expr: string };
  reason: string;
}[] = [
  {
    file: 'app/live/KakaoLiveMap.tsx',
    expr: 'selectedPlace',
    by: { file: 'app/live/LiveScreen.tsx', expr: 'selectedPlace.data' },
    reason:
      'a marker is a map overlay and holds no link; LiveScreen draws the credits under the map',
  },
];

/**
 * How many `.name` sites and `<PlaceAttribution place>` credits each
 * (file, expression) group has.
 *
 * A change here is a prompt, not a formality: open the file, find the unit the
 * new or missing site belongs to, and decide whether it shows a place (credit
 * it) or is UI feedback (say so in EXEMPT) before touching the number.
 */
const COUNTS: Record<string, { sites: number; credits: number }> = {
  'app/feed/FeedScreen.tsx :: card.primaryPlace': { sites: 1, credits: 0 },
  'app/live/KakaoLiveMap.tsx :: selectedPlace': { sites: 1, credits: 0 },
  'app/live/LivePlaceScreen.tsx :: detail.data.place': { sites: 1, credits: 1 },
  'app/live/LivePlaceScreen.tsx :: item.place': { sites: 1, credits: 1 },
  'app/live/LiveScreen.tsx :: item.place': { sites: 3, credits: 1 },
  'app/live/LiveScreen.tsx :: place': { sites: 3, credits: 1 },
  'app/live/LiveScreen.tsx :: selectedPlace.data': { sites: 0, credits: 1 },
  'app/optimize/OptimizeSetupScreen.tsx :: item.place': { sites: 1, credits: 1 },
  'app/optimize/ProposalCard.tsx :: places.places[]': { sites: 0, credits: 1 },
  'app/post/PostCreateScreen.tsx :: place': { sites: 2, credits: 2 },
  'app/post/PostScreen.tsx :: place': { sites: 1, credits: 1 },
  'app/trip-create/ConfirmStopsStep.tsx :: stop.place': { sites: 2, credits: 1 },
  'app/trip-create/ImportPasteScreen.tsx :: item.place': { sites: 2, credits: 1 },
  'app/trip-create/ImportPasteScreen.tsx :: place': { sites: 1, credits: 1 },
  'app/trip-create/ManualStopsStep.tsx :: place': { sites: 2, credits: 1 },
  'app/trip-create/ManualStopsStep.tsx :: stop.place': { sites: 3, credits: 1 },
  'app/trip-create/MustVisitScreen.tsx :: place': { sites: 5, credits: 2 },
  'app/trip-create/RecommendedDraftStep.tsx :: stop.place': { sites: 2, credits: 1 },
  'app/trip-create/wizard-attempt.ts :: (pickasPendingPick).place': {
    sites: 1,
    credits: 0,
  },
  'app/trip/AddPlaceScreen.tsx :: place': { sites: 3, credits: 1 },
  'app/trip/CandidatesScreen.tsx :: candidate.place': { sites: 3, credits: 1 },
  'app/trip/ItemMoveControls.tsx :: choice.place': { sites: 1, credits: 0 },
  'app/trip/ItemMoveControls.tsx :: item.place': { sites: 9, credits: 0 },
  'app/trip/RemoveItemControl.tsx :: item.place': { sites: 4, credits: 0 },
  'app/trip/ReplaceSheet.tsx :: item.place': { sites: 1, credits: 1 },
  'app/trip/ReplaceSheet.tsx :: option.place': { sites: 1, credits: 1 },
  'app/trip/TripScreen.tsx :: item.place': { sites: 3, credits: 2 },
  'app/trip/trip-edit.ts :: item.place': { sites: 1, credits: 0 },
  'app/trip/useTripDragReorder.ts :: current.item.place': { sites: 1, credits: 0 },
  'app/trip/useTripDragReorder.ts :: item.place': { sites: 2, credits: 0 },
  'shared/ui/components/CandidateCard.tsx :: candidate.place': { sites: 1, credits: 1 },
  'shared/ui/components/FeedPostCard.tsx :: primaryPlace': { sites: 1, credits: 1 },
  'shared/ui/components/TripItemCard.tsx :: item.place': { sites: 2, credits: 1 },
};

/**
 * Elements a credit link may not sit inside: a control's children are
 * presentational to a screen reader, so the credit is no link there, and the
 * control takes the click (FE-603-T11).
 *
 * How the list is drawn, so it can be redrawn:
 *   - the elements themselves: `button` and `a`;
 *   - react-router's `Link`, which renders an `a` — the only link-like
 *     component `src` imports from react-router (`NavLink` and `Form` are not
 *     imported; checked with grep over `import { … } from 'react-router'`);
 *   - any element whose `role` makes it one control;
 *   - any component in `src` that renders its `children`, or spreads props
 *     that can carry children, inside one of the above — found by the scan
 *     itself (`wrappers`), not listed, so a wrapper written tomorrow is a
 *     control without an edit here. None exists today: the three components
 *     that render `children` (ItemMoveControls, LiveBottomSheet, I18nProvider)
 *     render them outside any control, and the three that spread props onto a
 *     button (Chip, LockControl, TripAddButton) type them without `children`.
 *     A grep for spread props missed those three at first — it read each tag
 *     on one line and their attributes span several — which is why the scan
 *     finds wrappers rather than a list naming them.
 */
const CONTROL_TAGS = new Set(['button', 'a', 'Link']);
const CONTROL_ROLE = /radio|button|option|checkbox|link|tab|menuitem|switch/;

/** `item.place?.name` and `item.place!.name` are the same site as `item.place.name`. */
function normal(text: string): string {
  return text.replace(/[?!]/g, '').replace(/\s+/g, '');
}

function shipped(fileName: string): boolean {
  return (
    fileName.startsWith(SRC) &&
    !/\/__tests__\/|\/shared\/testing\/|\.stories\.tsx$|\.test\.tsx?$/.test(fileName)
  );
}

interface Scan {
  sites: Site[];
  /** Per `file :: expr`, the site and credit counts. */
  groups: Map<string, { sites: number; credits: number }>;
  /** Credit elements (PlaceAttribution or DataAttribution) found at all. */
  creditElements: number;
  /** Credit elements drawn inside a control, as `file:line Tag in control`. */
  nested: string[];
  /** Components found to render their children or props inside a control. */
  wrappers: string[];
}

function key(file: string, expr: string): string {
  return `${file} :: ${expr}`;
}

function scan(): Scan {
  const parsed = ts.getParsedCommandLineOfConfigFile(
    resolve(ROOT, 'tsconfig.json'),
    {},
    {
      ...ts.sys,
      onUnRecoverableConfigFileDiagnostic: (diagnostic) => {
        throw new Error(ts.flattenDiagnosticMessageText(diagnostic.messageText, '\n'));
      },
    },
  );
  if (!parsed) throw new Error('tsconfig.json could not be read');
  const program = ts.createProgram(parsed.fileNames, parsed.options);
  const checker = program.getTypeChecker();

  const found: Scan = {
    sites: [],
    groups: new Map(),
    creditElements: 0,
    nested: [],
    wrappers: [],
  };
  const sources = program.getSourceFiles().filter((source) => shipped(source.fileName));
  const wrappers = new Set<string>();

  /** A control: see CONTROL_TAGS for how the set is drawn. */
  const isControlIn =
    (source: ts.SourceFile) =>
    (element: ts.JsxOpeningLikeElement): boolean => {
      const tag = element.tagName.getText(source);
      return (
        CONTROL_TAGS.has(tag) ||
        wrappers.has(tag) ||
        element.attributes.properties.some(
          (attribute) =>
            ts.isJsxAttribute(attribute) &&
            attribute.name.getText(source) === 'role' &&
            attribute.initializer !== undefined &&
            CONTROL_ROLE.test(attribute.initializer.getText(source)),
        )
      );
    };

  /** The component a node is written in: its function or variable name. */
  const componentOf = (node: ts.Node): string | null => {
    for (let at: ts.Node | undefined = node.parent; at; at = at.parent) {
      if (ts.isFunctionDeclaration(at) && at.name) return at.name.text;
      if (ts.isVariableDeclaration(at) && ts.isIdentifier(at.name)) return at.name.text;
    }
    return null;
  };

  // Wrappers first, until no new one appears: a component that renders its
  // children inside another wrapper is found on the next round.
  for (let grew = true; grew; ) {
    grew = false;
    for (const source of sources) {
      const isControl = isControlIn(source);
      const mark = (node: ts.Node) => {
        const name = componentOf(node);
        if (name && !wrappers.has(name)) {
          wrappers.add(name);
          grew = true;
        }
      };
      /**
       * A control element with a spread whose type can carry children. Chip,
       * LockControl and TripAddButton spread their props onto a button but
       * take them as Omit<…, 'children'>, so no credit can reach them.
       */
      const spreadsChildren = (element: ts.JsxOpeningLikeElement): boolean =>
        isControl(element) &&
        element.attributes.properties.some(
          (attribute) =>
            ts.isJsxSpreadAttribute(attribute) &&
            checker
              .getNonNullableType(checker.getTypeAtLocation(attribute.expression))
              .getProperty('children') !== undefined,
        );
      const walk = (node: ts.Node, inside: boolean): void => {
        if (
          inside &&
          ts.isJsxExpression(node) &&
          node.expression &&
          /^(props\.)?children$/.test(normal(node.expression.getText(source)))
        ) {
          mark(node);
        }
        // A control that takes its children from a spread — `<button
        // {...props}>` and the self-closing `<button {...props} />` alike —
        // makes its component a wrapper when that spread can carry children.
        if (
          (ts.isJsxElement(node) || ts.isJsxSelfClosingElement(node)) &&
          spreadsChildren(ts.isJsxElement(node) ? node.openingElement : node)
        ) {
          mark(node);
        }
        if (ts.isJsxElement(node)) {
          const opening = node.openingElement;
          const control = isControl(opening);
          walk(opening, inside);
          for (const child of node.children) walk(child, inside || control);
          return;
        }
        ts.forEachChild(node, (child) => {
          walk(child, inside);
        });
      };
      walk(source, false);
    }
  }
  found.wrappers = [...wrappers].sort();
  const group = (file: string, expr: string) => {
    const entry = found.groups.get(key(file, expr)) ?? { sites: 0, credits: 0 };
    found.groups.set(key(file, expr), entry);
    return entry;
  };

  for (const source of sources) {
    const file = relative(SRC, source.fileName);
    const lineOf = (node: ts.Node) =>
      source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1;

    const isControl = isControlIn(source);

    /** `controls` is the stack of control elements this node sits inside. */
    const visit = (
      node: ts.Node,
      controls: readonly ts.JsxOpeningLikeElement[],
    ): void => {
      if (ts.isPropertyAccessExpression(node) && node.name.text === 'name') {
        const type = checker.getNonNullableType(
          checker.getTypeAtLocation(node.expression),
        );
        if (type.getProperty('sourceAttribution')) {
          const expr = normal(node.expression.getText(source));
          found.sites.push({ file, expr, line: lineOf(node) });
          group(file, expr).sites += 1;
        }
      }

      if (ts.isJsxSelfClosingElement(node) || ts.isJsxOpeningElement(node)) {
        const tag = node.tagName.getText(source);
        if (tag === 'PlaceAttribution' || tag === 'DataAttribution') {
          found.creditElements += 1;
          const control = controls.at(-1);
          if (control) {
            found.nested.push(
              `${file}:${String(lineOf(node))} ${tag} in <${control.tagName.getText(source)}>`,
            );
          }
        }
        if (tag === 'PlaceAttribution') {
          for (const attribute of node.attributes.properties) {
            if (
              !ts.isJsxAttribute(attribute) ||
              attribute.name.getText(source) !== 'place' ||
              !attribute.initializer ||
              !ts.isJsxExpression(attribute.initializer) ||
              !attribute.initializer.expression
            ) {
              continue;
            }
            const value = attribute.initializer.expression;
            const each = ts.isArrayLiteralExpression(value) ? value.elements : [value];
            for (const element of each) {
              group(file, normal(element.getText(source))).credits += 1;
            }
          }
        }
      }

      // Only an element's children are inside it; its own attributes are not.
      if (ts.isJsxElement(node)) {
        visit(node.openingElement, controls);
        const inner = isControl(node.openingElement)
          ? [...controls, node.openingElement]
          : controls;
        for (const child of node.children) visit(child, inner);
        return;
      }
      ts.forEachChild(node, (child) => {
        visit(child, controls);
      });
    };

    visit(source, []);
  }
  return found;
}

function isCredited(found: Scan, site: { file: string; expr: string }): boolean {
  return (found.groups.get(key(site.file, site.expr))?.credits ?? 0) > 0;
}

describe('FE-603-T4 CMP-ATT-001 a sourced place is never shown without its credit', () => {
  let found: Scan;
  beforeAll(() => {
    found = scan();
  }, 60_000);

  it('has targets to measure at all', () => {
    // The zero-target case is the whole reason this file exists. "100% of
    // nothing" is the shape of a compliance claim that passes while the rule it
    // names goes unchecked, so an empty scan is a failure rather than a pass —
    // on either side: no sites, or no credits to match them against.
    expect(found.sites.length, 'no site names a sourced place').toBeGreaterThan(0);
    expect(
      found.sites.filter((site) => isCredited(found, site)).length,
      'no site is credited by a PlaceAttribution',
    ).toBeGreaterThan(0);
  });

  it('BA-092-T17 credits every site that names a place, or says why not', () => {
    const missing = found.sites
      .filter((site) => !isCredited(found, site))
      .filter(
        (site) =>
          !EXEMPT.some((entry) => entry.file === site.file && entry.expr === site.expr) &&
          !CREDITED_ELSEWHERE.some(
            (entry) => entry.file === site.file && entry.expr === site.expr,
          ),
      )
      .map((site) => `${site.file}:${String(site.line)} ${site.expr}.name`);
    expect(
      missing,
      'these name a place with no PlaceAttribution for it in the same file. Credit the unit, or add it to EXEMPT with the reason if it is UI feedback.',
    ).toEqual([]);
  });

  it('keeps every exemption pointed at a real, uncredited site', () => {
    // An exemption that no longer matches anything would silently cover the
    // next site someone writes with the same file and expression.
    for (const entry of [...EXEMPT, ...CREDITED_ELSEWHERE]) {
      expect(
        found.groups.get(key(entry.file, entry.expr))?.sites ?? 0,
        `${entry.file} ${entry.expr} matches no site`,
      ).toBeGreaterThan(0);
      expect(
        isCredited(found, entry),
        `${entry.file} ${entry.expr} is credited now; drop its exemption`,
      ).toBe(false);
    }
  });

  it('BA-092-T17 finds the credit a parent draws for its child', () => {
    for (const entry of CREDITED_ELSEWHERE) {
      expect(
        isCredited(found, entry.by),
        `${entry.by.file} no longer credits ${entry.by.expr} (${entry.reason})`,
      ).toBe(true);
    }
  });

  it('BA-092-T17 has the sites and credits COUNTS pins, group by group', () => {
    // (file, expression) matching cannot see a second site in an exempt file,
    // or one unit losing its credit while a sibling keeps the expression
    // credited. A count can. Re-read the group before changing a number here.
    expect(Object.fromEntries([...found.groups].sort())).toEqual(COUNTS);
  });
});

describe('FE-603-T11 CMP-ATT-001 a credit link is never inside a control', () => {
  let found: Scan;
  beforeAll(() => {
    found = scan();
  }, 60_000);

  it('draws no credit inside a button, a link or a selection control', () => {
    // A button's or a radio's children are presentational, so a credit there
    // is no link to a screen reader, and the control takes the click. The
    // guard is only as good as the number of credits it looked at.
    expect(found.creditElements, 'no credit element was found at all').toBeGreaterThan(0);
    expect(found.nested).toEqual([]);
    // Today no component wraps its children in a control; if one appears, it
    // is a control above and shows up here, where someone reads it.
    expect(found.wrappers).toEqual([]);
  });
});
