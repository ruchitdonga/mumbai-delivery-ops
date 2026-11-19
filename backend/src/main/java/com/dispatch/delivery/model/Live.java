package com.dispatch.delivery.model;

import com.dispatch.algo.FibonacciHeap;

import java.time.LocalDateTime;
import java.util.*;

/** Mutable in-memory state of the running city. Only the simulation thread mutates it (under the engine lock). */
public final class Live {
    private Live() {}

    public enum OrderStatus { PLACED, PREPARING, READY, ASSIGNED, PICKED_UP, DELIVERED, CANCELLED }

    public enum RiderStatus { IDLE, TO_STORE, DELIVERING, RETURNING, OFF_SHIFT }

    public enum Scenario { NORMAL, PEAK, MONSOON }

    public static final class Locality {
        public final int id;
        public final String name, area, side, pincode;
        public final double lat, lng, minLat, maxLat, minLng, maxLng;
        public final double weight; // relative demand (population / 300k)
        public final int population; // approximate residents

        public Locality(int id, String name, String area, String side, String pincode, double lat, double lng,
                        double minLat, double maxLat, double minLng, double maxLng, int population) {
            this.id = id; this.name = name; this.area = area; this.side = side; this.pincode = pincode;
            this.lat = lat; this.lng = lng; this.minLat = minLat; this.maxLat = maxLat; this.minLng = minLng; this.maxLng = maxLng;
            this.population = population;
            this.weight = population / 300_000.0;
        }
    }

    public record Product(int id, String sku, String name, String category, double price) {}

    public static final class Store {
        public final int id, localityId;
        public int capacityPerHour;              // admin can raise it (more packers)
        public final String code, name;
        public final double lat, lng;
        public boolean active;
        public final Map<Integer, Integer> stock = new HashMap<>();
        public final ArrayDeque<LocalDateTime> recentOrders = new ArrayDeque<>(); // placements in the last hour
        public double[][] coverage = new double[0][]; // polygon ring [lat, lng]

        public Store(int id, String code, String name, int localityId, double lat, double lng, int capacityPerHour, boolean active) {
            this.id = id; this.code = code; this.name = name; this.localityId = localityId;
            this.lat = lat; this.lng = lng; this.capacityPerHour = capacityPerHour; this.active = active;
        }
    }

    public record Customer(int id, int localityId, double lat, double lng, boolean highRise) {}

    /** A stop on a rider's route: the store (pick up) or a customer (drop off orderId). */
    public record Stop(double lat, double lng, int storeId, long orderId) {}

    public static final class Rider {
        public final int id, capacity;
        public final String code, name;
        public int homeStoreId;
        public double lat, lng;
        public RiderStatus status = RiderStatus.IDLE;
        public final ArrayDeque<Stop> route = new ArrayDeque<>();
        public final List<Long> load = new ArrayList<>(); // orders currently assigned
        public String batchKey;
        public long busyMinutes, idleMinutes; // today, for utilisation
        public java.time.LocalDateTime dwellUntil; // handing over at a door / waiting at the store
        public double legLat = Double.NaN, legLng = Double.NaN;   // target of the current leg
        public double[][] legPath;                                 // road polyline of the current leg
        public int legIdx;                                         // next point on legPath
        public int shapeWait;                                      // ticks spent waiting for the leg's road shape
        public double legScale = 1.0;                              // road km per polyline km (1 on a real road shape)
        public final List<double[]> trail = new ArrayList<>();     // points passed in the last tick, for smooth drawing
        public int deliveriesToday;

        public Rider(int id, String code, String name, int homeStoreId, int capacity, double lat, double lng) {
            this.id = id; this.code = code; this.name = name; this.homeStoreId = homeStoreId; this.capacity = capacity;
            this.lat = lat; this.lng = lng;
        }

        public boolean available() {
            return (status == RiderStatus.IDLE || status == RiderStatus.RETURNING) && load.isEmpty();
        }
    }

    public static final class Order {
        public final long id;
        public final int customerId, localityId, itemCount;
        public int storeId;
        public final double lat, lng, total;
        public final boolean highRise;
        public final List<int[]> items; // [productId, qty]
        public final LocalDateTime placedAt;
        public LocalDateTime promisedAt, prepDoneAt, readyAt, pickedAt, deliveredAt, cancelledAt;
        public String cancelReason;
        public OrderStatus status = OrderStatus.PLACED;
        public final long seq;           // arrival order, FIFO tie-break
        public FibonacciHeap.Node<Order> node;
        public Integer riderId;
        public int escalationMinutes;    // urgency added by SLA escalation / admin override
        public int agingMinutes;         // urgency added for time spent waiting
        public double etaMin = -1;       // predicted minutes to delivery, as of assignment
        public LocalDateTime etaAt;      // predicted delivery time
        public boolean prepAlerted, riskAlerted, breachAlerted;

        public Order(long id, int customerId, int localityId, int storeId, double lat, double lng, double total, int itemCount,
                     boolean highRise, List<int[]> items, LocalDateTime placedAt, LocalDateTime promisedAt, long seq) {
            this.id = id; this.customerId = customerId; this.localityId = localityId; this.storeId = storeId;
            this.lat = lat; this.lng = lng; this.total = total; this.itemCount = itemCount; this.highRise = highRise;
            this.items = items; this.placedAt = placedAt; this.promisedAt = promisedAt; this.seq = seq;
        }

        public boolean waitingForRider() {
            return status == OrderStatus.PREPARING || status == OrderStatus.READY;
        }

        public boolean open() {
            return status != OrderStatus.DELIVERED && status != OrderStatus.CANCELLED;
        }
    }

    /** A road closure or waterlogged patch: circle on the map, active until endsAt. */
    public record Disruption(long id, String type, double lat, double lng, double radiusM, LocalDateTime endsAt, String details) {}
}
