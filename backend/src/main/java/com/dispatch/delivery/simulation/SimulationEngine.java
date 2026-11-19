package com.dispatch.delivery.simulation;

import com.dispatch.algo.Geo;
import com.dispatch.delivery.alerts.AlertService;
import com.dispatch.delivery.allocation.RiderAllocator;
import com.dispatch.delivery.dispatch.Batcher;
import com.dispatch.delivery.dispatch.SlaQueue;
import com.dispatch.delivery.geo.CoverageService;
import com.dispatch.delivery.geo.TrafficModel;
import com.dispatch.delivery.model.Live.*;
import com.dispatch.delivery.orders.OrderRepository;
import com.dispatch.delivery.riders.RiderRepository;
import com.dispatch.delivery.warehouses.WarehouseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * The running city. One tick = one simulated minute. Everything here is SIMULATED (customers, orders, riders,
 * traffic, disruptions); only the map, road network (OSRM) and locality names are real-world.
 *
 * Tick: generate demand -> pack orders -> age/escalate the Fibonacci-heap queue -> batch + allocate riders
 * (min-cost max-flow) -> move riders -> random cancellations -> SLA alerts -> persist -> broadcast.
 * All state changes go through this object's monitor, so REST overrides and the tick never interleave.
 */
@Service
public class SimulationEngine {

    private static final Logger log = LoggerFactory.getLogger(SimulationEngine.class);

    /** Orders per minute at the busiest hour profile value of 1.0 (whole city, before multipliers). */
    static final double BASE_RATE = 0.9;
    static final double[] HOUR_PROFILE = {0.10, 0.05, 0.03, 0.03, 0.05, 0.20, 0.50, 0.90, 1.20, 1.10, 1.00, 1.10,
            1.30, 1.20, 1.00, 0.90, 1.00, 1.30, 1.60, 1.80, 1.70, 1.30, 0.80, 0.40};
    static final int MAX_BATCH = 3;

    private final OrderRepository orders;
    private final WarehouseRepository warehouses;
    private final RiderRepository riderRepo;
    private final TrafficModel traffic;
    private final CoverageService coverage;
    private final AlertService alerts;
    private final long seed;
    private final int historyDays;
    private final boolean autostart;

    private volatile long tickMs;
    private volatile boolean running;
    private volatile double demandMultiplier = 1.0;
    private Random rnd;
    private ScheduledExecutorService exec;
    private ScheduledFuture<?> task;
    private final List<Consumer<Map<String, Object>>> listeners = new CopyOnWriteArrayList<>();

    // ---- live state (guarded by this) ----
    private LocalDateTime now;
    private final Map<Integer, Locality> localities = new LinkedHashMap<>();
    private final Map<Integer, Store> stores = new LinkedHashMap<>();
    private final List<Product> products = new ArrayList<>();
    private final Map<Integer, List<Customer>> customersByLocality = new HashMap<>();
    private final Map<Integer, Rider> riders = new LinkedHashMap<>();
    private final Map<Long, Order> open = new LinkedHashMap<>();
    private final SlaQueue queue = new SlaQueue();
    private long seq;
    private volatile boolean ready;
    private final ArrayDeque<double[]> recentDeliveries = new ArrayDeque<>(); // [epochSec, minutes, onTime] for the last hour
    private boolean coverageDirty = true;
    private final List<Long> monsoonDisruptions = new ArrayList<>();
    private final Map<Long, Disruption> disruptionsById = new HashMap<>();

    // pending writes, flushed at the end of each tick
    private final List<Order> newOrders = new ArrayList<>();
    private final Set<Order> changed = new LinkedHashSet<>();
    private final List<Object[]> events = new ArrayList<>(), assignments = new ArrayList<>(), demand = new ArrayList<>(), locations = new ArrayList<>();
    private final Set<String> stockDirty = new HashSet<>();

    // today's counters (sim day)
    private LocalDate day;
    private double revenueToday;
    private int ordersToday, cancelledToday, deliveredToday, onTimeToday;
    private double deliveryMinutesToday;

    public SimulationEngine(OrderRepository orders, WarehouseRepository warehouses, RiderRepository riderRepo,
                            TrafficModel traffic, CoverageService coverage, AlertService alerts,
                            @Value("${sim.tick-ms:1000}") long tickMs, @Value("${sim.seed:42}") long seed,
                            @Value("${sim.history-days:21}") int historyDays, @Value("${sim.autostart:true}") boolean autostart) {
        this.orders = orders;
        this.warehouses = warehouses;
        this.riderRepo = riderRepo;
        this.traffic = traffic;
        this.coverage = coverage;
        this.alerts = alerts;
        this.tickMs = tickMs;
        this.seed = seed;
        this.historyDays = historyDays;
        this.autostart = autostart;
    }

    // ================================================================== startup

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        init();
        exec = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "sim-tick"));
        // Road distances are fetched off the tick thread, so a slow or offline OSRM never stalls the city.
        ScheduledExecutorService roads = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "osrm-fetch");
            t.setDaemon(true);
            return t;
        });
        roads.scheduleWithFixedDelay(() -> {
            try {
                traffic.fetchPending();
                traffic.fetchShapes();
                if (!customersOnRoad && ready) moveCustomersOntoRoads();
            } catch (Exception e) {
                log.warn("Road data fetch failed: {}", e.getMessage());
            }
        }, 2, 1, TimeUnit.SECONDS);
        if (traffic.roadGraph() != null) traffic.roadGraph().loadAsync(); // local road network for rider routes
        if (autostart) setRunning(true);
    }

    public synchronized void init() {
        if (ready) return;
        rnd = new Random(seed);
        localities.clear();
        warehouses.loadLocalities().forEach(l -> localities.put(l.id, l));
        stores.clear();
        warehouses.loadStores().forEach(s -> stores.put(s.id, s));
        warehouses.loadStock(stores);
        products.clear();
        products.addAll(warehouses.loadProducts());
        orders.initIds();
        ensureCustomers();
        ensureRiders();

        LocalDateTime last = orders.lastActivity();
        List<OrderRepository.OpenRow> openRows = orders.loadOpen();
        now = last == null ? LocalDate.now().atTime(8, 0) : last.plusMinutes(1);
        // An empty city restarted at night resumes in the morning; one with orders in flight resumes exactly where it was.
        if (openRows.isEmpty() && now.getHour() < 7) now = now.toLocalDate().atTime(7, 30);
        riderRepo.lastPositions().forEach((id, p) -> {
            Rider r = riders.get(id);
            if (r != null) { r.lat = p[0]; r.lng = p[1]; }
        });

        traffic.preloadShapes(warehouses.loadShapes()); // riders drive on roads straight after a restart
        coverage.recompute(stores.values(), now);
        coverageDirty = false;
        if (!orders.hasOrders() && historyDays > 0) {
            long t0 = System.currentTimeMillis();
            int n = new HistorySeeder(this, orders, rnd).seed(now.toLocalDate().atStartOfDay().minusDays(historyDays), now.toLocalDate().atStartOfDay());
            orders.initIds();
            log.info("Generated {} days of simulated history: {} orders in {} ms", historyDays, n, System.currentTimeMillis() - t0);
        }
        loadTodayCounters();
        restoreOpen(openRows);
        ready = true;
    }

    /** Test hook and admin recovery: drop all in-memory state and rebuild it from the database. */
    public synchronized void reloadFromDatabase() {
        ready = false;
        open.clear();
        riders.clear();
        queue.clear();
        init();
    }

    /** Puts orders that were in flight when the previous run stopped back into the queue or onto their riders. */
    private void restoreOpen(List<OrderRepository.OpenRow> rows) {
        if (rows.isEmpty()) return;
        Map<Long, List<int[]>> items = orders.loadItems(rows.stream().map(OrderRepository.OpenRow::id).toList());
        Map<Integer, List<Order>> bags = new LinkedHashMap<>();
        Map<Integer, String> batchKeys = new HashMap<>();
        List<Order> orphans = new ArrayList<>();
        for (OrderRepository.OpenRow x : rows) {
            Order o = new Order(x.id(), x.customerId(), x.localityId(), x.storeId(), x.lat(), x.lng(), x.total(), x.itemCount(),
                    x.highRise(), items.getOrDefault(x.id(), List.of()), x.placedAt(), x.promisedAt(), ++seq);
            o.status = OrderStatus.valueOf(x.status());
            o.readyAt = x.readyAt();
            o.pickedAt = x.pickedAt();
            if (o.status != OrderStatus.PLACED) o.prepDoneAt = o.readyAt != null ? o.readyAt : now.plusMinutes(2); // packing resumes
            boolean riderBack = x.riderId() != null && riders.containsKey(x.riderId());
            if ((o.status == OrderStatus.ASSIGNED || o.status == OrderStatus.PICKED_UP) && riderBack) {
                o.riderId = x.riderId();
                bags.computeIfAbsent(x.riderId(), k -> new ArrayList<>()).add(o);
                batchKeys.put(x.riderId(), x.batchKey());
            } else if (o.status == OrderStatus.PICKED_UP) {
                orphans.add(o); // the goods left with a rider who is no longer on shift
            } else if (o.status == OrderStatus.ASSIGNED) {
                o.status = o.readyAt != null ? OrderStatus.READY : OrderStatus.PREPARING;
                changed.add(o);
                event(o, "REQUEUED", "rider not on shift after restart");
            }
            open.put(o.id, o);
            if (o.waitingForRider() && o.riderId == null) queue.add(o);
        }
        for (Map.Entry<Integer, List<Order>> e : bags.entrySet()) {
            Rider r = riders.get(e.getKey());
            Store s = stores.get(e.getValue().get(0).storeId);
            boolean toStore = e.getValue().stream().anyMatch(o -> o.pickedAt == null);
            if (toStore) r.route.add(new Stop(s.lat, s.lng, s.id, -1));
            for (Order o : Batcher.sequence(s, e.getValue())) {
                r.route.add(new Stop(o.lat, o.lng, -1, o.id));
                r.load.add(o.id);
            }
            r.status = toStore ? RiderStatus.TO_STORE : RiderStatus.DELIVERING;
            r.batchKey = batchKeys.get(e.getKey());
        }
        for (Order o : orphans) cancel(o, "RIDER_OFF_SHIFT_AT_RESTART");
        flush();
        log.info("Restored {} open orders ({} riders mid-delivery) from the database", rows.size(), bags.size());
    }

    private void ensureCustomers() {
        // Top up per locality, so localities added by a later migration get customers too.
        Set<Integer> have = new HashSet<>();
        List<Customer> existing = orders.loadCustomers();
        existing.forEach(c -> have.add(c.localityId()));
        if (have.size() < localities.size()) {
            List<Object[]> rows = new ArrayList<>();
            int n = existing.size() + 1;
            for (Locality l : localities.values()) {
                if (have.contains(l.id)) continue;
                int count = (int) Math.round(200 * l.weight);
                for (int i = 0; i < count; i++) {
                    double lat = l.minLat + rnd.nextDouble() * (l.maxLat - l.minLat);
                    double lng = l.minLng + rnd.nextDouble() * (l.maxLng - l.minLng);
                    rows.add(new Object[]{"Customer " + (n++), l.id, lat, lng, rnd.nextDouble() < 0.35});
                }
            }
            orders.insertCustomers(rows);
        }
        customersByLocality.clear();
        for (Customer c : orders.loadCustomers()) customersByLocality.computeIfAbsent(c.localityId(), k -> new ArrayList<>()).add(c);
    }

    private volatile boolean customersOnRoad;

    /**
     * Background, one OSRM request (99 customers) per second: customers scattered inside the approximate locality
     * boxes can land in the sea or a creek, so each is moved to its nearest road point once (persisted).
     */
    private void moveCustomersOntoRoads() {
        List<double[]> batch = orders.customersOffRoad(99);
        if (batch.isEmpty()) {
            customersOnRoad = true;
            int shaped = warehouses.shapeLocalitiesToLand();
            reloadCustomers();
            if (shaped > 0) reloadLocalities();
            log.info("All customers are on roads; {} localities reshaped to the land they cover", shaped);
            return;
        }
        List<double[]> snapped = traffic.snapToRoads(batch.stream().map(c -> new double[]{c[1], c[2]}).toList());
        if (snapped == null) return; // OSRM unavailable: try again later
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < batch.size(); i++) rows.add(new Object[]{snapped.get(i)[0], snapped.get(i)[1], (int) batch.get(i)[0]});
        orders.moveCustomers(rows);
    }

    private synchronized void reloadLocalities() {
        warehouses.loadLocalities().forEach(l -> localities.put(l.id, l));
    }

    private synchronized void reloadCustomers() {
        customersByLocality.clear();
        for (Customer c : orders.loadCustomers()) customersByLocality.computeIfAbsent(c.localityId(), k -> new ArrayList<>()).add(c);
    }

    private void ensureRiders() {
        Set<Integer> staffed = riderRepo.homeStores(); // top up stores added later (new migration) with riders
        if (staffed.size() < stores.size()) {
            int n = riderRepo.count() + 1;
            for (Store s : stores.values()) {
                if (staffed.contains(s.id)) continue;
                int count = Math.max(4, s.capacityPerHour / 12); // ~ one rider per 12 orders/hour of store capacity
                for (int i = 0; i < count; i++) {
                    riderRepo.insert(String.format("R-%03d", n), "Rider " + n, s.id, rnd.nextDouble() < 0.6 ? 2 : 3);
                    n++;
                }
            }
        }
        riders.clear();
        riderRepo.loadOnShift().forEach(r -> riders.put(r.id, r));
    }

    /** Today's KPIs come from the database, so a restart mid-day keeps them. */
    private void loadTodayCounters() {
        day = now.toLocalDate();
        double[] c = orders.dayCounters(day);
        revenueToday = c[0];
        ordersToday = (int) c[1];
        cancelledToday = (int) c[2];
        deliveredToday = (int) c[3];
        onTimeToday = (int) c[4];
        deliveryMinutesToday = c[5];
    }

    // ================================================================== control

    public synchronized void setRunning(boolean run) {
        running = run;
        if (exec == null) return;
        if (task != null) task.cancel(false);
        task = run ? exec.scheduleAtFixedRate(this::safeTick, tickMs, tickMs, TimeUnit.MILLISECONDS) : null;
    }

    public synchronized void setTickMs(long ms) {
        if (ms < 100 || ms > 10_000) throw new IllegalArgumentException("tick interval must be 100..10000 ms");
        tickMs = ms;
        if (running) setRunning(true);
    }

    public synchronized void setDemandMultiplier(double m) {
        if (m < 0 || m > 5) throw new IllegalArgumentException("demand multiplier must be 0..5");
        demandMultiplier = m;
    }

    public synchronized void setScenario(Scenario s) {
        if (s == traffic.scenario()) return;
        traffic.setScenario(s);
        for (Long id : monsoonDisruptions) {
            Disruption d = disruptionsById.remove(id);
            if (d != null) traffic.disruptions().remove(d);
        }
        monsoonDisruptions.clear();
        if (s == Scenario.MONSOON) {
            // Known low-lying spots that flood in heavy rain (approximate locations).
            double[][] spots = {{19.1197, 72.8468, 600}, {19.1866, 72.8486, 500}, {19.2290, 72.8570, 400}, {19.1655, 72.8496, 400}};
            String[] names = {"Andheri subway", "Malad subway", "Borivali station east", "Goregaon station"};
            for (int i = 0; i < spots.length; i++) monsoonDisruptions.add(addDisruptionLocked("WATERLOGGING", spots[i][0], spots[i][1], spots[i][2], null, names[i] + " waterlogged (monsoon)"));
        }
        warehouses.insertOpEvent("SCENARIO_CHANGE", null, null, null, "scenario -> " + s, now, null);
        alerts.raise("SCENARIO", "INFO", "Scenario switched to " + s, null, null, null, now);
        coverageDirty = true;
    }

    public synchronized long addDisruption(String type, double lat, double lng, double radiusM, int minutes, String details) {
        if (!type.equals("ROAD_CLOSURE") && !type.equals("WATERLOGGING")) throw new IllegalArgumentException("type must be ROAD_CLOSURE or WATERLOGGING");
        if (radiusM < 50 || radiusM > 3000) throw new IllegalArgumentException("radius must be 50..3000 m");
        if (minutes < 5 || minutes > 24 * 60) throw new IllegalArgumentException("duration must be 5..1440 minutes");
        requireInArea(lat, lng);
        long id = addDisruptionLocked(type, lat, lng, radiusM, now.plusMinutes(minutes), details == null || details.isBlank() ? type : details);
        alerts.raise(type, "WARNING", details == null || details.isBlank() ? type.replace('_', ' ') + " reported" : details, null, null, null, now);
        return id;
    }

    private long addDisruptionLocked(String type, double lat, double lng, double radiusM, LocalDateTime ends, String details) {
        long id = warehouses.insertOpEvent(type, lat, lng, radiusM, details, now, ends);
        Disruption d = new Disruption(id, type, lat, lng, radiusM, ends, details);
        traffic.addDisruption(d);
        disruptionsById.put(id, d);
        coverageDirty = true;
        return id;
    }

    public synchronized void setStoreActive(int storeId, boolean active) {
        Store s = stores.get(storeId);
        if (s == null) throw new NoSuchElementException("No dark store " + storeId);
        if (s.active == active) return;
        if (!active && stores.values().stream().filter(x -> x.active).count() <= 1) throw new IllegalArgumentException("At least one dark store must stay active");
        s.active = active;
        warehouses.setActive(storeId, active);
        warehouses.insertOpEvent("WAREHOUSE_TOGGLE", s.lat, s.lng, null, s.code + (active ? " activated" : " deactivated"), now, null);
        alerts.raise("WAREHOUSE", active ? "INFO" : "WARNING", s.name + (active ? " is back online" : " taken offline: new orders re-route"), null, null, storeId, now);
        coverageDirty = true;
    }

    // ================================================================== tick

    private void safeTick() {
        try {
            tick();
        } catch (Exception e) {
            log.error("Simulation tick failed", e);
        }
    }

    public void tick() {
        Map<String, Object> snap;
        synchronized (this) {
            if (!ready) return;
            now = now.plusMinutes(1);
            if (!now.toLocalDate().equals(day)) {
                loadTodayCounters();
                riders.values().forEach(r -> { r.busyMinutes = 0; r.idleMinutes = 0; r.deliveriesToday = 0; });
            }
            traffic.expire(now);
            disruptionsById.values().removeIf(d -> !traffic.disruptions().contains(d));
            if (coverageDirty || now.getMinute() % 15 == 0) {
                coverage.recompute(stores.values(), now);
                coverageDirty = false;
            }
            generateDemand();
            prepare();
            // Persist new orders now: alerts raised later in this tick reference them by foreign key.
            orders.insertOrders(newOrders, traffic.scenario().name());
            newOrders.clear();
            queue.age(open.values().stream().filter(Order::waitingForRider).toList(), now);
            updateEtas();
            slaChecks();
            dispatch();
            moveRiders();
            randomCancellations();
            if ((now.getHour() == 6 || now.getHour() == 15) && now.getMinute() == 0) restock(); // morning and afternoon trucks
            for (Rider r : riders.values()) {
                if (r.load.isEmpty()) r.idleMinutes++; else r.busyMinutes++;
                if (now.getMinute() % 5 == 0) locations.add(RiderRepository.location(r, now));
            }
            flush();
            snap = snapshot(true);
        }
        for (Consumer<Map<String, Object>> l : listeners) l.accept(snap);
    }

    // ---- demand

    double rate(LocalDateTime t, Scenario s) {
        int h = t.getHour();
        boolean weekend = t.getDayOfWeek().getValue() >= 6;
        double p = weekend ? HOUR_PROFILE[Math.max(0, h - 1)] * 1.3 : HOUR_PROFILE[h];
        double sc = switch (s) { case NORMAL -> 1.0; case PEAK -> 1.7; case MONSOON -> 1.25; };
        // BASE_RATE was tuned for the first 10 localities (total weight ~10.8); demand grows with population covered.
        double cityWeight = localities.values().stream().mapToDouble(l -> l.weight).sum() / 10.8;
        return BASE_RATE * p * sc * Math.max(0.1, cityWeight);
    }

    private void generateDemand() {
        double r = rate(now, traffic.scenario()) * demandMultiplier;
        int n = poisson(r, rnd);
        for (int i = 0; i < n; i++) placeOrder();
        for (int i = poisson(r * 1.5, rnd); i > 0; i--) {
            Customer c = randomCustomer();
            Product p = products.get(rnd.nextInt(products.size()));
            demand.add(new Object[]{"SEARCH", c.lat(), c.lng(), c.localityId(), p.id(), p.name().toLowerCase(), OrderRepository.ts(now)});
        }
        for (int i = poisson(r * 0.25, rnd); i > 0; i--) {
            Customer c = randomCustomer();
            demand.add(new Object[]{"ABANDONED_CART", c.lat(), c.lng(), c.localityId(), null, null, OrderRepository.ts(now)});
        }
    }

    Customer randomCustomer() {
        double total = localities.values().stream().mapToDouble(l -> l.weight).sum(), x = rnd.nextDouble() * total;
        for (Locality l : localities.values()) {
            x -= l.weight;
            if (x <= 0) {
                List<Customer> cs = customersByLocality.get(l.id);
                return cs.get(rnd.nextInt(cs.size()));
            }
        }
        List<Customer> cs = customersByLocality.values().iterator().next();
        return cs.get(rnd.nextInt(cs.size()));
    }

    /** Nearest active store (by travel time) whose current coverage polygon contains the point. */
    Store serviceableStore(double lat, double lng) {
        Store best = null;
        double bestMin = Double.MAX_VALUE;
        for (Store s : stores.values()) {
            if (!s.active || !CoverageService.contains(s.coverage, lat, lng)) continue;
            double m = traffic.travelMin(s.lat, s.lng, lat, lng, now);
            if (m < bestMin) { bestMin = m; best = s; }
        }
        return best;
    }

    /** Basket of 1..8 distinct products: [productId, qty, unitPricePaise]. */
    List<int[]> basket(Random r) {
        List<int[]> items = new ArrayList<>();
        for (Product p : pickProducts(products, Math.min(8, 1 + poisson(1.8, r)), r))
            items.add(new int[]{p.id(), 1 + (r.nextDouble() < 0.25 ? 1 : 0), (int) Math.round(p.price() * 100)});
        return items;
    }

    /** k distinct products, weighted by popularity: daily essentials (dairy, bread/eggs, produce) sell ~3x more than the rest. */
    static List<Product> pickProducts(List<Product> products, int k, Random r) {
        List<Product> pool = new ArrayList<>(products), out = new ArrayList<>();
        for (int i = 0; i < k && !pool.isEmpty(); i++) {
            double x = r.nextDouble() * pool.stream().mapToDouble(SimulationEngine::popularity).sum();
            int j = 0;
            while (j < pool.size() - 1 && (x -= popularity(pool.get(j))) > 0) j++;
            out.add(pool.remove(j));
        }
        return out;
    }

    private void placeOrder() {
        Customer c = randomCustomer();
        Store s = serviceableStore(c.lat(), c.lng());
        if (s == null) {
            demand.add(new Object[]{"UNSERVICEABLE", c.lat(), c.lng(), c.localityId(), null, null, OrderRepository.ts(now)});
            return;
        }
        List<int[]> items = new ArrayList<>();
        for (int[] it : basket(rnd)) {
            int have = s.stock.getOrDefault(it[0], 0);
            if (have >= it[1]) {
                s.stock.put(it[0], have - it[1]);
                stockDirty.add(s.id + ":" + it[0]);
                items.add(it);
            } else {
                demand.add(new Object[]{"OUT_OF_STOCK", c.lat(), c.lng(), c.localityId(), it[0], null, OrderRepository.ts(now)});
            }
        }
        if (items.isEmpty()) {
            demand.add(new Object[]{"ABANDONED_CART", c.lat(), c.lng(), c.localityId(), null, "everything out of stock", OrderRepository.ts(now)});
            return;
        }
        int count = items.stream().mapToInt(it -> it[1]).sum();
        double total = items.stream().mapToDouble(it -> it[1] * it[2] / 100.0).sum();
        int promise = (count <= 4 ? 20 : 30) + (traffic.scenario() == Scenario.MONSOON ? 10 : 0);
        Order o = new Order(orders.newOrderId(), c.id(), c.localityId(), s.id, c.lat(), c.lng(), Math.round(total * 100) / 100.0,
                count, c.highRise(), items, now, now.plusMinutes(promise), ++seq);
        open.put(o.id, o);
        newOrders.add(o);
        traffic.prefetchShape(s.lat, s.lng, o.lat, o.lng); // road shape ready by the time a rider leaves the store
        s.recentOrders.addLast(now);
        ordersToday++;
        revenueToday += o.total;
    }

    // ---- packing at the dark store

    private void prepare() {
        for (Store s : stores.values()) while (!s.recentOrders.isEmpty() && s.recentOrders.peekFirst().isBefore(now.minusMinutes(60))) s.recentOrders.pollFirst();
        Map<Integer, Long> packing = new HashMap<>();
        for (Order o : open.values()) if (o.status == OrderStatus.PREPARING) packing.merge(o.storeId, 1L, Long::sum);
        for (Order o : open.values()) {
            if (o.status == OrderStatus.PLACED) {
                Store s = stores.get(o.storeId);
                long slots = Math.max(2, (long) Math.ceil(s.capacityPerHour / 12.0));
                if (packing.getOrDefault(s.id, 0L) < slots) {
                    packing.merge(s.id, 1L, Long::sum);
                    double minutes = 3 + rnd.nextDouble() * 3 + o.itemCount * 0.2 + (traffic.scenario() == Scenario.PEAK ? 1.5 : 0);
                    o.prepDoneAt = now.plusSeconds((long) (minutes * 60));
                    o.status = OrderStatus.PREPARING;
                    changed.add(o);
                    event(o, "PREPARING", s.code);
                    queue.add(o); // can be pre-assigned while it is being packed
                }
            }
            if ((o.status == OrderStatus.PREPARING || o.status == OrderStatus.ASSIGNED) && o.readyAt == null
                    && o.prepDoneAt != null && !o.prepDoneAt.isAfter(now)) {
                o.readyAt = o.prepDoneAt;
                if (o.status == OrderStatus.PREPARING) o.status = OrderStatus.READY;
                changed.add(o);
                event(o, "READY", null);
            }
        }
    }

    // ---- SLA

    /** Re-predicts every assigned order's delivery time from where its rider is now, along the remaining route. */
    private void updateEtas() {
        for (Rider r : riders.values()) {
            if (r.load.isEmpty()) continue;
            double t = r.dwellUntil != null && r.dwellUntil.isAfter(now) ? Duration.between(now, r.dwellUntil).toSeconds() / 60.0 : 0;
            double lat = r.lat, lng = r.lng;
            for (Stop st : r.route) {
                t += traffic.travelMin(lat, lng, st.lat(), st.lng(), now);
                if (st.storeId() >= 0) { // waits for the slowest order in the bag to be packed
                    for (Long id : r.load) {
                        Order o = open.get(id);
                        if (o != null && o.readyAt == null && o.prepDoneAt != null) t = Math.max(t, Duration.between(now, o.prepDoneAt).toSeconds() / 60.0);
                    }
                } else {
                    Order o = open.get(st.orderId());
                    if (o != null) {
                        t += traffic.dropMin(o.highRise);
                        o.etaAt = now.plusSeconds((long) (t * 60));
                    }
                }
                lat = st.lat();
                lng = st.lng();
            }
        }
    }

    private void slaChecks() {
        for (Order o : open.values()) {
            long sinceMin = Duration.between(o.placedAt, now).toMinutes();
            double leftMin = Duration.between(now, o.promisedAt).toSeconds() / 60.0;
            if (!o.prepAlerted && o.readyAt == null && sinceMin >= 8) {
                o.prepAlerted = true;
                alerts.raise("PREP_DELAY", "WARNING", "Order #" + o.id + " still not packed after " + sinceMin + " min at " + stores.get(o.storeId).code,
                        o.id, null, o.storeId, now);
                if (o.waitingForRider() && o.node != null) queue.escalate(o, 5);
            }
            if (!o.riskAlerted && o.pickedAt == null) {
                // With a rider: trust the rider-specific ETA. Without one: only after 3 minutes unassigned, when
                // remaining packing + pickup + ride + hand-over no longer fits (a fixed buffer flagged 83% false alarms).
                Store s = stores.get(o.storeId);
                double prepLeft = o.readyAt != null ? 0 : o.prepDoneAt != null ? Math.max(0, Duration.between(now, o.prepDoneAt).toSeconds() / 60.0) : 4;
                double need = prepLeft + 2 + traffic.travelMin(s.lat, s.lng, o.lat, o.lng, now) + traffic.dropMin(o.highRise);
                boolean predictedLate = o.etaAt != null ? o.etaAt.isAfter(o.promisedAt) : sinceMin >= 3 && need > leftMin;
                if (predictedLate) {
                    o.riskAlerted = true;
                    alerts.raise("SLA_RISK", "WARNING", "Order #" + o.id + " at risk: " + Math.round(leftMin) + " min left", o.id, o.riderId, o.storeId, now);
                    if (o.waitingForRider() && o.node != null) queue.escalate(o, 10);
                    event(o, "ESCALATED", "SLA risk");
                }
            }
            if (!o.breachAlerted && leftMin < 0) {
                o.breachAlerted = true;
                alerts.raise("SLA_BREACH", "CRITICAL", "Order #" + o.id + " missed its " + Duration.between(o.placedAt, o.promisedAt).toMinutes()
                        + "-min promise (" + o.status + ")", o.id, o.riderId, o.storeId, now);
            }
        }
    }

    // ---- dispatch: Fibonacci heap -> batching -> min-cost max-flow

    private void dispatch() {
        List<Rider> free = riders.values().stream().filter(Rider::available).toList();
        if (free.isEmpty() || queue.size() == 0) return;
        List<Order> urgent = new ArrayList<>();
        // Just-in-time dispatch: hand an order out once the nearest free rider would reach the store about when packing
        // finishes (or the SLA is tight). Earlier just parks riders at the store and leaves batching nothing to combine.
        Map<Integer, Double> nearestRide = new HashMap<>();
        for (Order o : queue.poll(free.size() * MAX_BATCH + 5)) {
            double prepLeft = o.readyAt != null || o.prepDoneAt == null ? 0 : Duration.between(now, o.prepDoneAt).toSeconds() / 60.0;
            double slack = Duration.between(now, o.promisedAt).toSeconds() / 60.0;
            Store st = stores.get(o.storeId);
            double ride = nearestRide.computeIfAbsent(o.storeId, k -> free.stream()
                    .mapToDouble(r -> traffic.travelMin(r.lat, r.lng, st.lat, st.lng, now)).min().orElse(0));
            if (prepLeft <= Math.max(3, ride + 1) || slack <= 12) urgent.add(o); else queue.add(o);
        }
        List<Batcher.Batch> batches = Batcher.build(urgent, stores, traffic, now, MAX_BATCH);
        List<RiderAllocator.Plan> plans = RiderAllocator.allocate(batches, free, traffic, now, RiderAllocator.Algo.MIN_COST);
        Set<Long> assigned = new HashSet<>();
        for (RiderAllocator.Plan p : plans) {
            assign(p.rider(), p.batch().store(), p.batch().drops(), p.batch().key(), p.etaMin(), "MIN_COST_MAX_FLOW", false);
            p.batch().drops().forEach(o -> assigned.add(o.id));
        }
        for (Order o : urgent) if (!assigned.contains(o.id)) queue.add(o); // back in with its key unchanged
    }

    private void assign(Rider r, Store s, List<Order> drops, String batchKey, Map<Long, Double> eta, String algo, boolean manual) {
        r.route.clear();
        r.route.add(new Stop(s.lat, s.lng, s.id, -1));
        for (Order o : drops) {
            r.route.add(new Stop(o.lat, o.lng, -1, o.id));
            r.load.add(o.id);
            o.riderId = r.id;
            o.status = OrderStatus.ASSIGNED;
            o.etaMin = eta.getOrDefault(o.id, -1.0);
            o.etaAt = o.etaMin >= 0 ? now.plusSeconds((long) (o.etaMin * 60)) : null;
            changed.add(o);
            event(o, "ASSIGNED", r.code + (manual ? " (manual)" : "") + (drops.size() > 1 ? " batch " + batchKey : ""));
            assignments.add(new Object[]{o.id, r.id, batchKey, OrderRepository.ts(now), Math.round(o.etaMin * 10) / 10.0, algo, manual});
        }
        r.batchKey = batchKey;
        r.status = RiderStatus.TO_STORE;
        r.dwellUntil = null;
        // Queue the road shape of every leg now, so they are usually ready before the rider gets there.
        double[] prev = {r.lat, r.lng};
        for (Stop st : r.route) {
            traffic.prefetchShape(prev[0], prev[1], st.lat(), st.lng());
            prev = new double[]{st.lat(), st.lng()};
        }
        Store home = stores.get(r.homeStoreId);
        traffic.prefetchShape(prev[0], prev[1], home.lat, home.lng);
    }

    // ---- riders

    /**
     * Riders drive along the OSRM road polyline of each leg, at the local traffic speed. `trail` records exactly
     * which road points they passed this minute, so the map can replay the movement smoothly instead of jumping.
     * If a leg's road shape is not fetched yet, that leg is driven as a straight line at road pace (and queued).
     */
    private void moveRiders() {
        for (Rider r : riders.values()) {
            r.trail.clear();
            r.trail.add(new double[]{r.lat, r.lng});
            if (r.status == RiderStatus.IDLE || r.status == RiderStatus.OFF_SHIFT) continue;
            if (r.dwellUntil != null && r.dwellUntil.isAfter(now)) continue;
            double kmLeft = traffic.speedKmh(r.lat, r.lng, now) / 60.0; // road km this minute
            while (kmLeft > 1e-9) {
                double[] target = r.route.isEmpty() ? homeOf(r) : new double[]{r.route.peekFirst().lat(), r.route.peekFirst().lng()};
                if ((r.legPath == null || target[0] != r.legLat || target[1] != r.legLng) && !startLeg(r, target)) break; // waiting for road shape
                double[] next = r.legPath[r.legIdx];
                double cost = Geo.km(r.lat, r.lng, next[0], next[1]) * r.legScale;
                if (cost > kmLeft) {
                    double f = kmLeft / cost;
                    r.lat += (next[0] - r.lat) * f;
                    r.lng += (next[1] - r.lng) * f;
                    break;
                }
                r.lat = next[0];
                r.lng = next[1];
                kmLeft -= cost;
                if (r.legIdx < r.legPath.length - 1) {
                    r.trail.add(next);
                    r.legIdx++;
                    continue;
                }
                if (!arrive(r)) break; // stopped at this stop (waiting / handing over / done)
            }
            r.trail.add(new double[]{r.lat, r.lng});
        }
    }

    /** Riders always ride real roads: they hold at the start of a leg until its road shape arrives (urgent requests go
     *  first, usually 1 tick). Safety net only: after this many ticks, or while OSRM is unreachable, a straight line at road pace. */
    static final int SHAPE_WAIT_TICKS = 15;

    /** Starts the next leg on its road shape. Returns false if the rider should hold this tick while the shape is fetched. */
    private boolean startLeg(Rider r, double[] target) {
        double[][] shape = traffic.roadShape(r.lat, r.lng, target[0], target[1]);
        boolean shortHop = Geo.km(r.lat, r.lng, target[0], target[1]) < 0.15;
        if (shape == null && !shortHop && traffic.shapesAvailable() && r.shapeWait < SHAPE_WAIT_TICKS) {
            r.shapeWait++;
            traffic.urgentShape(r.lat, r.lng, target[0], target[1]);
            r.legPath = null;
            return false;
        }
        r.shapeWait = 0;
        r.legLat = target[0];
        r.legLng = target[1];
        r.legPath = shape;
        r.legScale = 1.0; // polyline length is the road length
        if (r.legPath == null) {
            r.legPath = new double[][]{{r.lat, r.lng}, target};
            r.legScale = traffic.detour(r.lat, r.lng, target[0], target[1]);
        }
        r.legIdx = 1;
        return true;
    }

    private double[] homeOf(Rider r) {
        Store s = stores.get(r.homeStoreId);
        return new double[]{s.lat, s.lng};
    }

    /** Handles reaching the current target. Returns true if the rider can keep moving this minute. */
    private boolean arrive(Rider r) {
        if (r.route.isEmpty()) { // reached home store
            r.status = RiderStatus.IDLE;
            return false;
        }
        Stop stop = r.route.peekFirst();
        if (stop.storeId() >= 0) {
            boolean allPacked = r.load.stream().map(open::get).allMatch(o -> o != null && o.readyAt != null);
            if (!allPacked) return false; // wait at the store
            r.route.pollFirst();
            for (Long id : r.load) {
                Order o = open.get(id);
                o.status = OrderStatus.PICKED_UP;
                o.pickedAt = now;
                changed.add(o);
                event(o, "PICKED_UP", r.code);
            }
            r.status = RiderStatus.DELIVERING;
            return true;
        }
        r.route.pollFirst();
        Order o = open.get(stop.orderId());
        if (o != null && o.status == OrderStatus.PICKED_UP) {
            o.status = OrderStatus.DELIVERED;
            o.deliveredAt = now;
            changed.add(o);
            boolean onTime = !now.isAfter(o.promisedAt);
            event(o, "DELIVERED", onTime ? "on time" : "late by " + Duration.between(o.promisedAt, now).toMinutes() + " min");
            deliveredToday++;
            if (onTime) onTimeToday++;
            deliveryMinutesToday += Duration.between(o.placedAt, now).toMinutes();
            recentDeliveries.addLast(new double[]{now.toEpochSecond(java.time.ZoneOffset.UTC), Duration.between(o.placedAt, now).toMinutes(), onTime ? 1 : 0});
            r.deliveriesToday++;
            open.remove(o.id);
            r.dwellUntil = now.plusSeconds((long) (traffic.dropMin(o.highRise) * 60));
        }
        r.load.remove(stop.orderId());
        if (r.route.isEmpty()) {
            r.status = RiderStatus.RETURNING;
            r.batchKey = null;
        }
        return false;
    }

    // ---- cancellations

    private void randomCancellations() {
        for (Order o : new ArrayList<>(open.values())) {
            if (o.pickedAt != null) continue;
            double p = now.isAfter(o.promisedAt) ? 0.012 : 0.0015;
            if (rnd.nextDouble() < p) cancel(o, "CUSTOMER_CANCELLED");
        }
    }

    private void cancel(Order o, String reason) {
        queue.remove(o);
        if (o.riderId != null) {
            Rider r = riders.get(o.riderId);
            if (r != null) {
                r.load.remove(o.id);
                r.route.removeIf(st -> st.orderId() == o.id);
                if (r.load.isEmpty()) {
                    r.route.clear();
                    r.status = RiderStatus.RETURNING;
                    r.batchKey = null;
                }
            }
        }
        Store s = stores.get(o.storeId);
        for (int[] it : o.items) { // goods go back on the shelf
            s.stock.merge(it[0], it[1], Integer::sum);
            stockDirty.add(s.id + ":" + it[0]);
        }
        o.status = OrderStatus.CANCELLED;
        o.cancelledAt = now;
        o.cancelReason = reason;
        changed.add(o);
        event(o, "CANCELLED", reason);
        open.remove(o.id);
        cancelledToday++;
        revenueToday -= o.total;
    }

    static double popularity(Product p) {
        return switch (p.category()) { case "Dairy", "Bakery", "Produce" -> 3.0; default -> 1.0; };
    }

    /** Stock each product is refilled to by the morning truck. Busy stores run short of essentials by evening. */
    static final int TRUCK_REFILL_TO = 30;

    /** Two delivery trucks a day (06:00, 15:00): every product back up to TRUCK_REFILL_TO; in between, the admin's Restock button. */
    private void restock() {
        for (Store s : stores.values()) {
            for (Map.Entry<Integer, Integer> e : s.stock.entrySet()) {
                if (e.getValue() < TRUCK_REFILL_TO) {
                    e.setValue(TRUCK_REFILL_TO);
                    stockDirty.add(s.id + ":" + e.getKey());
                }
            }
        }
    }

    // ---- persistence

    private void event(Order o, String type, String details) {
        events.add(new Object[]{o.id, type, OrderRepository.ts(now), details});
    }

    private void flush() {
        try {
            orders.insertOrders(newOrders, traffic.scenario().name());
            orders.updateOrders(changed);
            orders.insertEvents(events);
            orders.insertAssignments(assignments);
            orders.insertDemand(demand);
            warehouses.saveShapes(traffic.drainFreshShapes());
            riderRepo.insertLocations(locations);
            List<Object[]> stock = new ArrayList<>();
            for (String k : stockDirty) {
                String[] p = k.split(":");
                int sid = Integer.parseInt(p[0]), pid = Integer.parseInt(p[1]);
                stock.add(new Object[]{stores.get(sid).stock.get(pid), sid, pid});
            }
            warehouses.saveStock(stock);
        } finally {
            // Always clear: retrying a half-written batch next tick would fail forever on duplicate keys.
            newOrders.clear();
            changed.clear();
            events.clear();
            assignments.clear();
            demand.clear();
            locations.clear();
            stockDirty.clear();
        }
    }

    // ================================================================== admin overrides

    public synchronized void cancelOrder(long id) {
        Order o = open.get(id);
        if (o == null) throw new NoSuchElementException("No open order " + id);
        if (o.pickedAt != null) throw new IllegalArgumentException("Order " + id + " is already out for delivery");
        cancel(o, "ADMIN_CANCELLED");
        warehouses.insertOpEvent("MANUAL_OVERRIDE", o.lat, o.lng, null, "admin cancelled order " + id, now, null);
        flush();
    }

    public synchronized void escalateOrder(long id, int minutes) {
        Order o = open.get(id);
        if (o == null) throw new NoSuchElementException("No open order " + id);
        if (!o.waitingForRider() || o.node == null) throw new IllegalArgumentException("Order " + id + " already has a rider");
        if (minutes < 1 || minutes > 60) throw new IllegalArgumentException("escalation must be 1..60 minutes");
        queue.escalate(o, minutes);
        event(o, "ESCALATED", "admin +" + minutes + " min");
        warehouses.insertOpEvent("MANUAL_OVERRIDE", o.lat, o.lng, null, "admin escalated order " + id + " by " + minutes + " min", now, null);
        flush();
    }

    /** Manual allocation: give one waiting order to a specific free rider (bypasses the optimiser). */
    public synchronized void assignManually(long orderId, int riderId) {
        Order o = open.get(orderId);
        if (o == null) throw new NoSuchElementException("No open order " + orderId);
        if (!o.waitingForRider() || o.riderId != null) throw new IllegalArgumentException("Order " + orderId + " is not waiting for a rider");
        Rider r = riders.get(riderId);
        if (r == null) throw new NoSuchElementException("No on-shift rider " + riderId);
        if (!r.available()) throw new IllegalArgumentException(r.code + " is busy with " + r.load.size() + " order(s)");
        queue.remove(o);
        Store s = stores.get(o.storeId);
        Batcher.Batch b = new Batcher.Batch("M" + o.id, s, List.of(o), Geo.km(s.lat, s.lng, o.lat, o.lng));
        Map<Long, Double> eta = RiderAllocator.evaluate(r, b, traffic, now, new double[1]);
        assign(r, s, List.of(o), b.key(), eta, "MANUAL", true);
        warehouses.insertOpEvent("MANUAL_OVERRIDE", o.lat, o.lng, null, "admin assigned order " + orderId + " to " + r.code, now, null);
        flush();
    }

    /** Rebalancing: move a rider's home base to another dark store. */
    public synchronized void moveRider(int riderId, int storeId) {
        Rider r = riders.get(riderId);
        if (r == null) throw new NoSuchElementException("No on-shift rider " + riderId);
        Store s = stores.get(storeId);
        if (s == null || !s.active) throw new IllegalArgumentException("Target dark store must exist and be active");
        r.homeStoreId = storeId;
        riderRepo.setHome(riderId, storeId);
        if (r.available()) r.status = RiderStatus.RETURNING;
        warehouses.insertOpEvent("MANUAL_OVERRIDE", s.lat, s.lng, null, r.code + " rebalanced to " + s.code, now, null);
    }

    // ================================================================== one-click fixes

    /**
     * Opens one new dark store (the admin picked it): in the given locality, at the given point or the locality's centre.
     * It gets standard stock and `riders` new riders, and starts taking orders on the next tick.
     */
    public synchronized Map<String, Object> openStore(Integer localityId, Double lat, Double lng, int capacity, int newRiders) {
        if (capacity < 20 || capacity > 500) throw new IllegalArgumentException("capacity must be 20..500 orders/h");
        if (newRiders < 0 || newRiders > 20) throw new IllegalArgumentException("riders must be 0..20");
        Site site = site(localityId, lat, lng);
        Locality l = site.locality();
        double sLat = site.lat(), sLng = site.lng();
        String code = site.code(), name = site.name();
        int id = warehouses.insertStore(code, name, l.id, sLat, sLng, capacity);
        Store s = new Store(id, code, name, l.id, sLat, sLng, capacity, true);
        stores.put(id, s);
        warehouses.loadStock(Map.of(id, s));
        hireRiders(id, newRiders);
        coverageDirty = true;
        warehouses.insertOpEvent("WAREHOUSE_TOGGLE", sLat, sLng, null, "Opened " + code + " in " + l.name, now, null);
        alerts.raise("WAREHOUSE", "INFO", "New dark store " + code + " opened in " + l.name + " with " + newRiders + " riders", null, null, id, now);
        return Map.of("id", id, "code", code, "name", name, "lat", sLat, "lng", sLng);
    }

    /** Where a new store would go, and what it would be called. */
    public record Site(Locality locality, double lat, double lng, String code, String name) {}

    /**
     * Resolves a proposed store site: a given point (its locality from PostGIS), or for a locality alone the on-road
     * customer address closest to the middle of that locality's customers, i.e. the heart of its demand, on a road.
     */
    public synchronized Site site(Integer localityId, Double lat, Double lng) {
        if (localityId == null && (lat == null || lng == null)) throw new IllegalArgumentException("Give a localityId or lat/lng");
        if (localityId == null) localityId = warehouses.localityAt(lat, lng);
        if (localityId == null) { // point outside every mapped area: nearest locality centre
            final double la = lat, ln = lng;
            localityId = localities.values().stream().min(Comparator.comparingDouble(x -> Geo.km(la, ln, x.lat, x.lng))).orElseThrow().id;
        }
        Locality l = localities.get(localityId);
        if (l == null) throw new NoSuchElementException("No locality " + localityId);
        double sLat = l.lat, sLng = l.lng;
        if (lat != null && lng != null) { sLat = lat; sLng = lng; }
        else {
            List<Customer> cs = customersByLocality.getOrDefault(l.id, List.of());
            if (!cs.isEmpty()) {
                double mLat = cs.stream().mapToDouble(Customer::lat).average().orElse(l.lat), mLng = cs.stream().mapToDouble(Customer::lng).average().orElse(l.lng);
                Customer c = cs.stream().min(Comparator.comparingDouble(x -> Geo.km(x.lat(), x.lng(), mLat, mLng))).orElseThrow();
                sLat = c.lat(); sLng = c.lng();
            }
        }
        long here = stores.values().stream().filter(x -> x.localityId == l.id).count();
        String letters = l.area.replaceAll("[^A-Za-z]", "").toUpperCase();
        String base = "DS-" + letters.substring(0, Math.min(3, letters.length())) + ("NONE".equals(l.side) ? "" : "-" + l.side.charAt(0));
        String code = base;
        for (int i = 2; warehouses.storeCodeExists(code); i++) code = base + i;
        return new Site(l, sLat, sLng, code, l.name + " Dark Store" + (here > 0 ? " " + (here + 1) : ""));
    }

    /** Hires `count` new riders based at a store. */
    public synchronized int hireRiders(int storeId, int count) {
        if (!stores.containsKey(storeId)) throw new NoSuchElementException("No dark store " + storeId);
        if (count < 0 || count > 20) throw new IllegalArgumentException("count must be 0..20");
        for (int i = 0; i < count; i++) {
            int n = riderRepo.count() + 1;
            Rider r = riderRepo.load(riderRepo.insert(String.format("R-%03d", n), "Rider " + n, storeId, rnd.nextDouble() < 0.6 ? 2 : 3));
            riders.put(r.id, r);
        }
        return count;
    }

    /** Moves up to `count` riders from one store to another, free ones first; riders mid-delivery switch after it. */
    public synchronized int moveRidersBetween(int fromStoreId, int toStoreId, int count) {
        if (count < 1 || count > 20) throw new IllegalArgumentException("count must be 1..20");
        if (fromStoreId == toStoreId) throw new IllegalArgumentException("Pick two different stores");
        List<Rider> pool = riders.values().stream().filter(r -> r.homeStoreId == fromStoreId)
                .sorted(Comparator.comparing((Rider r) -> !r.available())).limit(count).toList();
        pool.forEach(r -> moveRider(r.id, toStoreId));
        return pool.size();
    }

    /** Restocks every product below 40 units back to 120. Returns how many products were topped up. */
    public synchronized int restockStore(int storeId) {
        Store s = stores.get(storeId);
        if (s == null) throw new NoSuchElementException("No dark store " + storeId);
        int n = 0;
        for (Map.Entry<Integer, Integer> e : s.stock.entrySet()) {
            if (e.getValue() < 40) { e.setValue(120); stockDirty.add(s.id + ":" + e.getKey()); n++; }
        }
        return n;
    }

    /** More packers / faster picking: raise (or lower) a store's hourly packing capacity. */
    public synchronized void setCapacity(int storeId, int capacity) {
        Store s = stores.get(storeId);
        if (s == null) throw new NoSuchElementException("No dark store " + storeId);
        if (capacity < 20 || capacity > 500) throw new IllegalArgumentException("capacity must be 20..500 orders/h");
        s.capacityPerHour = capacity;
        warehouses.setCapacity(storeId, capacity);
    }

    /** Point 6: dispatch priority by promised deadline (SLA) or strictly by order received (FIFO). */
    public synchronized void setPriority(SlaQueue.Policy p) {
        queue.setPolicy(p, open.values().stream().filter(o -> o.waitingForRider() && o.riderId == null).toList());
        warehouses.insertOpEvent("MANUAL_OVERRIDE", null, null, null, "Dispatch priority set to " + p, now, null);
    }

    public SlaQueue.Policy priority() { return queue.policy(); }

    /** Deliveries one rider completes per hour (ride out, hand over, ride back), batching included. */
    public static final double DELIVERIES_PER_RIDER_HOUR = 2.2;

    /**
     * Point 5: riders each store needs for its last-hour demand (+1 buffer), and the moves that close the gaps:
     * free riders from stores with a surplus go to the busiest stores first.
     */
    // ---- demand-aware staffing (no add-then-remove flip-flop)

    private Map<Integer, double[]> hourProfile = Map.of(); // store -> average orders per hour of day, last 7 days
    private LocalDate profileDay;

    /** Orders a store should expect in the hour starting `hoursAhead` from now: its usual load at that hour × scenario × demand slider. */
    public synchronized double expectedOrders(int storeId, int hoursAhead) {
        if (!now.toLocalDate().equals(profileDay)) {
            hourProfile = orders.storeHourProfile(now.minusDays(7), now);
            profileDay = now.toLocalDate();
        }
        double[] p = hourProfile.get(storeId);
        if (p == null) return 0;
        double sc = switch (traffic.scenario()) { case NORMAL -> 1.0; case PEAK -> 1.7; case MONSOON -> 1.25; };
        return p[(now.getHour() + hoursAhead) % 24] * sc * demandMultiplier;
    }

    public static int ridersFor(double ordersPerHour) {
        return (int) Math.ceil(ordersPerHour / DELIVERIES_PER_RIDER_HOUR) + 1; // +1 spare
    }

    /** Riders a store needs for the busiest of the next `hours` hours (or the last hour, if busier). */
    public synchronized int ridersNeeded(Store s, int hours) {
        double peak = s.recentOrders.size();
        for (int h = 1; h <= hours; h++) peak = Math.max(peak, expectedOrders(s.id, h));
        return ridersFor(peak);
    }

    /** Fill every store's gap for the next hour: off-shift riders come back first, then new hires. Returns riders added. */
    public synchronized int fillRiderGaps() {
        int added = 0;
        for (Store s : stores.values()) {
            if (!s.active) continue;
            long have = riders.values().stream().filter(r -> r.homeStoreId == s.id).count();
            for (int i = 0; i < ridersFor(Math.max(s.recentOrders.size(), expectedOrders(s.id, 1))) - have; i++) {
                Integer id = riderRepo.reactivateOne();
                if (id != null) {
                    riderRepo.setHome(id, s.id);
                    Rider r = riderRepo.load(id);
                    riders.put(r.id, r);
                } else {
                    hireRiders(s.id, 1);
                }
                added++;
            }
        }
        return added;
    }

    /** Take free riders off shift only where a store has more than it needs for the whole next 3 hours. Returns riders removed. */
    public synchronized int trimRiders() {
        int removed = 0;
        for (Store s : stores.values()) {
            int keep = ridersNeeded(s, 3) + 1;
            List<Rider> here = riders.values().stream().filter(r -> r.homeStoreId == s.id).toList();
            int left = here.size();
            for (Rider r : here) {
                if (left <= keep) break;
                if (!r.available()) continue;
                riders.remove(r.id);
                riderRepo.setOnShift(r.id, false);
                left--;
                removed++;
            }
        }
        return removed;
    }

    public synchronized Map<String, Object> staffingPlan() {
        Map<Integer, Integer> have = new HashMap<>();
        Map<Integer, List<Rider>> free = new HashMap<>();
        for (Rider r : riders.values()) {
            have.merge(r.homeStoreId, 1, Integer::sum);
            if (r.available()) free.computeIfAbsent(r.homeStoreId, k -> new ArrayList<>()).add(r);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<Integer, Integer> gap = new HashMap<>();
        for (Store s : stores.values()) {
            if (!s.active) continue;
            int perHour = s.recentOrders.size();
            double next = expectedOrders(s.id, 1);
            int need = ridersFor(Math.max(perHour, next));
            int h = have.getOrDefault(s.id, 0);
            gap.put(s.id, need - h);
            rows.add(new LinkedHashMap<>(Map.of("storeId", s.id, "code", s.code, "locality", localities.get(s.localityId).name,
                    "ordersLastHour", perHour, "expectedNextHour", Math.round(next * 10) / 10.0, "ridersNeeded", need, "riders", h, "gap", need - h)));
        }
        rows.sort(Comparator.comparingInt(m -> -(int) m.get("ordersLastHour")));
        List<Map<String, Object>> moves = new ArrayList<>();
        List<Store> needy = stores.values().stream().filter(s -> gap.getOrDefault(s.id, 0) > 0)
                .sorted(Comparator.comparingInt(s -> -s.recentOrders.size())).toList();
        for (Store to : needy) {
            for (Store from : stores.values().stream().sorted(Comparator.comparingInt(s -> gap.getOrDefault(s.id, 0))).toList()) {
                List<Rider> spare = free.getOrDefault(from.id, new ArrayList<>());
                while (gap.get(to.id) > 0 && gap.getOrDefault(from.id, 0) < 0 && !spare.isEmpty()) {
                    Rider r = spare.remove(spare.size() - 1);
                    gap.merge(to.id, -1, Integer::sum);
                    gap.merge(from.id, 1, Integer::sum);
                    moves.add(Map.of("riderId", r.id, "rider", r.code, "from", from.code, "to", to.code, "toStoreId", to.id,
                            "reason", to.code + ": " + to.recentOrders.size() + " orders in the last hour"));
                }
            }
        }
        return Map.of("stores", rows, "moves", moves);
    }

    @SuppressWarnings("unchecked")
    public synchronized int applyStaffing() {
        List<Map<String, Object>> moves = (List<Map<String, Object>>) staffingPlan().get("moves");
        for (Map<String, Object> m : moves) moveRider((int) m.get("riderId"), (int) m.get("toStoreId"));
        return moves.size();
    }

    /** Point 7: what is happening in one locality right now (in-memory state; the DB side is in AnalyticsService). */
    public synchronized Map<String, Object> localityLive(int id) {
        Locality l = localities.get(id);
        if (l == null) throw new NoSuchElementException("No locality " + id);
        List<Order> here = open.values().stream().filter(o -> o.localityId == id).toList();
        LocalDateTime soon = now.plusMinutes(5);
        long atRisk = here.stream().filter(o -> o.promisedAt.isBefore(soon) || (o.etaAt != null && o.etaAt.isAfter(o.promisedAt))).count();
        long inside = riders.values().stream().filter(r -> r.lat >= l.minLat && r.lat <= l.maxLat && r.lng >= l.minLng && r.lng <= l.maxLng).count();
        long near = riders.values().stream().filter(r -> r.available() && Geo.km(r.lat, r.lng, l.lat, l.lng) <= 3).count();
        List<String> serving = stores.values().stream().filter(s -> s.active && CoverageService.contains(s.coverage, l.lat, l.lng))
                .map(s -> s.code).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.id);
        m.put("name", l.name);
        m.put("pincode", l.pincode);
        m.put("population", l.population);
        m.put("simTime", now.toString());
        m.put("openOrders", here.size());
        m.put("waitingForRider", here.stream().filter(o -> o.waitingForRider() && o.riderId == null).count());
        m.put("atRisk", atRisk);
        m.put("ridersInside", inside);
        m.put("freeRidersWithin3km", near);
        m.put("servedBy", serving);
        m.put("congestionPct", Math.round(traffic.congestion(l.lat, l.lng, now) * 100));
        m.put("disruptions", traffic.disruptions().stream()
                .filter(d -> d.lat() >= l.minLat - 0.01 && d.lat() <= l.maxLat + 0.01 && d.lng() >= l.minLng - 0.01 && d.lng() <= l.maxLng + 0.01)
                .map(Disruption::details).toList());
        return m;
    }

    /** What-if: change the number of riders on shift (positive adds, negative removes idle ones). */
    public synchronized int changeRiders(int delta) {
        if (delta == 0 || Math.abs(delta) > 50) throw new IllegalArgumentException("delta must be 1..50 in either direction");
        int done = 0;
        if (delta > 0) {
            for (int i = 0; i < delta; i++) {
                Store target = stores.values().stream().filter(s -> s.active)
                        .min(Comparator.comparingDouble(s -> riders.values().stream().filter(r -> r.homeStoreId == s.id).count() / (double) s.capacityPerHour))
                        .orElseThrow();
                Integer id = riderRepo.reactivateOne();
                if (id == null) id = riderRepo.insert(String.format("R-%03d", riderRepo.count() + 1), "Rider " + (riderRepo.count() + 1), target.id, 2);
                riderRepo.setHome(id, target.id);
                Rider r = riderRepo.load(id);
                riders.put(r.id, r);
                done++;
            }
        } else {
            for (Rider r : new ArrayList<>(riders.values())) {
                if (done >= -delta) break;
                if (r.available()) {
                    riders.remove(r.id);
                    riderRepo.setOnShift(r.id, false);
                    done++;
                }
            }
        }
        return done;
    }

    // ================================================================== reads

    public void addListener(Consumer<Map<String, Object>> l) { listeners.add(l); }

    public synchronized LocalDateTime now() { return now; }
    public synchronized Map<Integer, Store> stores() { return stores; }
    public synchronized Map<Integer, Locality> localities() { return localities; }
    public synchronized List<Product> products() { return List.copyOf(products); }
    public synchronized Collection<Rider> riders() { return List.copyOf(riders.values()); }
    public synchronized List<Order> openOrders() { return List.copyOf(open.values()); }
    public Map<Integer, List<Customer>> customersByLocality() { return customersByLocality; }
    public TrafficModel traffic() { return traffic; }
    public boolean running() { return running; }
    public long tickMs() { return tickMs; }
    public double demandMultiplier() { return demandMultiplier; }
    public int queueSize() { return queue.size(); }
    public int staleSkipped() { return queue.staleSkipped(); }

    /**
     * "If we re-planned right now": every order not yet picked up, against every rider who is free or has not
     * picked anything up yet, solved by all three algorithms. Read-only: nothing is reassigned.
     */
    public synchronized List<RiderAllocator.Comparison> compareAllocation() {
        List<Order> waiting = open.values().stream().filter(o -> o.pickedAt == null && o.prepDoneAt != null)
                .sorted(Comparator.comparingLong(queue::key)).toList();
        List<Rider> free = riders.values().stream().filter(r -> r.available()
                || (!r.load.isEmpty() && r.load.stream().map(open::get).allMatch(o -> o != null && o.pickedAt == null))).toList();
        List<Batcher.Batch> batches = Batcher.build(waiting, stores, traffic, now, MAX_BATCH);
        return RiderAllocator.compare(batches, free, traffic, now);
    }

    public synchronized Map<String, Object> kpis() {
        long activeRiders = riders.values().stream().filter(r -> !r.load.isEmpty()).count();
        long available = riders.values().stream().filter(Rider::available).count();
        double util = stores.values().stream().filter(s -> s.active).mapToDouble(s -> s.recentOrders.size() / (double) s.capacityPerHour).average().orElse(0);
        int kept = ordersToday - cancelledToday;
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("revenueToday", Math.round(revenueToday));
        k.put("ordersToday", ordersToday);
        k.put("activeOrders", open.size());
        k.put("activeRiders", activeRiders);
        k.put("availableRiders", available);
        k.put("ridersOnShift", riders.size());
        k.put("aov", kept == 0 ? 0 : Math.round(revenueToday / kept));
        k.put("slaPct", deliveredToday == 0 ? null : Math.round(1000.0 * onTimeToday / deliveredToday) / 10.0);
        k.put("deliveredToday", deliveredToday);
        k.put("avgDeliveryMin", deliveredToday == 0 ? null : Math.round(10 * deliveryMinutesToday / deliveredToday) / 10.0);
        // Last hour too: a bad morning keeps the whole-day averages low long after service has recovered.
        double cutoff = now.minusHours(1).toEpochSecond(java.time.ZoneOffset.UTC);
        while (!recentDeliveries.isEmpty() && recentDeliveries.peekFirst()[0] < cutoff) recentDeliveries.pollFirst();
        int nh = recentDeliveries.size();
        k.put("deliveredLastHour", nh);
        k.put("slaLastHourPct", nh == 0 ? null : Math.round(1000.0 * recentDeliveries.stream().mapToDouble(d -> d[2]).sum() / nh) / 10.0);
        k.put("avgDeliveryLastHourMin", nh == 0 ? null : Math.round(10 * recentDeliveries.stream().mapToDouble(d -> d[1]).sum() / nh) / 10.0);
        k.put("cancelledToday", cancelledToday);
        k.put("warehouseUtilPct", Math.round(util * 1000) / 10.0);
        k.put("queueSize", queue.size());
        return k;
    }

    /** Live state for the dashboard. drainAlerts=true only for the WebSocket broadcast (so each alert is pushed once). */
    public synchronized Map<String, Object> snapshot(boolean drainAlerts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("simTime", now.toString());
        m.put("running", running);
        m.put("scenario", traffic.scenario().name());
        m.put("priority", queue.policy().name());
        m.put("tickMs", tickMs);
        m.put("demandMultiplier", demandMultiplier);
        m.put("roadDistancesPending", traffic.pendingRoads());
        m.put("kpis", kpis());
        List<Map<String, Object>> st = new ArrayList<>();
        Map<Integer, Long> waitingByStore = new HashMap<>();
        for (Order o : open.values()) if (o.waitingForRider() && o.riderId == null) waitingByStore.merge(o.storeId, 1L, Long::sum);
        for (Store s : stores.values()) {
            long low = s.stock.values().stream().filter(q -> q < 12).count();
            st.add(Map.of("id", s.id, "code", s.code, "name", s.name, "lat", s.lat, "lng", s.lng, "active", s.active,
                    "utilPct", Math.round(1000.0 * s.recentOrders.size() / s.capacityPerHour) / 10.0,
                    "waiting", waitingByStore.getOrDefault(s.id, 0L), "lowStock", low,
                    "riders", riders.values().stream().filter(r -> r.homeStoreId == s.id).count()));
        }
        m.put("stores", st);
        List<Map<String, Object>> rs = new ArrayList<>();
        for (Rider r : riders.values()) {
            // Remaining road route: rest of the current leg's polyline, then each later leg (road shape once fetched).
            List<double[]> path = new ArrayList<>();
            path.add(new double[]{r.lat, r.lng});
            if (r.status != RiderStatus.IDLE && r.legPath != null) {
                for (int i = r.legIdx; i < r.legPath.length; i++) path.add(r.legPath[i]);
                double[] prev = {r.legLat, r.legLng};
                boolean first = true;
                for (Stop stop : r.route) {
                    if (first) { first = false; continue; } // that is the current leg's target
                    double[][] s = traffic.roadShape(prev[0], prev[1], stop.lat(), stop.lng());
                    if (s == null) break; // never draw a straight line: the route extends once that road shape is fetched
                    for (int i = 1; i < s.length; i++) path.add(s[i]);
                    prev = new double[]{stop.lat(), stop.lng()};
                }
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", r.id); x.put("code", r.code); x.put("lat", r.lat); x.put("lng", r.lng); x.put("status", r.status.name());
            x.put("load", r.load.size()); x.put("capacity", r.capacity); x.put("homeStoreId", r.homeStoreId); x.put("path", path);
            x.put("deliveriesToday", r.deliveriesToday);
            x.put("trail", List.copyOf(r.trail)); // road points driven in the last tick: the map replays these
            x.put("onRoad", r.legPath == null || r.legScale == 1.0); // false = riding a straight-line estimate (road shape not fetched in time)
            rs.add(x);
        }
        m.put("riders", rs);
        List<Map<String, Object>> os = new ArrayList<>();
        for (Order o : open.values()) {
            double left = Duration.between(now, o.promisedAt).toSeconds() / 60.0;
            String risk = left < 0 ? "BREACHED" : o.riskAlerted ? "AT_RISK" : "OK";
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", o.id); x.put("lat", o.lat); x.put("lng", o.lng); x.put("status", o.status.name());
            x.put("storeId", o.storeId); x.put("riderId", o.riderId); x.put("total", o.total); x.put("items", o.itemCount);
            x.put("minutesLeft", Math.round(left)); x.put("risk", risk); x.put("localityId", o.localityId);
            x.put("etaMin", o.etaAt == null ? null : Math.max(0, Math.round(Duration.between(now, o.etaAt).toSeconds() / 60.0)));
            x.put("queueKey", o.node != null && o.node.inHeap() ? o.node.key() : null);
            os.add(x);
        }
        m.put("orders", os);
        m.put("disruptions", traffic.disruptions());
        List<Map<String, Object>> congestion = new ArrayList<>();
        for (Locality l : localities.values()) {
            congestion.add(Map.of("id", l.id, "name", l.name, "congestion", Math.round(traffic.congestion(l.lat, l.lng, now) * 100) / 100.0));
        }
        m.put("traffic", congestion);
        m.put("newAlerts", drainAlerts ? alerts.drainUnsent() : List.of());
        return m;
    }

    // ================================================================== helpers

    void requireInArea(double lat, double lng) {
        boolean inside = localities.values().stream().anyMatch(l -> lat >= l.minLat - 0.01 && lat <= l.maxLat + 0.01 && lng >= l.minLng - 0.01 && lng <= l.maxLng + 0.01);
        if (!inside) throw new IllegalArgumentException("(" + lat + ", " + lng + ") is outside the simulated service area");
    }

    static int poisson(double lambda, Random r) {
        if (lambda <= 0) return 0;
        double limit = Math.exp(-lambda), p = 1.0;
        int k = 0;
        do { k++; p *= r.nextDouble(); } while (p > limit);
        return k - 1;
    }
}
