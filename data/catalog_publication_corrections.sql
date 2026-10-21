-- Publication catalog correction, source checked 2026-10-21.
-- Product: 獺祭 純米大吟醸45 / 山口県 / 15%.
-- Official source: https://www.dassaistore.com/product-detail/47
-- Confirm the intended database, take a backup, and inspect the row before applying.
-- Do not run create_issho_no_deai.sql against an existing database for this correction.
-- Match the product by name/region, not an assumed seed ID. Only the old demo ABV is updated.
-- Existing edits to ABV, capacity-unspecified reference prices, and all other rows are preserved.

START TRANSACTION;

SELECT id, name, region, abv, price
FROM sake
WHERE name = '獺祭 純米大吟醸45'
  AND region = '山口県';

UPDATE sake
SET abv = 15.0
WHERE name = '獺祭 純米大吟醸45'
  AND region = '山口県'
  AND abv = 16.0;

SELECT ROW_COUNT() AS updated_rows;

SELECT id, name, region, abv, price
FROM sake
WHERE name = '獺祭 純米大吟醸45'
  AND region = '山口県';

COMMIT;
