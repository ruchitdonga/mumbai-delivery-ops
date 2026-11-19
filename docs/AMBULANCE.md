# 🚑 Mumbai Ambulance ↔ Hospital Dispatcher

CIA 3 · Advanced Algorithms · B.Tech CS AI & ML · 2026–27

An emergency control room tool for Mumbai. Calls arrive with a location, a severity (1–5) and a type
(cardiac, trauma, burns, neuro, general). The system keeps them in priority order, assigns each one an
ambulance **and** a hospital bed that can treat it, explains why any call could not be served, and
forecasts how many ambulances each zone needs on a busy day.

| Technique | Syllabus unit | Where it runs |
|---|---|---|
| **Fibonacci heap** (insert, extractMin, **decreaseKey**) | Unit 2 | Waiting-call queue; "Escalate" = decreaseKey |
| **Edmonds–Karp max-flow + min-cut** (bipartite matching with capacities) | Unit 5 | Assigning ambulances and beds; min cut names the bottleneck |
| **Randomized Quickselect + Monte Carlo simulation** | Unit 6 (CO5) | p50/p90 demand per zone → ambulance staffing plan |

All three are written from scratch in `backend/src/main/java/com/dispatch/algo/`. The frontend only
displays results; no algorithm runs in the browser.

---

## Run it

**Prerequisites:** JDK 21+, Node.js 18+. Maven is **not** needed (the Maven wrapper downloads it).
The map tiles come from OpenStreetMap, so the browser needs internet.

> ⚠ If `JAVA_HOME` points to an older JDK (e.g. Java 8), Maven fails with
> `class file has wrong version 61.0, should be 52.0`. Point it at JDK 21 for that terminal, as below.

**1. Backend** (port 8080)

```bash
cd backend
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"   # Git Bash; adjust path
./mvnw spring-boot:run
```

PowerShell: `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"; .\mvnw.cmd spring-boot:run`

**2. Frontend** (port 5173, proxies `/api` to 8080)

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173.

**3. Tests** (36 JUnit tests)

```bash
cd backend
./mvnw test
```

No environment variables or secrets are required.

---

## Demo script (≈ 8 minutes)

**First time?** Press **▶ Play guided demo** in the side panel. It runs the whole flow below by itself
(real API calls, nothing faked) with a caption on the map for each step. **❓ How to use** in the header brings the
beginner guide back.

**Live city:** press **🔴 Start live city** and the city runs by itself: 1–3 random emergencies every 6 s, ambulances
sent automatically with the Smart method. The bar under the header shows live totals (free ambulances, free beds,
waiting, in hospital, patients helped, average response time).

1. **Dispatch tab.** The sample city loads: 40 hospitals (🏥, 148 beds), 50 ambulances (🚑), 12 waiting patients (bigger dot = more serious).
   Every one of the 12 areas has all five kinds of care (general, heart, injury, burns, brain/stroke) within 2 km.
2. **Escalate** a low-severity call → it jumps up the queue (Fibonacci heap `decreaseKey`).
3. Click the map, pick severity/type, **Add call**. Try a point in the sea or outside Mumbai → clear validation error.
4. **Simulate 10 random calls**, then **Compare vs greedy** → served count, ETA and runtime side by side.
5. **Dispatch now** → each 🚑 drives to its patient (dashed line), then to the hospital (solid line),
   at 1 simulated minute = 1 second. On arrival the frontend calls `POST /api/ambulances/{id}/arrive`; the backend frees
   the ambulance and parks it at that hospital, so it can be dispatched again. The patient is **admitted**: the bed stays
   taken for a demo-time stay of 25 s (severity 1) to 45 s (severity 5), then the patient goes home and the bed frees up
   (the number on each 🏥 is its free beds; the "In hospital" panel shows the countdowns).
   Full hospitals fade; unserved calls are listed with a reason and the min-cut bottleneck.
6. Set ambulance range to 1 km and dispatch → many calls unserved with "No free ambulance within 1 km".
7. **Forecast tab** → run the Monte Carlo; compare the p90 plan with the mean-based plan.
8. **Benchmarks tab** → run all benchmarks (3 sizes each, against baselines).
9. **Upload scenario** → load your own JSON (format below). Upload `{}` to show the error handling.

---

## How it works

### 1. Priority queue: Fibonacci heap (`algo/FibonacciHeap.java`)

Key = `(5 − severity) × 10¹² + arrivalSequence`, so severity 5 comes first and ties are first-come-first-served.
When a patient gets worse, severity rises, the key drops, and that is `decreaseKey`: cut the node from its parent,
cascading cuts on marked ancestors.

| Operation | Amortized time |
|---|---|
| insert, peekMin, decreaseKey | O(1) |
| extractMin (with consolidation) | O(log n) |
| Space | O(n) |

### 2. Matching: Edmonds–Karp max-flow (`algo/MaxFlow.java`, `service/DispatchService.java`)

Calls are processed in **severity tiers** (all 5s, then all 4s, …). For each tier, on whatever is still free:

```
S ──1──▶ ambulance ──1 (if within ambulance range)──▶ patient_in ──1──▶ patient_out
          ──1 (if hospital treats this type and is within range)──▶ hospital ──free beds──▶ T
```

* Splitting the patient into `in → out` with capacity 1 forces **one ambulance + one bed** per patient.
* Integer capacities ⇒ an integral max flow exists (integrality theorem), so flow-1 edges read directly as assignments.
* **Max flow = the largest number of calls in that tier that can get both resources.** Greedy cannot guarantee this
  (see `MaxFlowTest.greedyTrapIsSolvedByAugmentingThroughReverseEdge` and `DispatchServiceTest.maxFlowServesMoreThanGreedyOnTrapInstance`).
* Tiering guarantees a lower-severity call can never take a resource that a higher-severity call could use.
* After max-flow, the **min cut** (nodes reachable from S in the residual graph) tells the operator what limited
  the tier: all ambulances used, or which hospitals are full.
* **Phase 2 (local search):** swap ambulances/hospitals between calls or take a closer free one whenever it strictly
  shortens the total distance. It never drops a call, so the count and the tier guarantee are kept.

Complexity per tier: Edmonds–Karp is **O(V·E²)**. Here V = A + 2P + H + 2 and E ≤ A·P + P·H + A + P + H. In practice
the number of augmenting paths is at most min(A, P), so it is O(min(A,P) · E).

### 3. Staffing forecast: Monte Carlo + randomized Quickselect (`service/ForecastService.java`, `algo/RandomizedSelect.java`)

For each zone, simulate `days` independent days of calls ~ Poisson(historical calls/day) (Knuth's sampler).
Randomized Quickselect (random pivot, 3-way partition) finds the p50 and the target percentile in **expected O(n)**
time without sorting. Recommended ambulances = ⌈p90 / callsPerAmbulance⌉. The table also shows the coverage of a
naive **mean-based** plan: planning for the average day leaves some zones short on 30%+ of days.

---

## Evidence

### Tests: `backend/src/test` (36 tests, all passing)

| Class | Tests | Edge / invalid / stress cases |
|---|---|---|
| `FibonacciHeapTest` | 6 | empty heap, single element, larger key rejected, 20k random ops vs reference |
| `MaxFlowTest` | 6 | disconnected sink, invalid nodes/caps, CLRS example = 23, min-cut = max-flow on 50 random graphs, 2000-node stress |
| `RandomizedSelectTest` | 6 | empty, k out of range, all duplicates, 500 random trials vs sort |
| `DispatchServiceTest` | 12 | priority respected, escalation, max-flow > greedy, infeasible speciality, beds full, out-of-area, 120-call feasibility check |
| `ForecastServiceTest` | 5 | Poisson mean = variance = λ, reproducible with seed, p90 coverage ≥ 90%, bad params, no zones |

### Benchmarks: `GET /api/benchmark` or the Benchmarks tab

Fixed seeds, three sizes each, median of 3 runs after a warm-up. Sample run on the dev laptop:

| Experiment | n | Ours | Baseline | Finding |
|---|---|---|---|---|
| p90 selection | 100k / 1M / 5M | Quickselect 1.0 / 13 / 57 ms | Arrays.sort 3.3 / 38 / 188 ms | ~3× faster, same answer |
| Matching | 100 / 400 / 1000 calls | max-flow serves 92 / 366 / 943 | greedy serves 76 / 300 / 771 | ~22% more calls served; costs ms instead of µs |
| Priority queue (mixed) | 10k / 100k / 500k | Fibonacci heap | indexed binary heap | Binary heap is faster (3–5×) |
| Escalation storm (decreaseKey-heavy) | 10k / 100k / 500k | Fibonacci heap | indexed binary heap | Gap shrinks to 1.2–3× |

**Honest note on the heap:** the Fibonacci heap's advantage is asymptotic (amortized O(1) decreaseKey). In practice its
pointer-based nodes cause cache misses and larger constants, so a compact array heap still wins at these sizes. The gap
narrows on decreaseKey-heavy workloads, exactly as the amortized analysis predicts. Expect a viva question on this.

---

## API

| Method | Path | Body | Purpose |
|---|---|---|---|
| GET | `/api/state` | – | hospitals (free beds), ambulances (busy?), queue in priority order, zones |
| POST | `/api/emergencies` | `{"lat":19.07,"lng":72.88,"severity":4,"type":"TRAUMA"}` | add a call |
| POST | `/api/emergencies/{id}/escalate` | `{"severity":5}` | raise severity (decreaseKey) |
| POST | `/api/simulate` | `{"count":10,"seed":1}` | add random calls |
| POST | `/api/dispatch` | `{"ambulanceRadiusKm":8,"hospitalRadiusKm":10,"strategy":"MAXFLOW","apply":true}` | run a round (`apply:false` = preview) |
| POST | `/api/compare` | same as dispatch | dry-run MAXFLOW and GREEDY |
| POST | `/api/ambulances/{id}/arrive` | – | ambulance reached the hospital: free it, park it there |
| POST | `/api/forecast` | `{"days":1000,"seed":42,"percentile":90,"callsPerAmbulance":8}` | Monte Carlo staffing plan |
| GET | `/api/benchmark` | – | run all benchmarks |
| POST | `/api/scenario` | scenario JSON | replace city data |
| POST | `/api/reset` | – | reload sample data |

Errors return HTTP 400/404 with `{"error": "..."}`, for example `Emergency location (28.6, 77.2) is outside the Mumbai service area`.

```bash
curl -X POST localhost:8080/api/dispatch -H "Content-Type: application/json" -d '{"strategy":"MAXFLOW","apply":false}'
```

### Scenario JSON (upload format)

See `backend/src/main/resources/data/mumbai.json`:

```json
{
  "hospitals":   [{ "id": "H01", "name": "KEM Hospital, Parel", "lat": 19.0029, "lng": 72.8423, "freeBeds": 4, "specialities": ["GENERAL","TRAUMA"] }],
  "ambulances":  [{ "id": "AMB-01", "lat": 18.93, "lng": 72.83 }],
  "zones":       [{ "name": "Dadar / Parel", "lat": 19.015, "lng": 72.845, "callsPerDay": 30 }],
  "emergencies": [{ "lat": 19.005, "lng": 72.84, "severity": 5, "type": "CARDIAC" }]
}
```

Validation rejects: no hospitals or ambulances, duplicate ids, coordinates outside the Mumbai box
(lat 18.88–19.32, lng 72.76–73.02), negative beds, severity outside 1–5, unknown types, radius outside (0, 50] km.

---

## Assumptions & limitations

* **Synthetic data.** Hospital names and coordinates are approximate; bed counts, specialities, ambulance positions
  and zone call rates are invented for the project.
* **Road distances.** The Java backend (`service/RoadRouter.java`) asks the public OSRM router (OpenStreetMap road
  network) for a road-distance table of every ambulance→patient and patient→hospital pair a dispatch could use, and
  caches it. Max-flow edges, range checks, the local search and ETAs (road km at 30 km/h) all use those road km. Pairs
  already out of range by air are skipped, since a road is never shorter than a straight line. The browser draws each
  trip along the OSRM road route. Network time is excluded from the reported algorithm runtime.
* If OSRM can't be reached (no internet), dispatch falls back to straight-line distance, the result says so
  ("Road router unreachable"), and the router is retried after a minute. OSRM's demo server has fair-use limits and no
  live traffic data.
* Max-flow maximises the **number** of calls served per tier. The local-search pass shortens trips but does **not**
  guarantee minimum total travel time (that would need min-cost flow, outside the syllabus).
* State is in memory: restarting the backend resets to the sample city. Trip timing is driven by the browser
  animation; if nobody has the page open, an ambulance stays on its mission until the page reopens and finishes it.
* The Poisson model assumes calls in a zone are independent with a constant daily rate. Real demand has peaks
  (monsoon, festivals) that this does not model.

---

## Project layout

```
backend/   Spring Boot 4 · Java 21
  algo/      FibonacciHeap, MaxFlow, RandomizedSelect, IndexedBinaryHeap (baseline), Geo
  service/   DispatchService, ForecastService, BenchmarkService
  api/       ApiController, ApiExceptionHandler
  resources/data/mumbai.json   sample city
frontend/  React 18 + Vite + Leaflet
```

## Suggested team split (one algorithm each for the viva)

| Member | Owns | Must be able to explain |
|---|---|---|
| A | `FibonacciHeap` + queue/escalation API | decreaseKey with cascading cuts, amortized analysis, why benchmarks favour the binary heap |
| B | `MaxFlow` + `DispatchService` | flow network, node splitting, integrality, augmenting paths, min cut, why greedy fails |
| C | `RandomizedSelect` + `ForecastService` + benchmarks | expected O(n) proof, Poisson sampling, percentile vs mean staffing |
