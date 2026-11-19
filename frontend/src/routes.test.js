// Run with: npm test   (Node's built-in test runner, no extra dependencies)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { along, splitAt } from './routes.js';

const L = [[19, 72.8], [19, 72.81], [19.01, 72.81]]; // two segments: east, then north

test('along: endpoints and clamping', () => {
  assert.deepEqual(along(L, 0).pos, L[0]);
  assert.deepEqual(along(L, 1).pos, L[2]);
  assert.deepEqual(along(L, -5).pos, L[0]);
  assert.deepEqual(along(L, 9).pos, L[2]);
});

test('along: rest starts at the current position and ends at the destination', () => {
  const { pos, rest } = along(L, 0.25);
  assert.deepEqual(rest[0], pos);
  assert.deepEqual(rest.at(-1), L[2]);
  assert.ok(pos[1] > 72.8 && pos[1] < 72.81 && pos[0] === 19, 'quarter way is on the first segment');
});

test('along: single-point line does not crash', () => {
  assert.deepEqual(along([[19, 72.8]], 0.5).pos, [19, 72.8]);
});

test('splitAt: both legs share the point nearest the stop', () => {
  const [a, b] = splitAt(L, [19.0001, 72.8101]);
  assert.deepEqual(a, L.slice(0, 2));
  assert.deepEqual(b, L.slice(1));
});
