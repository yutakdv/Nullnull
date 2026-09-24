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
// holding it). Every other site must be in EXEMPT below, with the reason; an
// unlisted uncredited site fails, and so does an exemption that no longer
// matches one, so the list cannot rot into a blanket pass.
//
// What it cannot see: a name taken by destructuring (`const { name } = place`),
// and whether the credit sits in the right element of the file. The per-unit
// render tests (FE-603-T5) hold the second — the post chip and the checklist
// row above it share the expression `place`, and only a render tells them
// apart. A name passed on as a string (a sheet's context line) is judged where
// it is first read, which is where EXEMPT names it.
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
 * Each entry names where the words end up, so a reader can check the claim
 * against the screen.
 */
const EXEMPT: readonly { file: string; expr: string; reason: string }[] = [
  {
    file: 'app/feed/FeedScreen.tsx',
    expr: 'card.primaryPlace',
    reason:
      'the trip picker context line (TripPicker.tsx:145) for the feed card it was opened from, which FeedPostCard credits',
  },
  {
    file: 'app/trip/ItemMoveControls.tsx',
    expr: 'item.place',
    reason:
      'accessible names of the move controls, the aria-live reorder status, and the MoveDaySheet context line (MoveDaySheet.tsx:178) for a stop TripScreen credits',
  },
  {
    file: 'app/trip/ItemMoveControls.tsx',
    expr: 'choice.place',
    reason:
      'the aria-live sentence announcing a replacement; ReplaceSheet credits the option itself',
  },
  {
    file: 'app/trip/RemoveItemControl.tsx',
    expr: 'item.place',
    reason:
      'the remove confirm dialog, its accessible names, and the removal status (TripScreen.tsx:379) for a stop TripScreen credits',
  },
  {
    file: 'app/trip/trip-edit.ts',
    expr: 'item.place',
    reason:
      'the stranded-stop list of the date-range form (TripEditForm.tsx:268), shown only on trip/:tripId/settings, a registered route no screen links to',
  },
  {
    file: 'app/trip/useTripDragReorder.ts',
    expr: 'item.place',
    reason: 'the aria-hidden drag ghost (TripScreen.tsx:512) and the drag announcements',
  },
  {
    file: 'app/trip/useTripDragReorder.ts',
    expr: 'current.item.place',
    reason: 'the drop announcement of a drag, for a stop TripScreen credits',
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
  /** Per file, the expressions a `<PlaceAttribution place={…}>` credits. */
  credited: Map<string, Set<string>>;
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

  const sites: Site[] = [];
  const credited = new Map<string, Set<string>>();
  for (const source of program.getSourceFiles()) {
    if (!shipped(source.fileName)) continue;
    const file = relative(SRC, source.fileName);
    const visit = (node: ts.Node): void => {
      if (ts.isPropertyAccessExpression(node) && node.name.text === 'name') {
        const type = checker.getNonNullableType(
          checker.getTypeAtLocation(node.expression),
        );
        if (type.getProperty('sourceAttribution')) {
          sites.push({
            file,
            expr: normal(node.expression.getText(source)),
            line: source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1,
          });
        }
      }
      if (
        (ts.isJsxSelfClosingElement(node) || ts.isJsxOpeningElement(node)) &&
        node.tagName.getText(source) === 'PlaceAttribution'
      ) {
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
          const set = credited.get(file) ?? new Set<string>();
          for (const element of each) set.add(normal(element.getText(source)));
          credited.set(file, set);
        }
      }
      ts.forEachChild(node, visit);
    };
    visit(source);
  }
  return { sites, credited };
}

function isCredited(found: Scan, site: { file: string; expr: string }): boolean {
  return found.credited.get(site.file)?.has(site.expr) ?? false;
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

  it('credits every site that names a place, or says why not', () => {
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
      const matches = found.sites.filter(
        (site) => site.file === entry.file && site.expr === entry.expr,
      );
      expect(
        matches.length,
        `${entry.file} ${entry.expr} matches no site`,
      ).toBeGreaterThan(0);
      expect(
        isCredited(found, entry),
        `${entry.file} ${entry.expr} is credited now; drop its exemption`,
      ).toBe(false);
    }
  });

  it('finds the credit a parent draws for its child', () => {
    for (const entry of CREDITED_ELSEWHERE) {
      expect(
        isCredited(found, entry.by),
        `${entry.by.file} no longer credits ${entry.by.expr} (${entry.reason})`,
      ).toBe(true);
    }
  });
});
