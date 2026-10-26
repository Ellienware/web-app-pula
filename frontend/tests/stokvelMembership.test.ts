import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  acceptOutcomeMessage, appInviteLink, canManageMembership, contributionSummary, discoveryAction, emptyStokvelDraft, extractInviteCode,
  friendlyMembershipError, inviteStatusLabel, parseMoney, requestStatusLabel, requestStatusTone, settingsToDraft, toCreatePayload,
  toSettingsPayload, validateSettings, validateStokvelDraft, webInviteLink, FUNDS_NOTICE,
} from '../src/lib/stokvelMembership.ts';

const ok = () => ({ ...emptyStokvelDraft(), name: 'Umoja Savers', contributionAmount: '250' });

test('money parsing accepts two decimals and rejects zero, negatives, extra precision and rubbish', () => {
  assert.equal(parseMoney('250'), 250);
  assert.equal(parseMoney('250.5'), 250.5);
  assert.equal(parseMoney(' 1 000,50 '), 1000.5);
  for (const bad of ['', '0', '0.00', '-5', '1.234', 'abc', '1e3', '1.2.3', '9999999999']) assert.equal(parseMoney(bad), null, bad);
});

test('stokvel draft: name length, rotational needs an amount, dates and thresholds are validated', () => {
  assert.deepEqual(validateStokvelDraft(ok()), {});
  assert.ok(validateStokvelDraft({ ...ok(), name: 'a' }).name);
  assert.ok(validateStokvelDraft({ ...ok(), name: 'x'.repeat(121) }).name);
  assert.ok(validateStokvelDraft({ ...ok(), description: 'x'.repeat(1001) }).description);
  assert.ok(validateStokvelDraft({ ...ok(), stokvelType: 'ROTATIONAL', contributionAmount: '' }).contributionAmount, 'rotational needs an amount');
  assert.deepEqual(validateStokvelDraft({ ...ok(), stokvelType: 'GENERAL', contributionAmount: '' }), {}, 'others may omit it');
  assert.ok(validateStokvelDraft({ ...ok(), contributionAmount: '10.123' }).contributionAmount);
  assert.ok(validateStokvelDraft({ ...ok(), startDate: '2026-13-01' }).startDate);
  assert.ok(validateStokvelDraft({ ...ok(), startDate: '2026-02-30' }).startDate, 'impossible dates are rejected');
  assert.ok(validateStokvelDraft({ ...ok(), startDate: '2026-11-01', targetDate: '2026-10-01' }).targetDate);
  assert.ok(validateStokvelDraft({ ...ok(), approvalRule: 'THRESHOLD', approvalThreshold: '' }).approvalThreshold);
  assert.ok(validateStokvelDraft({ ...ok(), approvalRule: 'THRESHOLD', approvalThreshold: '0' }).approvalThreshold);
  assert.deepEqual(validateStokvelDraft({ ...ok(), approvalRule: 'THRESHOLD', approvalThreshold: '2' }), {});
});

test('create payload targets the stokvel API shape: trimmed, optional fields omitted, numbers as numbers', () => {
  const p = toCreatePayload({ ...ok(), name: '  Umoja  ', description: ' ', startDate: '2026-11-01', targetAmount: '5 000', approvalRule: 'THRESHOLD', approvalThreshold: '3', membersCanVote: true }, 'ZAR');
  assert.equal(p.name, 'Umoja');
  assert.equal(p.currency, 'ZAR');
  assert.equal(p.contributionAmount, 250);
  assert.equal(p.targetAmount, 5000);
  assert.equal(p.approvalThreshold, 3);
  assert.equal(p.membersCanVote, true);
  assert.ok(!('description' in p) && !('targetDate' in p));
  assert.ok(!('approvalThreshold' in toCreatePayload(ok(), 'ZAR')), 'threshold only for THRESHOLD');
  assert.ok(!('contributionAmount' in toCreatePayload({ ...ok(), contributionAmount: '' }, 'ZAR')));
});

test('discovery settings: summary needed to be discoverable, limits within range and not below current members', () => {
  const d = { discoverable: true, area: 'Soweto', publicSummary: 'Monthly savings for neighbours', membershipRequirements: '', maxMembers: '10', inviteRequiresApproval: false };
  assert.deepEqual(validateSettings(d, 3), {});
  assert.ok(validateSettings({ ...d, publicSummary: 'short' }, 3).publicSummary);
  assert.deepEqual(validateSettings({ ...d, discoverable: false, publicSummary: '' }, 3), {}, 'private needs no summary');
  assert.ok(validateSettings({ ...d, maxMembers: '1' }, 1).maxMembers);
  assert.ok(validateSettings({ ...d, maxMembers: '501' }, 1).maxMembers);
  assert.ok(validateSettings({ ...d, maxMembers: '2' }, 5).maxMembers, 'below current members');
  assert.ok(validateSettings({ ...d, maxMembers: 'ten' }, 1).maxMembers);
  assert.deepEqual(validateSettings({ ...d, maxMembers: '' }, 99), {}, 'no limit');
  assert.ok(validateSettings({ ...d, area: 'x'.repeat(81) }, 1).area);
  const payload = toSettingsPayload({ ...d, area: ' ', membershipRequirements: ' Live in Soweto ', maxMembers: '' });
  assert.deepEqual(payload, { discoverable: true, area: null, publicSummary: 'Monthly savings for neighbours', membershipRequirements: 'Live in Soweto', maxMembers: null, inviteRequiresApproval: false });
  assert.equal(settingsToDraft({ discoverable: false, area: null, publicSummary: null, membershipRequirements: null, maxMembers: null, inviteRequiresApproval: true, memberCount: 1, pendingRequests: 0 }).inviteRequiresApproval, true);
});

test('an invite code is found in typed text, pasted text and links, and nothing else is trusted', () => {
  const C = '0123-4567-89AB-CDEF';
  assert.equal(extractInviteCode(C), C);
  assert.equal(extractInviteCode('0123456789abcdef'), C);
  assert.equal(extractInviteCode('  0123 4567 89ab cdef \n'), C);
  assert.equal(extractInviteCode(`https://pula.example/stokvels/join?code=${encodeURIComponent(C)}`), C);
  assert.equal(extractInviteCode(`momocircle://stokvel/join?code=${C}&x=1`), C);
  assert.equal(extractInviteCode(`Join my stokvel with code ${C} today`), C);
  assert.equal(extractInviteCode('Join with code 0123 4567 89AB CDEF'), null, 'spaced codes inside prose are not guessed');
  assert.equal(extractInviteCode('0123-4567-89AB-CDEF-0123'), null, 'a longer token is not a code');
  for (const bad of [null, undefined, '', 'hello', '0123-4567-89AB', 'A'.repeat(400), '0123-4567-89AB-CD!F']) assert.equal(extractInviteCode(bad), null, String(bad));
});

test('invite links carry only the code and use the right scheme', () => {
  assert.equal(webInviteLink('https://pula.example/', '0123-4567-89AB-CDEF'), 'https://pula.example/stokvels/join?code=0123-4567-89AB-CDEF');
  assert.equal(appInviteLink('momocircle', '0123-4567-89AB-CDEF'), 'momocircle://stokvel/join?code=0123-4567-89AB-CDEF');
});

test('discovery card actions: pending and full stokvels cannot be requested', () => {
  assert.deepEqual(discoveryAction({ full: false, joinState: 'NONE' }), { canRequest: true, label: 'Ask to join', note: null });
  assert.equal(discoveryAction({ full: true, joinState: 'NONE' }).canRequest, false);
  assert.equal(discoveryAction({ full: false, joinState: 'PENDING' }).canRequest, false);
  assert.match(discoveryAction({ full: false, joinState: 'PENDING' }).label, /pending/i);
});

test('wording: request and invite states, accept outcomes, tracked-not-held notice', () => {
  assert.equal(requestStatusLabel('PENDING'), 'Waiting for approval');
  assert.equal(requestStatusTone('APPROVED'), 'success');
  assert.equal(requestStatusTone('REJECTED'), 'failed');
  assert.equal(inviteStatusLabel('EXHAUSTED'), 'All uses taken');
  assert.match(acceptOutcomeMessage({ outcome: 'JOINED', stokvelName: 'Umoja' }), /joined Umoja/);
  assert.match(acceptOutcomeMessage({ outcome: 'REQUESTED', stokvelName: 'Umoja' }), /when an organiser approves/);
  assert.match(acceptOutcomeMessage({ outcome: 'ALREADY_MEMBER', stokvelName: 'Umoja' }), /already a member/);
  assert.match(FUNDS_NOTICE, /does not hold, pool or pay out/);
  assert.match(contributionSummary(250, 'MONTHLY', 'ZAR'), /250/);
  assert.match(contributionSummary(null, 'WEEKLY', 'ZAR'), /set by the group/);
});

test('errors become plain language and the manager hint is only for owners and admins', () => {
  assert.match(friendlyMembershipError({ status: 429, message: 'Your last request was declined. You can ask again after 24 hours' }), /ask again after 24 hours/);
  assert.match(friendlyMembershipError({ status: 401 }), /session has expired/);
  assert.match(friendlyMembershipError(new TypeError('Failed to fetch')), /Could not reach the server/);
  assert.match(friendlyMembershipError({ status: 500, message: 'org.hibernate.Exception' }), /our side/);
  assert.equal(friendlyMembershipError({ status: 409, message: 'This stokvel is full' }), 'This stokvel is full');
  assert.ok(canManageMembership('OWNER') && canManageMembership('ADMIN'));
  assert.ok(!canManageMembership('MEMBER') && !canManageMembership('TREASURER') && !canManageMembership(undefined));
});
