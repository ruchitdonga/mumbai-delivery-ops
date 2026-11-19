-- Mumbai Delivery Ops schema. PostGIS geography columns are generated from lat/lng so the app
-- writes plain numbers and every spatial query still gets a GiST index.
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE locality (
    id          SERIAL PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    area        TEXT NOT NULL,                 -- e.g. Borivali (both East and West share it)
    side        TEXT NOT NULL CHECK (side IN ('EAST', 'WEST')),
    pincode     TEXT NOT NULL,
    center_lat  DOUBLE PRECISION NOT NULL,
    center_lng  DOUBLE PRECISION NOT NULL,
    boundary    GEOGRAPHY(POLYGON, 4326) NOT NULL,
    approximate BOOLEAN NOT NULL DEFAULT TRUE  -- boundaries are hand-drawn approximations, not official wards
);
CREATE INDEX locality_boundary_gix ON locality USING GIST (boundary);
CREATE INDEX locality_pincode_idx ON locality (pincode);

CREATE TABLE warehouse (
    id                SERIAL PRIMARY KEY,
    code              TEXT NOT NULL UNIQUE,
    name              TEXT NOT NULL,
    locality_id       INT NOT NULL REFERENCES locality (id),
    lat               DOUBLE PRECISION NOT NULL,
    lng               DOUBLE PRECISION NOT NULL,
    geo               GEOGRAPHY(POINT, 4326) GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography) STORED,
    capacity_per_hour INT NOT NULL CHECK (capacity_per_hour > 0),
    active            BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX warehouse_geo_gix ON warehouse USING GIST (geo);

CREATE TABLE product (
    id       SERIAL PRIMARY KEY,
    sku      TEXT NOT NULL UNIQUE,
    name     TEXT NOT NULL,
    category TEXT NOT NULL,
    price    NUMERIC(10, 2) NOT NULL CHECK (price > 0)
);

CREATE TABLE inventory (
    warehouse_id INT NOT NULL REFERENCES warehouse (id),
    product_id   INT NOT NULL REFERENCES product (id),
    qty          INT NOT NULL CHECK (qty >= 0),
    PRIMARY KEY (warehouse_id, product_id)
);

CREATE TABLE customer (
    id          SERIAL PRIMARY KEY,
    name        TEXT NOT NULL,
    locality_id INT NOT NULL REFERENCES locality (id),
    lat         DOUBLE PRECISION NOT NULL,
    lng         DOUBLE PRECISION NOT NULL,
    geo         GEOGRAPHY(POINT, 4326) GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography) STORED,
    high_rise   BOOLEAN NOT NULL DEFAULT FALSE,
    simulated   BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX customer_locality_idx ON customer (locality_id);

CREATE TABLE rider (
    id                SERIAL PRIMARY KEY,
    code              TEXT NOT NULL UNIQUE,
    name              TEXT NOT NULL,
    home_warehouse_id INT NOT NULL REFERENCES warehouse (id),
    capacity          INT NOT NULL CHECK (capacity BETWEEN 1 AND 5),
    on_shift          BOOLEAN NOT NULL DEFAULT TRUE,
    simulated         BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE rider_location (
    id          BIGSERIAL PRIMARY KEY,
    rider_id    INT NOT NULL REFERENCES rider (id),
    lat         DOUBLE PRECISION NOT NULL,
    lng         DOUBLE PRECISION NOT NULL,
    status      TEXT NOT NULL,
    recorded_at TIMESTAMP NOT NULL
);
CREATE INDEX rider_location_rider_time_idx ON rider_location (rider_id, recorded_at);

CREATE TABLE orders (
    id            BIGSERIAL PRIMARY KEY,
    customer_id   INT NOT NULL REFERENCES customer (id),
    warehouse_id  INT REFERENCES warehouse (id),
    locality_id   INT NOT NULL REFERENCES locality (id),
    status        TEXT NOT NULL CHECK (status IN ('PLACED', 'PREPARING', 'READY', 'ASSIGNED', 'PICKED_UP', 'DELIVERED', 'CANCELLED')),
    total         NUMERIC(10, 2) NOT NULL CHECK (total >= 0),
    item_count    INT NOT NULL CHECK (item_count > 0),
    lat           DOUBLE PRECISION NOT NULL,
    lng           DOUBLE PRECISION NOT NULL,
    geo           GEOGRAPHY(POINT, 4326) GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography) STORED,
    placed_at     TIMESTAMP NOT NULL,
    promised_at   TIMESTAMP NOT NULL,       -- SLA deadline
    ready_at      TIMESTAMP,
    picked_at     TIMESTAMP,
    delivered_at  TIMESTAMP,
    cancelled_at  TIMESTAMP,
    cancel_reason TEXT,
    scenario      TEXT NOT NULL,
    simulated     BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX orders_placed_idx ON orders (placed_at);
CREATE INDEX orders_status_idx ON orders (status) WHERE status NOT IN ('DELIVERED', 'CANCELLED');
CREATE INDEX orders_locality_idx ON orders (locality_id, placed_at);
CREATE INDEX orders_customer_idx ON orders (customer_id);
CREATE INDEX orders_geo_gix ON orders USING GIST (geo);

CREATE TABLE order_item (
    order_id   BIGINT NOT NULL REFERENCES orders (id),
    product_id INT NOT NULL REFERENCES product (id),
    qty        INT NOT NULL CHECK (qty > 0),
    unit_price NUMERIC(10, 2) NOT NULL,
    PRIMARY KEY (order_id, product_id)
);

-- Full lifecycle history: one row per status change, never updated.
CREATE TABLE order_event (
    id         BIGSERIAL PRIMARY KEY,
    order_id   BIGINT NOT NULL REFERENCES orders (id),
    event_type TEXT NOT NULL,
    at         TIMESTAMP NOT NULL,
    details    TEXT
);
CREATE INDEX order_event_order_idx ON order_event (order_id, at);

CREATE TABLE delivery_assignment (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT NOT NULL REFERENCES orders (id),
    rider_id    INT NOT NULL REFERENCES rider (id),
    batch_key   TEXT NOT NULL,           -- orders delivered together share it
    assigned_at TIMESTAMP NOT NULL,
    eta_min     DOUBLE PRECISION NOT NULL,
    algorithm   TEXT NOT NULL,
    manual      BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX delivery_assignment_rider_idx ON delivery_assignment (rider_id, assigned_at);
CREATE INDEX delivery_assignment_order_idx ON delivery_assignment (order_id);

CREATE TABLE alert (
    id              BIGSERIAL PRIMARY KEY,
    type            TEXT NOT NULL,
    severity        TEXT NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    message         TEXT NOT NULL,
    order_id        BIGINT REFERENCES orders (id),
    rider_id        INT REFERENCES rider (id),
    warehouse_id    INT REFERENCES warehouse (id),
    created_at      TIMESTAMP NOT NULL,
    acknowledged_at TIMESTAMP
);
CREATE INDEX alert_created_idx ON alert (created_at);

-- Demand the business did not fulfil: searches, abandoned carts, out-of-stock, unserviceable addresses.
CREATE TABLE demand_event (
    id          BIGSERIAL PRIMARY KEY,
    type        TEXT NOT NULL CHECK (type IN ('SEARCH', 'ABANDONED_CART', 'OUT_OF_STOCK', 'UNSERVICEABLE')),
    lat         DOUBLE PRECISION NOT NULL,
    lng         DOUBLE PRECISION NOT NULL,
    geo         GEOGRAPHY(POINT, 4326) GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography) STORED,
    locality_id INT REFERENCES locality (id),
    product_id  INT REFERENCES product (id),
    query       TEXT,
    at          TIMESTAMP NOT NULL,
    simulated   BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX demand_event_at_idx ON demand_event (at, type);
CREATE INDEX demand_event_geo_gix ON demand_event USING GIST (geo);

-- Disruptions: road closures, waterlogging, surges. Radius-based so they can be drawn and checked spatially.
CREATE TABLE operational_event (
    id         BIGSERIAL PRIMARY KEY,
    type       TEXT NOT NULL CHECK (type IN ('ROAD_CLOSURE', 'WATERLOGGING', 'DEMAND_SURGE', 'WAREHOUSE_TOGGLE', 'SCENARIO_CHANGE', 'MANUAL_OVERRIDE')),
    lat        DOUBLE PRECISION,
    lng        DOUBLE PRECISION,
    radius_m   DOUBLE PRECISION,
    details    TEXT NOT NULL,
    starts_at  TIMESTAMP NOT NULL,
    ends_at    TIMESTAMP
);
CREATE INDEX operational_event_active_idx ON operational_event (ends_at);
