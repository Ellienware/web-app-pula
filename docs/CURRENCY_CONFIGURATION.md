# Currency configuration

MoMoCircle has exactly one source of truth for currency: `CurrencyProperties`
(`backend/src/main/java/com/momocircle/config/CurrencyProperties.java`),
bound to two `application.yml` properties.

## Current configuration (unchanged from before this pass)

```yaml
app:
  currency:
    default-currency: ${DEFAULT_CURRENCY:XAF}
    supported: ${SUPPORTED_CURRENCIES:XAF,GHS,UGX}
```

- **Default**: `XAF` — used whenever a create request (circle, product) doesn't
  specify a currency explicitly.
- **Supported**: `XAF`, `GHS`, `UGX` — any currency outside this list is
  rejected by `FinancialValidationService.requireValidCurrency` regardless of
  where the request came from (manual form, AI action, or any other client).

This was the market already configured in the repository before this pass;
per Phase D.1.1's own instruction, it has been preserved, not changed.

## How every part of the app gets this value

- **Backend services** (`ProductService`, `CircleService`) — inject
  `CurrencyProperties` directly.
- **AI assistant** (`AssistantService`) — same injection, used for the
  `CREATE_CIRCLE` heuristic default and the AI's own default when the model
  doesn't specify one.
- **Frontend** — `GET /api/config/currency` (public, no auth required — see
  `ConfigController`) returns `{defaultCurrency, supportedCurrencies}`.
  `frontend/src/lib/currencyConfig.ts`'s `useCurrencyConfig()` hook fetches
  and caches this once per page load; `CreateCircleModal`,
  `AssistantPanel`, and `SellerDashboardPage` all use it instead of any
  hard-coded value. Its own `FALLBACK` constant (`XAF`) only matters if the
  backend is completely unreachable, and mirrors the backend's own default
  so the two can't silently disagree.

## What's *not* driven by this config (and why that's fine)

`Circle.currency` and `Product.currency` both have a `"XAF"` field-initializer
default in the entity class itself. These are dead code in practice — every
real write path (`CircleService.create`, `ProductService.create`) explicitly
sets currency from `CurrencyProperties` before the entity is ever persisted,
and `DevAdminSeeder` (the only other place that creates data) seeds a `User`
only, never a `Circle` or `Product`. The entity-level literals exist purely
as a defensive fallback for the (currently nonexistent) case of some future
code path constructing one of these entities directly without going through
its service — left as-is rather than removed, since a JPA entity needing
*some* default for a `NOT NULL` column is normal, but worth documenting here
so it's clear they're not a second, competing source of truth.

## Changing market

Two environment variables, no code change:

```bash
# Ghana
DEFAULT_CURRENCY=GHS
SUPPORTED_CURRENCIES=GHS

# Uganda
DEFAULT_CURRENCY=UGX
SUPPORTED_CURRENCIES=UGX

# Accept more than one currency
SUPPORTED_CURRENCIES=XAF,GHS
```

`DEFAULT_CURRENCY` should always also appear in `SUPPORTED_CURRENCIES` —
nothing enforces that relationship automatically today (a good small
addition for later: fail fast at boot if it doesn't).
