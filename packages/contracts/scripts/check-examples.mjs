#!/usr/bin/env node
// Every response example in the contract must (a) validate against the schema it is
// declared under and (b) equal the fixture it was derived from, where one exists.
//
// (a) stops an example from promising a shape the schema forbids: a client built from
// a bad example fails at integration, not at lint time. (b) stops the spec and the
// approved mocks from drifting apart, which would leave Frontend building against one
// truth and Backend testing against another.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { load } from 'js-yaml';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const api = load(readFileSync(resolve(ROOT, 'docs/api/openapi.yaml'), 'utf8'));

// Examples that were derived from an approved fixture, and must stay equal to it.
const FIXTURE_OF = {
  trips: 'trips/trip-page.json',
  noTrips: 'trips/trip-page-empty.json',
  createdTrip: 'trips/trip-detail-created.json',
  scheduledTrip: 'trips/trip-detail-scheduled.json',
  updatedTrip: 'trips/trip-detail-scheduled.json',
  interestsReplaced: 'trips/trip-detail-interests.json',
  notFound: 'problems/not-found.json',
  statusExpired: 'problems/deletion-status-expired.json',
  feedPage: 'feed/page.json',
  emptyFeed: 'feed/page-empty.json',
  post: 'posts/post-detail.json',
  savedPost: 'posts/post-detail-saved.json',
  candidates: 'candidates/candidate-page.json',
  noCandidates: 'candidates/candidate-page-empty.json',
  alreadyACandidate: 'candidates/save-result-duplicate.json',
  candidateCreated: 'candidates/save-result-created.json',
  alreadySaved: 'posts/saved-post-state-duplicate.json',
  newlySaved: 'posts/saved-post-state.json',
};

const ajv = new Ajv2020({ strict: false, allErrors: true, logger: false });
addFormats(ajv);
ajv.addFormat('int64', true);

const errors = [];
let checked = 0;
let pinned = 0;

/** Every (schema, examples, label) triple in the document, whatever media type carries it. */
function* exampleSites(api) {
  for (const [path, item] of Object.entries(api.paths ?? {})) {
    for (const [method, op] of Object.entries(item ?? {})) {
      if (!op || typeof op !== 'object' || !op.responses) continue;
      for (const [code, response] of Object.entries(op.responses)) {
        for (const [type, media] of Object.entries(response?.content ?? {})) {
          if (media?.examples && media.schema) {
            yield [media, `${method.toUpperCase()} ${path} ${code} (${type})`];
          }
        }
      }
    }
  }
  // Shared responses are referenced by many operations, so an example here is the one place a
  // reusable error's code can be pinned at all - a $ref cannot carry a sibling example.
  for (const [name, response] of Object.entries(api.components?.responses ?? {})) {
    for (const [type, media] of Object.entries(response?.content ?? {})) {
      if (media?.examples && media.schema) {
        yield [media, `components.responses.${name} (${type})`];
      }
    }
  }
}

{
  for (const [media, label] of exampleSites(api)) {
    {
      for (const [name, example] of Object.entries(media.examples)) {
        if (!('value' in (example ?? {}))) continue;
        checked += 1;
        const where = `${label} examples.${name}`;

        let validate;
        try {
          validate = ajv.compile({ components: api.components, ...media.schema });
        } catch (cause) {
          errors.push(`${where}: cannot compile its schema (${cause.message})`);
          continue;
        }
        if (!validate(example.value)) {
          const first = validate.errors?.[0];
          errors.push(`${where}: does not satisfy its schema (${first?.instancePath || '/'} ${first?.message})`);
        }

        const fixture = FIXTURE_OF[name];
        if (fixture) {
          pinned += 1;
          const onDisk = JSON.parse(
            readFileSync(resolve(ROOT, 'packages/contracts/fixtures', fixture), 'utf8'),
          );
          if (JSON.stringify(example.value) !== JSON.stringify(onDisk)) {
            errors.push(`${where}: drifted from packages/contracts/fixtures/${fixture}`);
          }
        }
      }
    }
  }
}

for (const line of errors) console.error(line);
if (errors.length) {
  console.error(`contract_examples=invalid failures=${errors.length}`);
  process.exit(1);
}
console.log(`contract_examples=valid checked=${checked} fixture_pinned=${pinned}`);
