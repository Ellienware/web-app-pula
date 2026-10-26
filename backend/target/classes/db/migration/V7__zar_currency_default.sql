-- This deployment now targets South Africa (see CurrencyProperties /
-- application.yml's app.currency.default-currency, changed to ZAR).
--
-- Neither column default below is actually read on the write path —
-- CircleService/ProductService always set an explicit, validated currency
-- before persisting (see FinancialValidationService.requireValidCurrency)
-- — but leaving them at the old 'XAF' literal is misleading and would only
-- ever be hit by some future write path that forgets to set currency
-- explicitly. Existing rows are untouched; this only changes what a future
-- insert falls back to if one ever skips the currency column entirely.
ALTER TABLE circles ALTER COLUMN currency SET DEFAULT 'ZAR';
ALTER TABLE products ALTER COLUMN currency SET DEFAULT 'ZAR';
