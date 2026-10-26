import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  CAPABILITY_CHOICES, DEFAULT_CAPABILITIES, FULFILMENT_CHOICES, REGISTRATION_CHOICES, REGISTRATION_DISCLAIMER, TYPE_CHOICES,
  emptyDraft, normalisePhone, toPayload, toggle, validateAll, validateStep, withType,
} from '../src/lib/businessSetup.ts';

const valid = () => ({ ...emptyDraft('INFORMAL_TRADER'), name: "Thandi's Stand", category: 'Groceries' });

test('every business type offered has default capabilities, and informal traders and spazas can be chosen', () => {
  for (const t of TYPE_CHOICES) assert.ok(DEFAULT_CAPABILITIES[t.value].length > 0, t.value);
  const values = TYPE_CHOICES.map((t) => t.value);
  for (const needed of ['INFORMAL_TRADER', 'SPAZA', 'FORMAL_RETAILER', 'WHOLESALER_SUPPLIER', 'RESTAURANT', 'SERVICE_PROVIDER', 'OTHER_SMALL_BUSINESS']) {
    assert.ok(values.includes(needed), needed);
  }
  assert.equal(new Set(values).size, values.length, 'no duplicate types');
  assert.equal(new Set(TYPE_CHOICES.map((t) => t.label)).size, TYPE_CHOICES.length, 'labels are distinct in the picker');
});

test('services capability exists and service providers start with it', () => {
  assert.ok(CAPABILITY_CHOICES.some((c) => c.value === 'CAN_PROVIDE_SERVICES'));
  assert.ok(DEFAULT_CAPABILITIES.SERVICE_PROVIDER.includes('CAN_PROVIDE_SERVICES'));
  assert.equal(FULFILMENT_CHOICES.length, 4);
  assert.equal(REGISTRATION_CHOICES.length, 3);
});

test('choosing a type resets the suggested capabilities and keeps typed details', () => {
  const d = withType({ ...valid(), description: 'x' }, 'WHOLESALER_SUPPLIER');
  assert.deepEqual(d.capabilities, ['CAN_SUPPLY_BUSINESSES', 'CAN_MANAGE_DELIVERY_FLEET']);
  assert.equal(d.name, "Thandi's Stand");
  assert.equal(d.description, 'x');
});

test('step 1 requires a sensible name and category and checks lengths', () => {
  assert.deepEqual(Object.keys(validateStep(1, emptyDraft())).sort(), ['category', 'name']);
  assert.deepEqual(validateStep(1, valid()), {});
  assert.ok(validateStep(1, { ...valid(), name: ' a ' }).name);
  assert.ok(validateStep(1, { ...valid(), name: 'x'.repeat(81) }).name);
  assert.ok(validateStep(1, { ...valid(), category: 'x'.repeat(61) }).category);
  assert.ok(validateStep(1, { ...valid(), description: 'x'.repeat(501) }).description);
  assert.ok(validateStep(1, { ...valid(), location: 'x'.repeat(121) }).location);
});

test('phone numbers: South African and international formats accepted, rubbish rejected, optional when empty', () => {
  assert.equal(normalisePhone('082 123 4567'), '0821234567');
  assert.equal(normalisePhone('+27 82 123 4567'), '+27821234567');
  assert.equal(normalisePhone('27821234567'), '+27821234567');
  assert.equal(normalisePhone('(011) 555-1234'), '0115551234');
  assert.equal(normalisePhone('+44 20 7946 0958'), '+442079460958');
  for (const bad of ['', 'abc', '12345', '08212345', '+27 82', '0821234567890', '++27821234567']) assert.equal(normalisePhone(bad), null, bad);
  assert.deepEqual(validateStep(1, { ...valid(), contactPhone: '' }), {}, 'optional');
  assert.ok(validateStep(1, { ...valid(), contactPhone: '123' }).contactPhone);
});

test('step 2 needs a capability, fulfilment for sellers, and a plausible registration number only when registered', () => {
  assert.ok(validateStep(2, { ...valid(), capabilities: [] }).capabilities);
  assert.ok(validateStep(2, { ...valid(), fulfilmentOptions: [] }).fulfilmentOptions, 'sellers need a fulfilment option');
  assert.deepEqual(validateStep(2, { ...valid(), capabilities: ['CAN_PROVIDE_SERVICES'], fulfilmentOptions: [] }), {}, 'non-sellers do not');
  assert.deepEqual(validateStep(2, { ...valid(), registrationStatus: 'NOT_REGISTERED', registrationNumber: '!!!' }), {}, 'ignored when not registered');
  assert.ok(validateStep(2, { ...valid(), registrationStatus: 'FORMALLY_REGISTERED', registrationNumber: '!!' }).registrationNumber);
  assert.deepEqual(validateStep(2, { ...valid(), registrationStatus: 'FORMALLY_REGISTERED', registrationNumber: '2019/123456/07' }), {});
  assert.deepEqual(validateStep(2, { ...valid(), registrationStatus: 'FORMALLY_REGISTERED', registrationNumber: '' }), {}, 'the number is optional');
});

test('validateAll reports the first failing step', () => {
  assert.equal(validateAll(valid()), null);
  assert.equal(validateAll({ ...valid(), name: '' }).step, 1);
  assert.equal(validateAll({ ...valid(), capabilities: [] }).step, 2);
});

test('payload: trimmed, optional fields omitted, registration number only when registered, fulfilment only for sellers', () => {
  const p = toPayload({ ...valid(), name: '  Thandi  ', description: '  ', location: ' Soweto ', contactPhone: '082 123 4567', registrationNumber: '12345-6', registrationStatus: 'NOT_REGISTERED' });
  assert.equal(p.name, 'Thandi');
  assert.equal(p.location, 'Soweto');
  assert.equal(p.contactPhone, '0821234567');
  assert.ok(!('description' in p));
  assert.ok(!('registrationNumber' in p), 'a number is never sent for an unregistered business');
  assert.deepEqual(p.fulfilmentOptions, ['CUSTOMER_PICKUP']);
  const reg = toPayload({ ...valid(), registrationStatus: 'REGISTRATION_IN_PROGRESS', registrationNumber: ' 2019/123456/07 ' });
  assert.equal(reg.registrationNumber, '2019/123456/07');
  const svc = toPayload({ ...valid(), capabilities: ['CAN_PROVIDE_SERVICES'] });
  assert.deepEqual(svc.fulfilmentOptions, []);
  assert.ok(!('contactPhone' in toPayload(valid())));
});

test('toggle adds and removes without mutating', () => {
  const a = ['x'];
  assert.deepEqual(toggle(a, 'y'), ['x', 'y']);
  assert.deepEqual(toggle(['x', 'y'], 'x'), ['y']);
  assert.deepEqual(a, ['x']);
});

test('the registration disclaimer never claims PULA registers or verifies', () => {
  assert.match(REGISTRATION_DISCLAIMER, /does not register businesses with CIPC/);
  assert.match(REGISTRATION_DISCLAIMER, /does not check registration numbers/);
  assert.doesNotMatch(REGISTRATION_DISCLAIMER, /verified by PULA|we verify/i);
});

// ---- Phase 1 (F-02): the business type must be chosen explicitly; there is no default ----

import { readFileSync } from 'node:fs';
import { isBusinessTypeId } from '../src/lib/businessSetup.ts';

test('a fresh draft has NO business type and no preselected capabilities', () => {
  const d = emptyDraft();
  assert.equal(d.businessType, '');
  assert.deepEqual(d.capabilities, []);
});

test('the type step cannot be passed without a deliberate, valid choice', () => {
  assert.ok(validateStep(0, emptyDraft()).businessType, 'unselected type is an error');
  assert.ok(validateStep(0, { ...emptyDraft(), businessType: 'NOT_A_TYPE' as never }).businessType, 'unknown type is an error');
  const bad = validateAll({ ...emptyDraft(), name: 'Thandi', category: 'Groceries' });
  assert.equal(bad?.step, 0, 'validation sends the owner back to the type step');
});

test('toPayload refuses to build a request without a supported type', () => {
  assert.throws(() => toPayload({ ...emptyDraft(), name: 'Thandi', category: 'Groceries' }));
  assert.throws(() => toPayload({ ...emptyDraft(), businessType: 'FOOD_TRUCK' as never, name: 'Thandi', category: 'Groceries' }));
});

test('every offered type is submitted as exactly itself, and nothing else becomes LOCAL_BUSINESS', () => {
  for (const t of TYPE_CHOICES) {
    const picked = withType({ ...emptyDraft(), name: 'Thandi', category: 'Groceries' }, t.value);
    assert.equal(validateAll(picked), null, t.value);
    assert.equal(toPayload(picked).businessType, t.value, t.value);
    assert.ok(isBusinessTypeId(t.value));
  }
  assert.equal(isBusinessTypeId(''), false);
  assert.equal(isBusinessTypeId('local_business'), false);
});

test('the web wizard source has no implicit type and gates Next on a chosen type', () => {
  const src = readFileSync(new URL('../src/components/market/CreateBusinessModal.tsx', import.meta.url), 'utf8');
  assert.ok(/useState<BusinessType \| null>\(null\)/.test(src), 'type state starts unselected');
  assert.ok(!/useState<BusinessType>\("LOCAL_BUSINESS"\)/.test(src), 'no preselected LOCAL_BUSINESS');
  assert.ok(/disabled=\{step === 0 && !businessType\}/.test(src), 'Next is disabled until a type is chosen');
});

test('public business consumers use the public shape (no owner or registration fields)', () => {
  for (const f of ['pages/market/BusinessProfilePage.tsx', 'pages/market/MarketHomePage.tsx', 'components/market/BusinessCard.tsx']) {
    const src = readFileSync(new URL(`../src/${f}`, import.meta.url), 'utf8');
    assert.ok(src.includes('PublicBusiness'), f);
    assert.ok(!/\b(ownerId|ownerName|registrationNumber|registrationStatus)\b/.test(src), `${f} must not read protected fields`);
  }
});
