# 🛵 Mumbai Delivery Ops — Admin Delivery Mapper & Live Operations Command Center

A quick-commerce (dark-store) operations center for **all of Mumbai**: 58 localities from Colaba and Malabar Hill to Dahisar, Gorai and Mulund
(island city, western and eastern suburbs, East/West split where the railway divides an area), 21 dark stores and
112 riders. Admins watch orders, riders, dark stores, revenue, localities, traffic and unmet demand
live on a map. They see Fibonacci-heap dispatch and min-cost-flow rider allocation working, get SLA alerts, override
decisions, and run what-if simulations (peak hour, monsoon, road closures, more or fewer riders).

> **Simulated vs real.** Customers, orders, baskets, riders, inventory, traffic and disruptions are **simulated**
> (fixed seed, reproducible). The map (OpenStreetMap), road network (OSRM) and locality names/pincodes are real.
> Locality boundaries are **approximate**, not official wards: each starts as a box split at the railway line, then
> (once OSRM is reachable) every customer is moved to the nearest real road and the area is reshaped to the land its
> customers cover (200 m around each one, gaps closed, clipped to the original box, gaps under ~800 m filled), so areas, orders and delivery zones follow
> the coastline and creeks instead of covering water.
> Dark-store names and locations are invented. Locality populations are rounded estimates (Census 2011 ward totals
> split by area), used for demand weighting and per-capita comparisons only.
>
> **New here?** Open the dashboard: the **Start here** tab explains every icon and number in plain words and lists
> recommendations you can apply with one click.

The original CIA 3 **Ambulance Dispatcher** still works: the button at the top right switches to it.
Its documentation is in [docs/AMBULANCE.md](docs/AMBULANCE.md).

---

## Run it

**Prerequisites:** JDK 21+, Node 18+, Docker Desktop (for PostgreSQL + PostGIS). Maven is not needed (wrapper included).

```bash
cp .env.example .env          # optional: every value already has a default
docker compose up -d          # PostgreSQL 16 + PostGIS 3.4 on port 5433, data kept in a Docker volume
```

```bash
cd backend
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"   # if your default Java is older than 21
./mvnw spring-boot:run        # http://localhost:8081
```

```bash
cd frontend
npm install
npm run dev                   # http://localhost:5173 (proxies /api and /ws to :8081)
```

On first start the backend runs the Flyway migrations, creates simulated customers in every locality (in proportion
to its population) and 112 riders, and generates 21 days of simulated order history (about 44,000 orders, ~11 s).
A database from an older version is topped up instead: localities and stores added by a migration get customers and riders. The live city then starts at 1 simulated minute per second.
After a restart it carries on from where it stopped: open orders, riders' routes and today's KPIs are restored from the database.

> Port 8080 is often taken by Docker Desktop on Windows, which is why the backend uses **8081**.

### Configuration (environment variables, see `.env.example`)

| Variable | Default | Meaning |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `localhost` / `5433` / `mumbai_ops` / `ops` / `ops_dev_password` | PostgreSQL connection |
| `SERVER_PORT` | `8081` | Backend port |
| `SERVER_ADDRESS` | `127.0.0.1` | Interface the backend listens on (`0.0.0.0` to expose it; then also set `ADMIN_TOKEN`) |
| `ADMIN_TOKEN` | empty (no check) | If set, admin actions need header `X-Admin-Token` |
| `OSRM_URL` | public OSRM demo table endpoint | Road-distance service. Set your own OSRM for heavy use. Falls back to straight-line × 1.35 if unreachable |
| `SIM_TICK_MS` | `1000` | Real milliseconds per simulated minute |
| `SIM_SEED` | `42` | Random seed (same seed = same city) |
| `SIM_HISTORY_DAYS` | `21` | Days of history generated on first start |
| `SIM_AUTOSTART` | `true` | Start the live simulation automatically |

Reset everything: `docker compose down -v && docker compose up -d`, then restart the backend.

### Tests

```bash
cd backend && ./mvnw test     # 75 tests; the 8 integration tests start a throwaway PostGIS container (Docker needed)
cd frontend && npm test       # route-geometry helper tests
```

---

## The 12 features and where they live

| # | Feature | How it works | Code |
|---|---|---|---|
| 1 | **AOV** | Overall, by locality, by pincode, top customers; click a customer for full spending history | `analytics/AnalyticsService.aov`, `customer` |
| 2 | **Revenue forecast** | Next-hour = 0.7 × same-hour weekday/weekend baseline × damped 3-hour trend + 0.3 × 30-min velocity, scaled by the active scenario's demand factor; back-tested over 72 h (WAPE ≈ 20 % on clean data, close to the ~16 % noise floor of ~50 orders/hour) | `forecast/RevenueForecastService` |
| 3 | **Warehouse optimisation** | Capacitated weighted **greedy set cover** over 7 days of demand cells: how many dark stores, where, and current coverage | `warehouses/WarehousePlanner` |
| 4 | **Weekday vs weekend** | Orders/day, revenue/day, AOV, peak hour, riders needed at peak, top items/day, unmet demand/day | `AnalyticsService.weekdayWeekend` |
| 5 | **Dynamic rider allocation** | Every tick: **min-cost max-flow** over riders × job batches (cost = ETA + 4 × lateness + workload). Rebalancing suggestions with one-click apply | `allocation/RiderAllocator`, `AnalyticsService.utilization` |
| 6 | **Priority queue** | **Fibonacci heap**: SLA deadline key, FIFO tie-break, waiting-time aging and escalation via decrease-key, true deletion on cancel, stale-entry skipping. **Just-in-time dispatch**: orders wait in the heap while being packed and go to a rider when packing is ≤ 3 min from done or the promise is ≤ 12 min away | `dispatch/SlaQueue`, `algo/FibonacciHeap` |
| 7 | **Locality comparison** | Any localities side by side (one click for East vs West of an area); search by name or pincode | `AnalyticsService.compareLocalities`, `searchLocalities` |
| 8 | **Dynamic delivery radius** | Per-store polygon: 24 bearings walked outward at traffic- and disruption-adjusted speed with per-direction road detours from OSRM; closures cut it | `geo/CoverageService`, `geo/TrafficModel` |
| 9 | **Smart batching** | Same store, drops ≤ 1.5 km apart, detour ≤ 40 %, every order still on time, ≤ 3 per rider | `dispatch/Batcher` |
| 10 | **SLA alerts** | Packing delay, SLA risk (rider ETA or unassigned too long) with auto-escalation, SLA breach; live over WebSocket, acknowledgeable | `simulation/SimulationEngine.slaChecks`, `alerts/AlertService` |
| 11 | **Unfulfilled-demand heatmap** | Searches, abandoned carts, out-of-stock, unserviceable addresses, aggregated on a PostGIS grid | `AnalyticsService.heatmap` |
| 12 | **Rider utilisation** | Busy % per rider, idle / active / overloaded, store backlog vs free riders, rebalancing moves | `AnalyticsService.utilization` |

### The 7 extra points

| # | Point | How it works | Where |
|---|---|---|---|
| 1 | **AOV by how much customers buy** | Customers grouped by spend in the window (occasional < ₹1k, regular ₹1k–5k, loyal ₹5k–15k, VIP ₹15k+): customers, orders each, AOV, revenue | Analytics → AOV · `AnalyticsService.aov` (`spendTiers`) |
| 2 | **1-hour revenue estimate** | The forecast above, shown in the header and refreshed every minute, with its back-test error | header KPI · `RevenueForecastService` |
| 3 | **Dark stores needed per area** | Busiest hour of an average day in that locality (orders + orders lost as unserviceable) × 1.15 ÷ 70 orders/h per store; at least 1 for any area with ≥ 3 orders/h | Planning · `GET /warehouses/needed` |
| 4 | **Weekday and weekend revenue estimate** | Next weekday and next weekend day: 60 % of the last 3 days of that type + 40 % of the 21-day average | Analytics → Weekday vs weekend |
| 5 | **More riders in popular areas** | Riders needed per store = last-hour orders ÷ 2.2 deliveries per rider-hour + 1; free riders move from quiet stores to the busiest first; *Apply all* | Planning → Riders by demand · `SimulationEngine.staffingPlan` |
| 6 | **Priority by order received** | Toggle between *promised deadline first* (SLA) and *order received first* (FIFO); switching re-keys the Fibonacci heap | Live tab · `SlaQueue.Policy`, `POST /sim/priority` |
| 7 | **Area vs area with population, live view** | Compare any localities (e.g. Borivali East vs West) with population, orders per 1,000 residents, % of residents who buy; search a locality for a live panel (open orders, at risk, riders inside / nearby, congestion, last-hour orders and revenue, searches, unserviceable demand) | Analytics, header search · `GET /localities/{id}/live` |

**Recommendations** (Start here tab, `GET /recommendations`) turn these numbers into plain next steps, each with a
button where the dashboard can act: move free riders to busy stores, switch to deadline priority when orders are
about to be late, add or remove riders, open a store where demand is lost, re-open a switched-off store, restock.

## Algorithms

| Algorithm | Role | Complexity |
|---|---|---|
| **Fibonacci heap** (own) | Dispatch queue. Keys only decrease while an order waits (aging, escalation), so `decreaseKey` is O(1) amortized; `remove` is O(log n) | insert O(1), extract-min O(log n) amortized |
| **Edmonds–Karp** (own, kept as baseline) | Max-flow on the rider → batch network | O(V·E²) |
| **Dinic** (own) | Faster max-flow on the same network | O(E·√V) on unit bipartite graphs |
| **Min-cost max-flow** (own, SPFA successive shortest paths) | **Live allocator**: maximum assignments at minimum total ETA + lateness + workload | O(F·V·E) |
| **Greedy set cover** | Dark-store count and placement | ln n + 1 approximation, NP-hard problem |
| **Randomized Quickselect** (from CIA 3) | Ambulance forecast percentiles | expected O(n) |

**Why not max-flow alone?** Edmonds–Karp and Dinic only maximise how many orders get a rider. Among all maximum
assignments they pick an arbitrary one. In a live peak-hour run on the same 9 orders (Planning → *Compare now*):

| Algorithm | Assigned | Total ETA | Predicted late orders |
|---|---|---|---|
| Min-cost max-flow | 9 | 132 min | 1 |
| Edmonds–Karp | 9 | 256 min | 7 |
| Dinic | 9 | 290 min | 8 |

Benchmarks for both the heap (against an indexed binary heap) and the three allocators (n = 20, 60, 150) run from
Planning → *Run benchmarks*. As in CIA 3, the Fibonacci heap's advantage is asymptotic; the array binary heap is
usually faster in wall-clock time at these sizes.

## Architecture

Modular Spring Boot monolith (`backend/src/main/java/com/dispatch/delivery/`):

```
simulation/  SimulationEngine (tick loop, lifecycle, overrides), HistorySeeder
dispatch/    SlaQueue (Fibonacci heap), Batcher
allocation/  RiderAllocator (min-cost flow / EK / Dinic), AllocationBenchmark
geo/         TrafficModel (speed by hour, scenario, disruptions), CoverageService (dynamic radius)
orders/      OrderRepository        riders/  RiderRepository        warehouses/ WarehouseRepository, WarehousePlanner
analytics/   AnalyticsService       forecast/ RevenueForecastService alerts/     AlertService
api/         OpsController (REST /api/ops/**), LiveSocket (WebSocket /ws/live)
```

* The backend owns every business decision; React only renders and sends admin commands.
* Live state is in memory (one writer thread, guarded by the engine lock). Every change is written to PostgreSQL
  in batches at the end of each tick: orders, items, a **full order-event history**, assignments, rider location
  history, alerts, demand events, operational events and stock.
* Database: Flyway migrations (`db/migration`), PostGIS `GEOGRAPHY` columns generated from lat/lng, GiST spatial
  indexes, and spatial SQL (`ST_Covers`, `ST_DWithin`, `ST_SnapToGrid`, `ST_AsGeoJSON`).

### Data model

`locality` (PostGIS polygon, pincode) · `warehouse` · `product` · `inventory` · `customer` · `rider` ·
`rider_location` (history) · `orders` · `order_item` · `order_event` (history) · `delivery_assignment` · `alert` ·
`demand_event` (search / abandoned cart / out-of-stock / unserviceable) · `operational_event` (closures,
waterlogging, toggles, overrides).

## Dashboard

* **Header:** live/paused state, simulated clock, scenario, locality/pincode search (flies to and outlines it), switch to the ambulance app.
* **KPIs** (hover for an explanation): revenue today, next-hour revenue estimate, orders in progress, riders on a delivery / free,
  average order value, **on time (SLA met)** = share of today's deliveries that arrived before the promised time
  (20 min small basket, 30 min big, +10 in monsoon), **how busy stores are (store utilisation)** = orders received in the
  last hour ÷ hourly packing capacity, average delivery time.
* **Start here tab:** recommendations with one-click actions, what each icon means, a glossary, things to try.
* **Map layers:** labelled locality outlines (names shown when zoomed in), 🏬 dark stores (waiting-order badge; smaller when the whole city is in view), 🛵 riders on real roads with their remaining route, orders by SLA risk, SLA-risk-only filter,
  coverage polygons, traffic (locality congestion), unmet-demand heatmap (type and time window), disruptions, recommended sites.
* **Live tab:** the dispatch queue in heap order with Escalate / Assign to a rider / Cancel, and the alert feed with Ack.
* **Analytics tab:** time filter, revenue forecast and back-test chart, revenue by hour, AOV tables, customer history, weekday vs weekend, locality comparison.
* **Planning tab:** activate / deactivate dark stores, warehouse optimisation, rider utilisation and rebalancing, allocation comparison, benchmarks.
* **Simulation tab:** run / pause, speed, scenario (normal, peak, monsoon), demand multiplier, add or remove riders, report road closures or waterlogging by clicking the map.

## API (prefix `/api/ops`)

| Method | Path | Purpose |
|---|---|---|
| GET | `/snapshot` | Live state (also pushed every tick on `ws://host/ws/live`) |
| GET | `/alerts?limit=` · POST `/alerts/{id}/ack` | Alert feed |
| GET | `/localities?q=` · `/boundaries` · `/coverage` | Search, GeoJSON boundaries, delivery polygons |
| GET | `/analytics/aov?hours=` · `/analytics/customers/{id}` | AOV, spending history |
| GET | `/analytics/weekday-weekend?days=` · `/analytics/localities?ids=&hours=` | Comparisons |
| GET | `/analytics/heatmap?type=&hours=` · `/analytics/revenue?hours=` · `/analytics/utilization` | Heatmap, revenue, riders |
| GET | `/forecast/revenue` · `/warehouses/plan?budget=&target=&capacity=` | Forecast, store placement |
| POST | `/warehouses/{id}/active` `{active}` | Activate / deactivate a store |
| POST | `/orders/{id}/cancel` · `/orders/{id}/escalate` `{minutes}` · `/orders/{id}/assign` `{riderId}` | Overrides |
| POST | `/riders/{id}/move` `{storeId}` | Rebalance a rider |
| GET | `/recommendations` | Plain-language next steps with an action each |
| GET | `/warehouses/needed?days=` · `/localities/{id}/live` | Stores needed per locality, live locality panel |
| GET | `/riders/staffing` · POST `/riders/staffing/apply` | Riders needed per store by demand, apply the moves |
| POST | `/riders/fill-gaps` · `/riders/trim` | Bring in exactly the riders each store needs for the next hour · take off only riders spare for the next 3 hours |
| POST | `/sim/priority` `{policy: SLA \| FIFO}` | Dispatch priority |
| GET | `/warehouses/health?days=` | Every store: OVERLOADED / BUSY / OK / UNDERUSED, the reasons, and fixes (each with its action) |
| POST | `/warehouses` `{localityId \| lat,lng, capacity, riders}` | Open **one** new dark store (stock + riders included) |
| POST | `/warehouses/{id}/capacity` `{capacity}` · `/warehouses/{id}/restock` | Raise packing capacity, restock low items |
| POST | `/riders/move-between` `{fromStoreId, toStoreId, count}` · `/riders/hire` `{storeId, count}` | Move riders between two stores, hire riders for a store |
| GET | `/allocation/compare` · `/benchmark` | Algorithm comparison and benchmarks |
| POST | `/sim/control` `{running, scenario, demandMultiplier, tickMs}` · `/sim/riders` `{delta}` · `/sim/disruptions` | What-if |

Invalid input returns HTTP 400 and unknown ids return 404, both with `{"error": "..."}`.

## Limitations (honest list)

* **Simulated data only.** No real customers, orders or traffic feeds are connected. External live-data APIs would need credentials; the simulation is the built-in fallback.
* **Access control is minimal.** The backend listens only on 127.0.0.1 by default. Set `ADMIN_TOKEN` and every admin action (POST) needs the `X-Admin-Token` header; the dashboard asks for it once per tab. There are no user accounts or roles.
* **Riders on roads:** riders are routed **in-process** on Mumbai's real road network: OpenStreetMap roads (motorways down to residential streets) downloaded once from Overpass in 9 tiles, reduced to the largest connected network, saved to `backend/data/mumbai-roads.bin` and loaded from disk on later starts. Each leg is an **A\* search on our own Fibonacci heap** (straight-line distance as the admissible heuristic; `decreaseKey` when a shorter way to a junction is found), cached per ~110 m cell pair. No per-trip network calls, so 200 riders at peak stay on roads. Until the graph is loaded (first start: a few minutes), OSRM road shapes are used. One-way restrictions are ignored (two-wheelers).
* **Dispatch timing.** An order goes to a rider once the nearest free rider would reach the store about when packing finishes (or the promise is ≤ 12 min away), so riders arrive as the bag is ready. Until then the order is "waiting for rider" even with free riders nearby: the 📦 marks the customer's door, but the rider must collect from the 🏬 first.
* **Rider staffing uses expected demand.** Each store's usual orders for the coming hours (last 7 days, by hour of day) × scenario × demand slider. *Add* closes next-hour gaps per store; *remove* only takes riders spare for the whole next 3 hours. The gap between the two rules stops the old remove-then-add-5-again loop at peak.
* **Idle riders wait at their store.** Every delivery starts with a pickup there, so free riders are parked at their dark store, not spread around the area. With 112 riders and a quiet hour most are free; the Start here tab then suggests taking some off shift.
* **Road distances are approximate per ~110 m.** Live ETAs, rider movement and allocation use OSRM road distances, cached per ~110 m grid-cell pair (the road ratio between cell centres is applied to the exact points). New pairs are fetched in the background, at most one OSRM table request per second; until a pair arrives, straight-line × 1.35 is used, and the header says how many are still pending. At high simulation speeds the backlog grows and then drains.
* **Restarts restore open orders.** Riders' positions come from the last location sample (taken every 5 simulated minutes), so a restored rider may jump back up to 5 minutes along their route. Orders already picked up by a rider who is no longer on shift are cancelled as `RIDER_OFF_SHIFT_AT_RESTART`.
* **SLA-risk alerts are an early warning.** ETAs are re-predicted every minute from the rider's position. In a measured peak run, every late order (19 of 19) was flagged before its deadline, and about 1 alert in 4 was a real problem; the rest were rescued by dispatch. Breaches are exact.
* **Planning is approximate.** Greedy set cover, the forecast and batching are heuristics with stated assumptions, not optimal solutions.

## Data credits

* Map tiles and the road network in `backend/data/mumbai-roads.bin`: © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, available under the Open Database Licence (ODbL).
* Road distances: [OSRM](https://project-osrm.org) public demo server (fair use).
* Locality boundaries and populations are approximate estimates made for this project, not official data.
