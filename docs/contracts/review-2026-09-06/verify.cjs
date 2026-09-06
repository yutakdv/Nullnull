// Contract review only. No network, application server, or provider calls.
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const toolchain = process.env.NULLNULL_REVIEW_TOOLCHAIN || __dirname;
const deps = createRequire(path.resolve(toolchain, 'package.json'));
const yaml = deps('js-yaml');
const Ajv = deps('ajv/dist/2020').default;
const addFormats = deps('ajv-formats').default;
const root = path.resolve(__dirname, '../../..');
const api = yaml.load(fs.readFileSync(path.join(root, 'docs/api/openapi.yaml'), 'utf8'));
const read = (name) => JSON.parse(fs.readFileSync(path.join(__dirname, name), 'utf8'));
const ajv = new Ajv({ strict: false, allErrors: true, logger: false });
addFormats(ajv);
ajv.addFormat('int64', true);
const eventSchema = JSON.parse(fs.readFileSync(path.join(root, 'docs/contracts/events.schema.json'), 'utf8'));
const validators = new Map();
function validate(name, data) {
  if (!validators.has(name)) {
    validators.set(name, ajv.compile(name === 'events' ? eventSchema : {
      components: api.components, $ref: '#/components/schemas/' + name,
    }));
  }
  const fn = validators.get(name);
  return { valid: Boolean(fn(data)), errors: fn.errors };
}
const checks = [];
function check(id, schema, data, expected) {
  const result = validate(schema, data);
  checks.push({ id, schema, expected, actual: result.valid, passed: result.valid === expected,
    errors: result.valid === expected ? null : result.errors });
}
for (const [name, schema] of Object.entries(api.components.schemas)) {
  (schema.examples || []).forEach((data, i) => check(name + '-example-' + i, name, data, true));
}
for (const c of read('fixtures.json').cases) check(c.id, c.schema, c.data, c.expectedValid);
const schemas = api.components.schemas;
const apply = schemas.ApplyOptimizationDecision.examples[0];
const keep = schemas.KeepOptimizationDecision.examples[0];
const revert = schemas.RevertOptimizationDecision.examples[0];
check('initial-apply', 'InitialOptimizationDecision', apply, true);
check('initial-keep', 'InitialOptimizationDecision', keep, true);
check('initial-rejects-revert', 'InitialOptimizationDecision', revert, false);
check('history-retains-revert', 'OptimizationDecision', revert, true);
const missingWindow = { ...apply }; delete missingWindow.revertUntil;
check('apply-requires-window', 'InitialOptimizationDecision', missingWindow, false);
check('keep-has-no-window', 'InitialOptimizationDecision', { ...keep, revertUntil: apply.revertUntil }, false);
const item = schemas.CreateItemOptimizationRequest.examples[0];
const missingTarget = { ...item }; delete missingTarget.targetItemId;
check('item-requires-target', 'CreateOptimizationRequest', missingTarget, false);
check('item-rejects-day-target', 'CreateOptimizationRequest', { ...item, targetDate: '2026-09-07' }, false);
check('optional-objective', 'CreateOptimizationRequest', (({ objective, ...rest }) => rest)(item), true);
const p = schemas.DataProvenance.examples[0];
const oldP = { ...p }; delete oldP.attributionShort;
check('provenance-backward-compatible', 'DataProvenance', oldP, true);
check('short-null-fallback', 'DataProvenance', { ...p, attributionShort: null }, true);
check('short-over-limit', 'DataProvenance', { ...p, attributionShort: 'a'.repeat(161) }, false);
check('short-empty-rejected', 'DataProvenance', { ...p, attributionShort: '' }, false);
const run = read('fixtures.json').cases[0].data;
const oldRun = { ...run }; delete oldRun.revertAvailability;
check('run-backward-compatible', 'OptimizationRun', oldRun, true);
check('run-unknown-revert-state', 'OptimizationRun', { ...run, revertAvailability: 'CLIENT_CLOCK_READY' }, false);
const initialRef = api.paths['/optimizations/{runId}/decisions'].post.responses['200'].content['application/json'].schema.$ref;
checks.push({ id: 'operation-uses-initial-union', passed: initialRef === '#/components/schemas/InitialOptimizationDecision' });
const revertRef = api.paths['/optimization-decisions/{decisionId}/revert'].post.responses['200'].content['application/json'].schema.$ref;
checks.push({ id: 'revert-operation-stays-specific', passed: revertRef === '#/components/schemas/RevertOptimizationDecision' });
const policy = read('source-link-policy.json');
const sourceCatalog = fs.readFileSync(path.join(root, 'docs/data/SOURCE_CATALOG.md'), 'utf8');
const reasonSection = sourceCatalog.split('## 9. Comparison eligibility reason code')[1].split('## 10. Pair comparison')[0];
const comparisonReasons = new Set(Array.from(reasonSection.matchAll(/^\| `([A-Z_]+)`/gm), (m) => m[1]));
function linkAllowed(value) {
  try {
    const url = new URL(value);
    return policy.schemes.includes(url.protocol) && policy.exactHosts.includes(url.hostname)
      && url.username === '' && url.password === '' && url.port === '';
  } catch { return false; }
}
for (const p of schemas.DataProvenance.examples) {
  checks.push({ id: p.source + '-approved-comparison-reason', passed: comparisonReasons.has(p.comparisonReasonCode) });
  for (const field of ['officialUrl', 'licenseUrl']) {
    checks.push({ id: p.source + '-' + field, passed: p[field] !== null && linkAllowed(p[field]) });
  }
}
for (const value of ['javascript:alert(1)', 'http://data.go.kr/', 'https://data.go.kr.evil.example/',
  'https://evil.data.go.kr/', 'https://user:password@data.go.kr/', 'https://data.go.kr:8443/', '/relative']) {
  checks.push({ id: 'unsafe-link-' + value, passed: !linkAllowed(value) });
}
const probes = read('probes.json').cases.map((c) => {
  const actual = validate(c.schema, c.data);
  return { id: c.id, pmId: c.pmId, schema: c.schema, expectedProductAcceptance: c.expectedProductAcceptance,
    actualContractAcceptance: actual.valid, mismatch: actual.valid !== c.expectedProductAcceptance,
    enforcementLayer: c.enforcementLayer, errors: actual.errors };
});
const output = { apiVersion: api.info.version, synthetic: true,
  contractChecks: { total: checks.length, passed: checks.filter((x) => x.passed).length, checks },
  openProductProbes: probes,
  limitation: 'Schema, type and fixture checks do not execute server state transitions or prove provider usage.' };
console.log(JSON.stringify(output, null, 2));
if (checks.some((x) => !x.passed)) process.exitCode = 1;
