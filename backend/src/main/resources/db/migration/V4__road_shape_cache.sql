-- OSRM road geometries riders drive along, cached across restarts (key = start cell > end cell, ~110 m cells).
-- pts = "lat,lng;lat,lng;..." polyline.
CREATE TABLE road_shape (
    cell_key TEXT PRIMARY KEY,
    pts      TEXT NOT NULL
);
