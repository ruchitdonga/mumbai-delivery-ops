-- Full Mumbai (BMC area): adds the island city, the rest of the western suburbs and the eastern suburbs, plus a
-- population per locality. Boundaries are APPROXIMATE hand-drawn boxes (not official wards); populations are rounded
-- estimates from Census 2011 ward totals split by area, for demand weighting and per-capita metrics only.
ALTER TABLE locality ADD COLUMN population INT;
ALTER TABLE locality DROP CONSTRAINT IF EXISTS locality_side_check;
ALTER TABLE locality ADD CONSTRAINT locality_side_check CHECK (side IN ('EAST', 'WEST', 'NONE'));

UPDATE locality SET population = CASE name
    WHEN 'Borivali West' THEN 330000 WHEN 'Borivali East' THEN 270000
    WHEN 'Kandivali West' THEN 300000 WHEN 'Kandivali East' THEN 280000
    WHEN 'Malad West' THEN 420000 WHEN 'Malad East' THEN 300000
    WHEN 'Goregaon West' THEN 250000 WHEN 'Goregaon East' THEN 260000
    WHEN 'Andheri West' THEN 380000 WHEN 'Andheri East' THEN 450000 END;

-- (name, area, side, pincode, lng_min, lng_max, lat_min, lat_max, population); boxes do not overlap.
INSERT INTO locality (name, area, side, pincode, center_lat, center_lng, boundary, population)
SELECT n, a, s, p, (y1 + y2) / 2, (x1 + x2) / 2,
       ST_GeogFromText(format('SRID=4326;POLYGON((%s %s, %s %s, %s %s, %s %s, %s %s))', x1, y1, x2, y1, x2, y2, x1, y2, x1, y1)), pop
FROM (VALUES
 ('Dahisar West',     'Dahisar',    'WEST', '400068', 72.835, 72.858, 19.250, 19.275, 200000),
 ('Dahisar East',     'Dahisar',    'EAST', '400068', 72.858, 72.880, 19.250, 19.275, 220000),
 ('Vile Parle West',  'Vile Parle', 'WEST', '400056', 72.825, 72.845, 19.090, 19.105, 150000),
 ('Vile Parle East',  'Vile Parle', 'EAST', '400057', 72.845, 72.865, 19.090, 19.105, 160000),
 ('Santacruz West',   'Santacruz',  'WEST', '400054', 72.825, 72.842, 19.072, 19.090, 170000),
 ('Santacruz East',   'Santacruz',  'EAST', '400055', 72.842, 72.865, 19.072, 19.090, 230000),
 ('Khar West',        'Khar',       'WEST', '400052', 72.825, 72.842, 19.062, 19.072, 120000),
 ('Bandra West',      'Bandra',     'WEST', '400050', 72.820, 72.842, 19.040, 19.062, 220000),
 ('Bandra East',      'Bandra',     'EAST', '400051', 72.842, 72.855, 19.040, 19.072, 260000),
 ('Dadar West',       'Dadar',      'WEST', '400028', 72.830, 72.845, 19.010, 19.040, 250000),
 ('Dadar East',       'Dadar',      'EAST', '400014', 72.845, 72.855, 19.010, 19.030, 200000),
 ('Worli',            'Worli',      'NONE', '400018', 72.810, 72.830, 18.995, 19.025, 200000),
 ('Parel',            'Parel',      'NONE', '400012', 72.830, 72.850, 18.990, 19.010, 210000),
 ('Byculla',          'Byculla',    'NONE', '400027', 72.825, 72.845, 18.965, 18.990, 230000),
 ('Tardeo',           'Tardeo',     'NONE', '400034', 72.808, 72.825, 18.955, 18.985, 180000),
 ('Girgaon',          'Girgaon',    'NONE', '400004', 72.812, 72.825, 18.940, 18.955, 150000),
 ('Fort',             'Fort',       'NONE', '400001', 72.825, 72.845, 18.925, 18.950, 100000),
 ('Colaba',           'Colaba',     'NONE', '400005', 72.810, 72.835, 18.895, 18.925, 150000),
 ('Sion',             'Sion',       'NONE', '400022', 72.855, 72.880, 19.030, 19.055, 220000),
 ('Wadala',           'Wadala',     'NONE', '400031', 72.855, 72.875, 19.005, 19.030, 180000),
 ('Chembur',          'Chembur',    'NONE', '400071', 72.880, 72.915, 19.040, 19.075, 300000),
 ('Govandi',          'Govandi',    'NONE', '400043', 72.915, 72.935, 19.045, 19.070, 250000),
 ('Kurla West',       'Kurla',      'WEST', '400070', 72.865, 72.880, 19.055, 19.090, 280000),
 ('Kurla East',       'Kurla',      'EAST', '400024', 72.880, 72.900, 19.075, 19.095, 200000),
 ('Ghatkopar West',   'Ghatkopar',  'WEST', '400086', 72.885, 72.908, 19.095, 19.115, 300000),
 ('Ghatkopar East',   'Ghatkopar',  'EAST', '400077', 72.915, 72.935, 19.070, 19.100, 280000),
 ('Vikhroli',         'Vikhroli',   'NONE', '400083', 72.910, 72.940, 19.100, 19.125, 230000),
 ('Powai',            'Powai',      'NONE', '400076', 72.885, 72.910, 19.115, 19.135, 180000),
 ('Bhandup West',     'Bhandup',    'WEST', '400078', 72.925, 72.945, 19.135, 19.160, 250000),
 ('Mulund West',      'Mulund',     'WEST', '400080', 72.940, 72.960, 19.165, 19.185, 220000),
 ('Mulund East',      'Mulund',     'EAST', '400081', 72.960, 72.975, 19.160, 19.180, 150000)
) AS v(n, a, s, p, x1, x2, y1, y2, pop);

-- More dark stores; Vile Parle, Govandi, Kurla East, Tardeo, Vikhroli etc. are left uncovered on purpose (planner demo).
INSERT INTO warehouse (code, name, locality_id, lat, lng, capacity_per_hour)
SELECT c, n, (SELECT id FROM locality WHERE name = l), lat, lng, cap
FROM (VALUES
 ('DS-DAH-E', 'Dahisar East Dark Store',  'Dahisar East',  19.2620, 72.8680, 60),
 ('DS-BAN-W', 'Bandra West Dark Store',   'Bandra West',   19.0560, 72.8330, 90),
 ('DS-SAN-E', 'Santacruz East Dark Store','Santacruz East',19.0800, 72.8530, 70),
 ('DS-DAD-W', 'Dadar West Dark Store',    'Dadar West',    19.0200, 72.8400, 80),
 ('DS-WOR',   'Worli Dark Store',         'Worli',         19.0100, 72.8180, 70),
 ('DS-BYC',   'Byculla Dark Store',       'Byculla',       18.9770, 72.8330, 60),
 ('DS-COL',   'Colaba Dark Store',        'Colaba',        18.9100, 72.8220, 50),
 ('DS-FRT',   'Fort Dark Store',          'Fort',          18.9350, 72.8350, 50),
 ('DS-SIO',   'Sion Dark Store',          'Sion',          19.0420, 72.8650, 70),
 ('DS-CHE',   'Chembur Dark Store',       'Chembur',       19.0600, 72.8980, 80),
 ('DS-KUR-W', 'Kurla West Dark Store',    'Kurla West',    19.0720, 72.8720, 70),
 ('DS-GHA-W', 'Ghatkopar West Dark Store','Ghatkopar West',19.1050, 72.8950, 80),
 ('DS-POW',   'Powai Dark Store',         'Powai',         19.1220, 72.9000, 70),
 ('DS-BHA-W', 'Bhandup West Dark Store',  'Bhandup West',  19.1450, 72.9350, 60),
 ('DS-MUL-W', 'Mulund West Dark Store',   'Mulund West',   19.1750, 72.9500, 60)
) AS v(c, n, l, lat, lng, cap);

INSERT INTO inventory (warehouse_id, product_id, qty)
SELECT w.id, p.id, 15 + ((w.id * 37 + p.id * 53) % 110)
FROM warehouse w CROSS JOIN product p
WHERE NOT EXISTS (SELECT 1 FROM inventory i WHERE i.warehouse_id = w.id AND i.product_id = p.id);
