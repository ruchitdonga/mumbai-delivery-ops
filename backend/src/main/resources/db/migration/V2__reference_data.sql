-- Reference data for the first five areas (East + West each). Boundaries are APPROXIMATE boxes split at the
-- Western Railway line, drawn for this project; they are not official ward or pincode boundaries.
INSERT INTO locality (name, area, side, pincode, center_lat, center_lng, boundary) VALUES
 ('Borivali West',  'Borivali',  'WEST', '400092', 19.2340, 72.8360, ST_GeogFromText('SRID=4326;POLYGON((72.8200 19.2180, 72.8510 19.2180, 72.8510 19.2500, 72.8200 19.2500, 72.8200 19.2180))')),
 ('Borivali East',  'Borivali',  'EAST', '400066', 19.2340, 72.8680, ST_GeogFromText('SRID=4326;POLYGON((72.8510 19.2180, 72.8850 19.2180, 72.8850 19.2500, 72.8510 19.2500, 72.8510 19.2180))')),
 ('Kandivali West', 'Kandivali', 'WEST', '400067', 19.2065, 72.8350, ST_GeogFromText('SRID=4326;POLYGON((72.8200 19.1950, 72.8500 19.1950, 72.8500 19.2180, 72.8200 19.2180, 72.8200 19.1950))')),
 ('Kandivali East', 'Kandivali', 'EAST', '400101', 19.2065, 72.8675, ST_GeogFromText('SRID=4326;POLYGON((72.8500 19.1950, 72.8850 19.1950, 72.8850 19.2180, 72.8500 19.2180, 72.8500 19.1950))')),
 ('Malad West',     'Malad',     'WEST', '400064', 19.1825, 72.8340, ST_GeogFromText('SRID=4326;POLYGON((72.8200 19.1700, 72.8490 19.1700, 72.8490 19.1950, 72.8200 19.1950, 72.8200 19.1700))')),
 ('Malad East',     'Malad',     'EAST', '400097', 19.1825, 72.8670, ST_GeogFromText('SRID=4326;POLYGON((72.8490 19.1700, 72.8850 19.1700, 72.8850 19.1950, 72.8490 19.1950, 72.8490 19.1700))')),
 ('Goregaon West',  'Goregaon',  'WEST', '400062', 19.1575, 72.8350, ST_GeogFromText('SRID=4326;POLYGON((72.8200 19.1450, 72.8490 19.1450, 72.8490 19.1700, 72.8200 19.1700, 72.8200 19.1450))')),
 ('Goregaon East',  'Goregaon',  'EAST', '400063', 19.1575, 72.8670, ST_GeogFromText('SRID=4326;POLYGON((72.8490 19.1450, 72.8850 19.1450, 72.8850 19.1700, 72.8490 19.1700, 72.8490 19.1450))')),
 ('Andheri West',   'Andheri',   'WEST', '400058', 19.1250, 72.8340, ST_GeogFromText('SRID=4326;POLYGON((72.8200 19.1050, 72.8470 19.1050, 72.8470 19.1450, 72.8200 19.1450, 72.8200 19.1050))')),
 ('Andheri East',   'Andheri',   'EAST', '400069', 19.1250, 72.8660, ST_GeogFromText('SRID=4326;POLYGON((72.8470 19.1050, 72.8850 19.1050, 72.8850 19.1450, 72.8470 19.1450, 72.8470 19.1050))'));

-- Dark stores (simulated): placed in 6 of the 10 localities so some areas are deliberately under-served.
INSERT INTO warehouse (code, name, locality_id, lat, lng, capacity_per_hour) VALUES
 ('DS-BOR-W', 'Borivali West Dark Store',  (SELECT id FROM locality WHERE name = 'Borivali West'),  19.2340, 72.8420, 70),
 ('DS-KAN-E', 'Kandivali East Dark Store', (SELECT id FROM locality WHERE name = 'Kandivali East'), 19.2060, 72.8660, 60),
 ('DS-MAL-W', 'Malad West Dark Store',     (SELECT id FROM locality WHERE name = 'Malad West'),     19.1840, 72.8380, 80),
 ('DS-GOR-E', 'Goregaon East Dark Store',  (SELECT id FROM locality WHERE name = 'Goregaon East'),  19.1580, 72.8620, 60),
 ('DS-AND-W', 'Andheri West Dark Store',   (SELECT id FROM locality WHERE name = 'Andheri West'),   19.1320, 72.8320, 90),
 ('DS-AND-E', 'Andheri East Dark Store',   (SELECT id FROM locality WHERE name = 'Andheri East'),   19.1160, 72.8660, 80);

INSERT INTO product (sku, name, category, price) VALUES
 ('MLK-1L', 'Toned Milk 1L', 'Dairy', 66), ('CRD-400', 'Curd 400g', 'Dairy', 45), ('PNR-200', 'Paneer 200g', 'Dairy', 95),
 ('BTR-100', 'Butter 100g', 'Dairy', 58), ('BRD-WHT', 'White Bread', 'Bakery', 45), ('EGG-12', 'Eggs (12)', 'Bakery', 96),
 ('TOM-1K', 'Tomatoes 1kg', 'Produce', 40), ('ONI-1K', 'Onions 1kg', 'Produce', 45), ('POT-1K', 'Potatoes 1kg', 'Produce', 38),
 ('BAN-6', 'Bananas (6)', 'Produce', 48), ('ATT-5K', 'Wheat Atta 5kg', 'Staples', 265), ('RIC-5K', 'Basmati Rice 5kg', 'Staples', 590),
 ('DAL-1K', 'Toor Dal 1kg', 'Staples', 165), ('OIL-1L', 'Sunflower Oil 1L', 'Staples', 155), ('SUG-1K', 'Sugar 1kg', 'Staples', 48),
 ('TEA-250', 'Tea 250g', 'Beverages', 140), ('COF-100', 'Instant Coffee 100g', 'Beverages', 310), ('CLA-750', 'Cola 750ml', 'Beverages', 45),
 ('CHP-52', 'Potato Chips', 'Snacks', 20), ('BIS-200', 'Biscuits 200g', 'Snacks', 35), ('NDL-4', 'Instant Noodles (4)', 'Snacks', 56),
 ('DET-1K', 'Detergent 1kg', 'Household', 130), ('SOP-4', 'Bath Soap (4)', 'Household', 160), ('TSH-6', 'Tissue Rolls (6)', 'Household', 210);

-- Starting stock: deterministic spread so every store starts with a few low-stock items.
INSERT INTO inventory (warehouse_id, product_id, qty)
SELECT w.id, p.id, 15 + ((w.id * 37 + p.id * 53) % 110) FROM warehouse w CROSS JOIN product p;
