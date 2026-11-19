-- Fills the parts of Mumbai no locality covered yet (Malabar Hill, Churchgate, Mahalaxmi, Mazgaon, Sewri, Dharavi, BKC,
-- Juhu, Versova, Madh/Marve, Gorai/Manori, Wadala East, Trombay, Mankhurd, Vidyavihar, Kanjurmarg, Nahur), and keeps each
-- locality's original hand-drawn box in `box` so the land shape can be rebuilt from scratch (it is clipped to the box).
-- Boxes and populations are APPROXIMATE (rounded estimates), like V2/V3.
ALTER TABLE locality ADD COLUMN box GEOGRAPHY(POLYGON, 4326);

-- (name, lng_min, lng_max, lat_min, lat_max): the boxes from V2 and V3
UPDATE locality l SET box = ST_GeogFromText(format('SRID=4326;POLYGON((%s %s, %s %s, %s %s, %s %s, %s %s))', x1, y1, x2, y1, x2, y2, x1, y2, x1, y1))
FROM (VALUES
 ('Borivali West', 72.8200, 72.8510, 19.2180, 19.2500), ('Borivali East', 72.8510, 72.8850, 19.2180, 19.2500),
 ('Kandivali West', 72.8200, 72.8500, 19.1950, 19.2180), ('Kandivali East', 72.8500, 72.8850, 19.1950, 19.2180),
 ('Malad West', 72.8200, 72.8490, 19.1700, 19.1950), ('Malad East', 72.8490, 72.8850, 19.1700, 19.1950),
 ('Goregaon West', 72.8200, 72.8490, 19.1450, 19.1700), ('Goregaon East', 72.8490, 72.8850, 19.1450, 19.1700),
 ('Andheri West', 72.8200, 72.8470, 19.1050, 19.1450), ('Andheri East', 72.8470, 72.8850, 19.1050, 19.1450),
 ('Dahisar West', 72.835, 72.858, 19.250, 19.275), ('Dahisar East', 72.858, 72.880, 19.250, 19.275),
 ('Vile Parle West', 72.825, 72.845, 19.090, 19.105), ('Vile Parle East', 72.845, 72.865, 19.090, 19.105),
 ('Santacruz West', 72.825, 72.842, 19.072, 19.090), ('Santacruz East', 72.842, 72.865, 19.072, 19.090),
 ('Khar West', 72.825, 72.842, 19.062, 19.072), ('Bandra West', 72.820, 72.842, 19.040, 19.062),
 ('Bandra East', 72.842, 72.855, 19.040, 19.072), ('Dadar West', 72.830, 72.845, 19.010, 19.040),
 ('Dadar East', 72.845, 72.855, 19.010, 19.030), ('Worli', 72.810, 72.830, 18.995, 19.025),
 ('Parel', 72.830, 72.850, 18.990, 19.010), ('Byculla', 72.825, 72.845, 18.965, 18.990),
 ('Tardeo', 72.808, 72.825, 18.955, 18.985), ('Girgaon', 72.812, 72.825, 18.940, 18.955),
 ('Fort', 72.825, 72.845, 18.925, 18.950), ('Colaba', 72.810, 72.835, 18.895, 18.925),
 ('Sion', 72.855, 72.880, 19.030, 19.055), ('Wadala', 72.855, 72.875, 19.005, 19.030),
 ('Chembur', 72.880, 72.915, 19.040, 19.075), ('Govandi', 72.915, 72.935, 19.045, 19.070),
 ('Kurla West', 72.865, 72.880, 19.055, 19.090), ('Kurla East', 72.880, 72.900, 19.075, 19.095),
 ('Ghatkopar West', 72.885, 72.908, 19.095, 19.115), ('Ghatkopar East', 72.915, 72.935, 19.070, 19.100),
 ('Vikhroli', 72.910, 72.940, 19.100, 19.125), ('Powai', 72.885, 72.910, 19.115, 19.135),
 ('Bhandup West', 72.925, 72.945, 19.135, 19.160), ('Mulund West', 72.940, 72.960, 19.165, 19.185),
 ('Mulund East', 72.960, 72.975, 19.160, 19.180)
) AS v(n, x1, x2, y1, y2)
WHERE l.name = v.n;
UPDATE locality SET box = boundary WHERE box IS NULL; -- safety net: any locality not listed keeps its current shape

INSERT INTO locality (name, area, side, pincode, center_lat, center_lng, boundary, box, population)
SELECT n, a, 'NONE', p, (y1 + y2) / 2, (x1 + x2) / 2, g, g, pop
FROM (
  SELECT n, a, p, x1, x2, y1, y2, pop,
         ST_GeogFromText(format('SRID=4326;POLYGON((%s %s, %s %s, %s %s, %s %s, %s %s))', x1, y1, x2, y1, x2, y2, x1, y2, x1, y1)) AS g
  FROM (VALUES
   ('Malabar Hill',        'Malabar Hill', '400006', 72.795, 72.808, 18.940, 18.965, 120000),
   ('Churchgate',          'Churchgate',   '400020', 72.815, 72.825, 18.925, 18.940,  60000),
   ('Mahalaxmi',           'Mahalaxmi',    '400034', 72.808, 72.825, 18.985, 18.995, 110000),
   ('Mazgaon',             'Mazgaon',      '400010', 72.845, 72.860, 18.950, 18.985, 140000),
   ('Sewri',               'Sewri',        '400015', 72.850, 72.870, 18.990, 19.005, 100000),
   ('Dharavi',             'Dharavi',      '400017', 72.845, 72.855, 19.030, 19.040, 300000),
   ('BKC & Kalanagar',     'BKC',          '400051', 72.855, 72.865, 19.055, 19.072,  40000),
   ('Juhu',                'Juhu',         '400049', 72.815, 72.825, 19.085, 19.105, 120000),
   ('Versova',             'Versova',      '400061', 72.805, 72.820, 19.105, 19.145, 150000),
   ('Madh & Marve',        'Madh',         '400061', 72.790, 72.820, 19.145, 19.210,  80000),
   ('Gorai & Manori',      'Gorai',        '400091', 72.785, 72.820, 19.210, 19.250,  50000),
   ('Wadala East',         'Wadala',       '400037', 72.880, 72.890, 19.005, 19.040, 120000),
   ('Trombay',             'Trombay',      '400088', 72.890, 72.935, 19.000, 19.040, 150000),
   ('Mankhurd',            'Mankhurd',     '400043', 72.935, 72.955, 19.040, 19.070, 200000),
   ('Vidyavihar',          'Vidyavihar',   '400086', 72.900, 72.915, 19.075, 19.095,  80000),
   ('Kanjurmarg',          'Kanjurmarg',   '400042', 72.940, 72.960, 19.100, 19.135, 100000),
   ('Nahur & Bhandup East','Nahur',        '400078', 72.945, 72.965, 19.135, 19.160, 120000)
  ) AS v(n, a, p, x1, x2, y1, y2, pop)
) s;

-- Rebuild every land shape with the gap-filling method (see WarehouseRepository.shapeLocalitiesToLand).
UPDATE locality SET boundary = box, land_shaped = FALSE;
