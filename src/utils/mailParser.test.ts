import test from 'node:test';
import assert from 'node:assert/strict';
import {
  AUTO_POLL_INTERVAL_MS,
  TWO_HOURS_MS,
  clampFloatingPosition,
  extractOtpCode,
  formatRemainingTime,
  getNextMailboxIndex,
  hasNewMailCode,
  isAutoReadActive,
  parseBulkMailboxInput,
  parseMailboxInput,
  selectLatestIncomingMailboxId,
  validateMailboxFields,
} from './mailParser';

test('parseMailboxInput parses 4-part pipe format (email|password|refresh_token|client_id)', () => {
  const res = parseMailboxInput('tommiel@hotmail.com|bV1emc|M.C518_BAY.token123|client-uuid-123');
  assert.equal(res.ok, true);
  if (res.ok) {
    assert.equal(res.value.email, 'tommiel@hotmail.com');
    assert.equal(res.value.password, 'bV1emc');
    assert.equal(res.value.refresh_token, 'M.C518_BAY.token123');
    assert.equal(res.value.client_id, 'client-uuid-123');
  }
});

test('parseMailboxInput parses 3-part pipe format (email|refresh_token|client_id)', () => {
  const res = parseMailboxInput('user@outlook.com|M.C518_BAY.xyz|5464fghj-bnmm');
  assert.equal(res.ok, true);
  if (res.ok) {
    assert.equal(res.value.email, 'user@outlook.com');
    assert.equal(res.value.password, undefined);
    assert.equal(res.value.refresh_token, 'M.C518_BAY.xyz');
    assert.equal(res.value.client_id, '5464fghj-bnmm');
  }
});

test('parseMailboxInput parses JSON payload', () => {
  const res = parseMailboxInput(
    JSON.stringify({
      email: 'json@hotmail.com',
      refresh_token: 'M.C123',
      client_id: 'cid-99',
    })
  );
  assert.equal(res.ok, true);
  if (res.ok) {
    assert.equal(res.value.email, 'json@hotmail.com');
    assert.equal(res.value.client_id, 'cid-99');
  }
});

test('parseMailboxInput rejects empty or malformed input without silent fallbacks', () => {
  assert.equal(parseMailboxInput('').ok, false);
  assert.equal(parseMailboxInput('onlyemail@hotmail.com').ok, false);
  assert.equal(parseMailboxInput('not-an-email|tok|cid').ok, false);
});

test('validateMailboxFields rejects missing refresh_token or client_id', () => {
  assert.equal(
    validateMailboxFields({ email: 'a@hotmail.com', refresh_token: '', client_id: 'cid' }).ok,
    false
  );
  assert.equal(
    validateMailboxFields({ email: 'a@hotmail.com', refresh_token: 'tok', client_id: '' }).ok,
    false
  );
});

test('extractOtpCode prefers explicit code and falls back to email body digits', () => {
  assert.equal(extractOtpCode('37589', '37589 is your Facebook confirmation code'), '37589');
  assert.equal(extractOtpCode('', 'Your Facebook code is FB-849201'), '849201');
  assert.equal(extractOtpCode('----', 'Use 552091 to verify your account'), '552091');
  assert.equal(extractOtpCode('', 'No digits here'), '');
});

test('clampFloatingPosition keeps widget inside viewport bounds', () => {
  const clamped = clampFloatingPosition(
    { x: -120, y: 950 },
    { width: 420, height: 56 },
    { width: 1440, height: 900 },
    12
  );
  assert.deepEqual(clamped, { x: 12, y: 832 });
});

test('getNextMailboxIndex cycles cleanly for CHG button', () => {
  const list = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
  assert.equal(getNextMailboxIndex('a', list), 1);
  assert.equal(getNextMailboxIndex('c', list), 0);
  assert.equal(getNextMailboxIndex(null, list), 0);
  assert.equal(getNextMailboxIndex('a', []), -1);
});

test('parseBulkMailboxInput parses multi-line accounts, ignores blanks, deduplicates, and reports invalid lines', () => {
  const raw = [
    'one@hotmail.com|pw1|M.tok1|cid1',
    '',
    'two@outlook.com|M.tok2|cid2',
    'bad-line-here',
    'ONE@hotmail.com|pw1-new|M.tok1-new|cid1-new',
  ].join('\n');

  const res = parseBulkMailboxInput(raw);
  assert.equal(res.valid.length, 2);
  assert.equal(res.errors.length, 1);
  assert.equal(res.errors[0]?.line, 4);
  assert.equal(res.valid[0]?.email, 'ONE@hotmail.com');
  assert.equal(res.valid[1]?.email, 'two@outlook.com');
});

test('hasNewMailCode detects first arrival and code/date updates while ignoring identical polls', () => {
  assert.equal(
    hasNewMailCode({}, { code: '37589', content: '37589 is your Facebook confirmation code', date: '12:00' }),
    true
  );
  assert.equal(
    hasNewMailCode(
      { lastCode: '37589', lastContent: '37589 is your Facebook confirmation code', lastDate: '12:00' },
      { code: '37589', content: '37589 is your Facebook confirmation code', date: '12:00' }
    ),
    false
  );
  assert.equal(
    hasNewMailCode(
      { lastCode: '37589', lastContent: '37589 is your Facebook confirmation code', lastDate: '12:00' },
      { code: '99104', content: '99104 is your Facebook confirmation code', date: '12:03' }
    ),
    true
  );
  assert.equal(hasNewMailCode({}, { code: '', content: 'no digits' }), false);
});

test('selectLatestIncomingMailboxId picks the mailbox that most recently received a code', () => {
  const picked = selectLatestIncomingMailboxId(
    [
      { id: 'm1', lastCode: '11111', lastCodeReceivedAt: 1000 },
      { id: 'm2', lastCode: '22222', lastCodeReceivedAt: 3000 },
      { id: 'm3' },
    ],
    'm1'
  );
  assert.equal(picked, 'm2');
});

test('isAutoReadActive enforces 2-hour window and 3-second polling constants', () => {
  assert.equal(AUTO_POLL_INTERVAL_MS, 3000);
  assert.equal(TWO_HOURS_MS, 7200000);

  const start = 1_000_000;
  assert.equal(isAutoReadActive(start, start + 3000, true, 2), true);
  assert.equal(isAutoReadActive(start, start + TWO_HOURS_MS - 1, true, 2), true);
  assert.equal(isAutoReadActive(start, start + TWO_HOURS_MS, true, 2), false);
  assert.equal(isAutoReadActive(start, start + 3000, false, 2), false);
  assert.equal(isAutoReadActive(start, start + 3000, true, 0), false);
  assert.equal(formatRemainingTime(start, start + 60_000), '1h 59m');
});
