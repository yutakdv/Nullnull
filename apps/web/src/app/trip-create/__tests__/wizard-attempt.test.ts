// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest';
import { readAttempt, writeAttempt, type WizardAttempt } from '../wizard-attempt.js';

afterEach(() => {
  vi.restoreAllMocks();
  writeAttempt(null);
  sessionStorage.clear();
});

it('keeps a create attempt in memory when tab storage refuses a write', () => {
  const attempt: WizardAttempt = {
    fingerprint: '{}',
    key: crypto.randomUUID(),
    phase: 'creating',
    picks: [],
    pending: [],
    tripId: null,
  };
  const original = sessionStorage.setItem.bind(sessionStorage);
  vi.spyOn(sessionStorage, 'setItem').mockImplementation((key, value) => {
    if (key === 'nullnull.wizard.attempt.v1') throw new DOMException('Quota exceeded');
    original(key, value);
  });

  writeAttempt(attempt);
  expect(sessionStorage.getItem('nullnull.wizard.attempt.v1')).toBeNull();
  expect(readAttempt()).toEqual(attempt);
});
