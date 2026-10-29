/**
 * Business onboarding rules shared (by identical copy) between the web CreateBusinessModal and the mobile setup screen: the
 * option lists, per-step validation and the exact payload sent to POST /api/businesses. Pure (no React, no network) so it is
 * unit-tested. The server remains the authority; these rules only give early, readable feedback.
 *
 * Registration is SELF-DECLARED. PULA does not register businesses with CIPC and does not verify registration numbers.
 */

export type BusinessTypeId =
  | 'LOCAL_BUSINESS' | 'INFORMAL_TRADER' | 'FORMAL_RETAILER' | 'SERVICE_PROVIDER' | 'WHOLESALER_SUPPLIER' | 'OTHER_SMALL_BUSINESS'
  | 'RESTAURANT' | 'HOME_KITCHEN' | 'CAFE' | 'FAST_FOOD' | 'FOOD_VENDOR' | 'SPAZA' | 'BAKERY' | 'CATERING';
export type CapabilityId = 'CAN_SELL_TO_CONSUMERS' | 'CAN_BUY_STOCK' | 'CAN_SUPPLY_BUSINESSES' | 'CAN_MANAGE_DELIVERY_FLEET' | 'CAN_PROVIDE_SERVICES';
export type FulfilmentId = 'CUSTOMER_PICKUP' | 'SELLER_DELIVERY' | 'MOMORIDE_DELIVERY' | 'SUPPORTED_PARTNER_DELIVERY';
export type RegistrationId = 'NOT_REGISTERED' | 'REGISTRATION_IN_PROGRESS' | 'FORMALLY_REGISTERED';

export const REGISTRATION_DISCLAIMER =
  'Registration status is what you tell us. PULA does not register businesses with CIPC and does not check registration numbers.';

/** Choices for the setup picker only. Display labels elsewhere in the app are unchanged. */
export const TYPE_CHOICES: { value: BusinessTypeId; label: string; hint: string }[] = [
  { value: 'LOCAL_BUSINESS', label: 'Local business', hint: 'A shop, stall or service with regular customers' },
  { value: 'INFORMAL_TRADER', label: 'Street, market or home-based trader', hint: 'Sell from a table, stand, car boot or home' },
  { value: 'SPAZA', label: 'Spaza shop', hint: 'Neighbourhood convenience shop' },
  { value: 'FORMAL_RETAILER', label: 'Retailer', hint: 'A shop with a till and stock lines' },
  { value: 'WHOLESALER_SUPPLIER', label: 'Wholesaler or supplier', hint: 'Sell stock to other businesses' },
  { value: 'SERVICE_PROVIDER', label: 'Service provider', hint: 'Repairs, hair, cleaning, transport and similar' },
  { value: 'RESTAURANT', label: 'Restaurant', hint: 'Sit-down or take-away meals' },
  { value: 'CAFE', label: 'Café', hint: 'Coffee, snacks and light meals' },
  { value: 'FAST_FOOD', label: 'Fast food', hint: 'Quick-service meals' },
  { value: 'FOOD_VENDOR', label: 'Food vendor', hint: 'Street food and market stalls' },
  { value: 'HOME_KITCHEN', label: 'Home kitchen', hint: 'Meals cooked at home for orders' },
  { value: 'BAKERY', label: 'Bakery', hint: 'Bread, cakes and pastries' },
  { value: 'CATERING', label: 'Catering', hint: 'Food for events and groups' },
  { value: 'OTHER_SMALL_BUSINESS', label: 'Other small business', hint: 'Anything else' },
];

export const CAPABILITY_CHOICES: { value: CapabilityId; label: string }[] = [
  { value: 'CAN_SELL_TO_CONSUMERS', label: 'Sell to customers' },
  { value: 'CAN_BUY_STOCK', label: 'Buy stock from suppliers' },
  { value: 'CAN_SUPPLY_BUSINESSES', label: 'Supply other businesses' },
  { value: 'CAN_MANAGE_DELIVERY_FLEET', label: 'Manage a delivery fleet' },
  { value: 'CAN_PROVIDE_SERVICES', label: 'Provide services' },
];

export const FULFILMENT_CHOICES: { value: FulfilmentId; label: string }[] = [
  { value: 'CUSTOMER_PICKUP', label: 'Customer pickup' },
  { value: 'SELLER_DELIVERY', label: 'I deliver myself' },
  { value: 'MOMORIDE_DELIVERY', label: 'Delivery through PULA Ride' },
  { value: 'SUPPORTED_PARTNER_DELIVERY', label: 'Supported delivery partner' },
];

export const REGISTRATION_CHOICES: { value: RegistrationId; label: string }[] = [
  { value: 'NOT_REGISTERED', label: 'Not registered yet' },
  { value: 'REGISTRATION_IN_PROGRESS', label: 'Registration in progress' },
  { value: 'FORMALLY_REGISTERED', label: 'Formally registered' },
];

const SELL: CapabilityId[] = ['CAN_SELL_TO_CONSUMERS'];
const SELL_BUY: CapabilityId[] = ['CAN_SELL_TO_CONSUMERS', 'CAN_BUY_STOCK'];

/** Starting capabilities per type; the owner can change them in the last step. */
export const DEFAULT_CAPABILITIES: Record<BusinessTypeId, CapabilityId[]> = {
  LOCAL_BUSINESS: SELL_BUY, INFORMAL_TRADER: SELL_BUY, SPAZA: SELL_BUY, FORMAL_RETAILER: SELL,
  SERVICE_PROVIDER: ['CAN_PROVIDE_SERVICES', 'CAN_BUY_STOCK'], WHOLESALER_SUPPLIER: ['CAN_SELL_TO_CONSUMERS', 'CAN_SUPPLY_BUSINESSES'],
  OTHER_SMALL_BUSINESS: SELL, RESTAURANT: SELL, CAFE: SELL, FAST_FOOD: SELL, FOOD_VENDOR: SELL, HOME_KITCHEN: SELL, BAKERY: SELL, CATERING: SELL,
};

export interface BusinessDraft {
  /** '' = the owner has not chosen yet. There is deliberately no default type: the owner must pick one. */
  businessType: BusinessTypeId | '';
  name: string;
  category: string;
  description: string;
  location: string;
  contactPhone: string;
  capabilities: CapabilityId[];
  fulfilmentOptions: FulfilmentId[];
  registrationStatus: RegistrationId;
  registrationNumber: string;
}

export function emptyDraft(type: BusinessTypeId | '' = ''): BusinessDraft {
  return {
    businessType: type, name: '', category: '', description: '', location: '', contactPhone: '',
    capabilities: type ? [...DEFAULT_CAPABILITIES[type]] : [], fulfilmentOptions: ['CUSTOMER_PICKUP'],
    registrationStatus: 'NOT_REGISTERED', registrationNumber: '',
  };
}

/** Choosing a type resets the suggested capabilities (as the web wizard does) and keeps everything else the owner typed. */
export function withType(draft: BusinessDraft, type: BusinessTypeId): BusinessDraft {
  return { ...draft, businessType: type, capabilities: [...DEFAULT_CAPABILITIES[type]] };
}

export function toggle<T>(list: readonly T[], item: T): T[] {
  return list.includes(item) ? list.filter((x) => x !== item) : [...list, item];
}

/**
 * South African numbers (0821234567, +27 82 123 4567, 27821234567) and other international numbers (+ followed by 8-15 digits).
 * Returns the number without spaces/dashes/brackets, or null if it is not a plausible phone number.
 */
export function normalisePhone(raw: string): string | null {
  const s = (raw ?? '').replace(/[\s\-().]/g, '');
  if (/^0\d{9}$/.test(s)) return s;
  if (/^\+27\d{9}$/.test(s)) return s;
  if (/^27\d{9}$/.test(s)) return `+${s}`;
  if (/^\+\d{8,15}$/.test(s)) return s;
  return null;
}

export type Errors = Partial<Record<'businessType' | 'name' | 'category' | 'description' | 'location' | 'contactPhone'
  | 'capabilities' | 'fulfilmentOptions' | 'registrationNumber', string>>;

/** Validation for one wizard step (0 type, 1 details, 2 capabilities/fulfilment/registration). */
export function validateStep(step: 0 | 1 | 2, d: BusinessDraft): Errors {
  const e: Errors = {};
  if (step === 0) {
    if (!TYPE_CHOICES.some((t) => t.value === d.businessType)) e.businessType = 'Choose the kind of business you run';
  }
  if (step === 1) {
    const name = d.name.trim();
    const category = d.category.trim();
    if (name.length < 2) e.name = 'Enter your business name (at least 2 characters)';
    else if (name.length > 80) e.name = 'Business name is too long (80 characters at most)';
    if (category.length < 2) e.category = 'Enter a category, for example Groceries';
    else if (category.length > 60) e.category = 'Category is too long (60 characters at most)';
    if (d.description.trim().length > 500) e.description = 'Description is too long (500 characters at most)';
    if (d.location.trim().length > 120) e.location = 'Location is too long (120 characters at most)';
    if (d.contactPhone.trim() && normalisePhone(d.contactPhone) === null) e.contactPhone = 'Enter a valid phone number, for example 082 123 4567';
  }
  if (step === 2) {
    if (d.capabilities.length === 0) e.capabilities = 'Choose at least one thing your business will do on PULA';
    if (d.capabilities.includes('CAN_SELL_TO_CONSUMERS') && d.fulfilmentOptions.length === 0) {
      e.fulfilmentOptions = 'Choose how customers will get their orders';
    }
    if (d.registrationStatus !== 'NOT_REGISTERED' && d.registrationNumber.trim()
      && !/^[A-Za-z0-9][A-Za-z0-9/\- ]{3,29}$/.test(d.registrationNumber.trim())) {
      e.registrationNumber = 'Registration numbers use letters, digits, / and - (4 to 30 characters)';
    }
  }
  return e;
}

export function validateAll(d: BusinessDraft): { step: 0 | 1 | 2; errors: Errors } | null {
  for (const step of [0, 1, 2] as const) {
    const errors = validateStep(step, d);
    if (Object.keys(errors).length > 0) return { step, errors };
  }
  return null;
}

export interface CreateBusinessPayload {
  name: string;
  category: string;
  businessType: BusinessTypeId;
  description?: string;
  location?: string;
  contactPhone?: string;
  capabilities: CapabilityId[];
  fulfilmentOptions: FulfilmentId[];
  registrationStatus: RegistrationId;
  registrationNumber?: string;
}

/**
 * The exact body for POST /api/businesses. Empty optional fields are omitted, a registration number is sent ONLY when the owner
 * says they are registered or registering, and fulfilment is sent only for businesses that sell to customers.
 */
export function isBusinessTypeId(v: string): v is BusinessTypeId {
  return TYPE_CHOICES.some((t) => t.value === v);
}

export function toPayload(d: BusinessDraft): CreateBusinessPayload {
  // Never build a request without an explicitly chosen, supported type (the server also rejects it).
  if (!isBusinessTypeId(d.businessType)) throw new Error('Choose the kind of business you run');
  const phone = d.contactPhone.trim() ? normalisePhone(d.contactPhone) : null;
  const sells = d.capabilities.includes('CAN_SELL_TO_CONSUMERS');
  const number = d.registrationStatus === 'NOT_REGISTERED' ? '' : d.registrationNumber.trim();
  return {
    name: d.name.trim(),
    category: d.category.trim(),
    businessType: d.businessType,
    ...(d.description.trim() ? { description: d.description.trim() } : {}),
    ...(d.location.trim() ? { location: d.location.trim() } : {}),
    ...(phone ? { contactPhone: phone } : {}),
    capabilities: [...d.capabilities],
    fulfilmentOptions: sells ? [...d.fulfilmentOptions] : [],
    registrationStatus: d.registrationStatus,
    ...(number ? { registrationNumber: number } : {}),
  };
}
