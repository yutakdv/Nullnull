// Every file that reads a sourced record also renders its credit (CMP-ATT-001).
//
// The matrix asks for "DOM/visual coverage 100%", and that number is the trap:
// it is measured against whatever KTO data happens to be on screen, so with the
// catalog gate closed there are zero targets and 100% passes while proving
// nothing. This file measures the code instead — the set of files that read
// `sourceAttribution` and the set that render `DataAttribution` — and fails if
// the first set has a member the second does not, or if either set is empty.
//
// Why a static scan rather than more screen tests: the screen tests each prove
// one place renders a credit, and this session added four of them after finding
// three that did not. What none of them can say is "and there is no ninth
// screen we forgot". A scan answers exactly that, and answers it for a screen
// written next week.
//
// What it cannot do: tell whether the credit is rendered on the right element,
// in a visible place, or for every branch inside the file. The per-screen tests
// do that — trip-screen, post, add-place-screen, candidates-screen,
// must-visit and optimize-setup each pin their own render, and the negative
// controls for those live in the commits that added them. This is the outer
// boundary, not a replacement.
import { readFileSync, readdirSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const SRC = resolve(process.cwd(), 'src');

/** Source files that ship, so stories and tests are not scanned. */
function shippedFiles(): string[] {
  const found: string[] = [];
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, entry.name);
      if (entry.isDirectory()) {
        if (entry.name !== '__tests__') walk(full);
      } else if (
        entry.name.endsWith('.tsx') &&
        !entry.name.endsWith('.stories.tsx') &&
        !entry.name.includes('.test.')
      ) {
        found.push(full);
      }
    }
  };
  walk(SRC);
  return found;
}

/** Strips comments so a mention in prose is not read as code. */
function code(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .filter((line) => !line.trimStart().startsWith('//'))
    .join('\n');
}

interface Scan {
  reads: string[];
  renders: string[];
}

function scan(): Scan {
  const reads: string[] = [];
  const renders: string[] = [];
  for (const file of shippedFiles()) {
    const body = code(readFileSync(file, 'utf8'));
    const name = relative(SRC, file);
    // `.sourceAttribution` rather than the bare word: the type import and the
    // schema name mention it without reading a record.
    if (/\.sourceAttribution\b/.test(body)) reads.push(name);
    if (/<DataAttribution\b/.test(body)) renders.push(name);
  }
  return { reads, renders };
}

describe('CMP-ATT-001 a sourced record is never rendered without its credit', () => {
  const { reads, renders } = scan();

  it('has targets to measure at all', () => {
    // The zero-target case is the whole reason this file exists. "100% of
    // nothing" is the shape of a compliance claim that passes while the rule it
    // names goes unchecked, so an empty scan is a failure rather than a pass.
    expect(
      reads.length,
      'no file reads sourceAttribution — the scan found nothing',
    ).toBeGreaterThan(0);
    expect(renders.length, 'no file renders DataAttribution').toBeGreaterThan(0);
  });

  it('renders a credit in every file that reads one', () => {
    const missing = reads.filter((file) => !renders.includes(file));
    expect(
      missing,
      `these read sourceAttribution but render no DataAttribution: ${missing.join(', ')}`,
    ).toEqual([]);
  });
});
