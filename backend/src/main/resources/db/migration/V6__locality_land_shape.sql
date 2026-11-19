-- Locality boxes are replaced once by land-hugging shapes built from road-snapped customers (see
-- WarehouseRepository.shapeLocalitiesToLand); this flag records which localities have been reshaped.
ALTER TABLE locality ADD COLUMN land_shaped BOOLEAN NOT NULL DEFAULT FALSE;
