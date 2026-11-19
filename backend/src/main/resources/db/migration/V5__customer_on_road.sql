-- Customers were scattered at random inside each (approximate, rectangular) locality, so some fell in the sea or a
-- creek. A background job moves each one to the nearest road point (OSRM) once; this flag records that it was done.
ALTER TABLE customer ADD COLUMN on_road BOOLEAN NOT NULL DEFAULT FALSE;
