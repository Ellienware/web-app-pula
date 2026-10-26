# Phase K — In-Progress Status (stopped mid-session)

This snapshot was packaged when the session was interrupted for another
prompt, not at a natural phase boundary. Recording exact state so work can
resume cleanly rather than needing to be reconstructed from memory.

## Done, backend-complete (not yet re-verified with `mvn test` this round)
- Seller dashboard analytics: unique customers, 14-day revenue trend,
  MoMoRide delivery stats. Web UI done and build-verified.
- SIMILAR_ALTERNATIVE matching: seller-explicit linking (never automatic),
  transparent token-overlap suggestion endpoint, kept strictly separate
  from EXACT_MATCH price comparison in both data and API shape. Web
  linking UI (in `ProductFormModal.tsx`) written and build-verified.
- Smart Basket Optimization: `Business.minimumOrderAmount`/`deliveryFee`
  (real seller-configured data, never fabricated), `estimatedTotal` on
  each store, `meetsMinimumOrder` flag, and a genuine multi-store
  "fewest deliveries" greedy set-cover plan. `FASTEST`/`BEST_BALANCE`
  deliberately not built — no delivery-time or composite-scoring data
  exists anywhere in this app to build them on honestly.

## Explicitly NOT done yet
- **Backend not re-verified with `mvn test`** since this round of changes
  (V15 migration, Business/Basket/Product/SellerDashboard DTO changes).
  This is the first thing to run before trusting this zip.
- Web: no settings UI yet for a seller to actually set their own
  `minimumOrderAmount`/`deliveryFee`; `BasketComparePage.tsx` doesn't
  display `estimatedTotal`, `meetsMinimumOrder`, or the fewest-deliveries
  plan yet — only the backend/API layer is done for these.
- Mobile: none of today's work (analytics, SIMILAR_ALTERNATIVE,
  optimization) has been ported. Canonical-linking UI and order-status
  update UI on mobile were never started.
- **Multilingual support (§33): not started at all.**

## Regression discipline maintained this session
Every DTO change had its construction sites grepped across the whole
tree before considering the change complete — this caught and fixed a
real stale call site in `AssistantService`'s `CREATE_BUSINESS` handler
(same class of bug as the original Phase G.1 regression) before it would
have shipped again.
