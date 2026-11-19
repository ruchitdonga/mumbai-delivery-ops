package com.dispatch.delivery.geo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RoadGraphTest {

    /**
     * A "U" road around a creek: 1 -> 2 -> 3 -> 4, where 1 and 4 are close but not directly connected,
     * plus a short cut 1 -> 5 -> 4 that is longer, and an island 8-9 that must be dropped.
     */
    static RoadGraph.Graph sample() {
        Map<Long, double[]> nodes = new HashMap<>();
        nodes.put(1L, new double[]{19.000, 72.800});
        nodes.put(2L, new double[]{19.010, 72.800});
        nodes.put(3L, new double[]{19.010, 72.810});
        nodes.put(4L, new double[]{19.000, 72.810});
        nodes.put(5L, new double[]{18.980, 72.805}); // long way round the south
        nodes.put(8L, new double[]{19.100, 72.900});
        nodes.put(9L, new double[]{19.101, 72.901});
        List<long[]> ways = List.of(new long[]{1, 2, 3, 4}, new long[]{1, 5, 4}, new long[]{8, 9});
        return RoadGraph.build(nodes, ways);
    }

    @Test
    void routesAlongRoadsNotStraightAcrossAndPicksTheShorterWay() {
        RoadGraph.Graph g = sample();
        assertEquals(5, g.lat().length, "island 8-9 is not reachable, so it is dropped");
        int s = RoadGraph.nearest(g, 19.000, 72.800), t = RoadGraph.nearest(g, 19.000, 72.810);
        double[][] path = RoadGraph.astar(g, s, t);
        assertNotNull(path);
        // via the north bend (2.2 km) rather than the south one (4.5 km); never the straight 1 km line across
        assertEquals(4, path.length);
        assertEquals(19.010, path[1][0], 1e-4);
        assertEquals(19.010, path[2][0], 1e-4);
    }

    @Test
    void savedGraphLoadsBackIdentically(@TempDir Path dir) throws Exception {
        RoadGraph.Graph g = sample();
        Path f = dir.resolve("roads.bin");
        RoadGraph.write(g, f);
        RoadGraph.Graph back = RoadGraph.read(f);
        assertArrayEquals(g.lat(), back.lat());
        assertArrayEquals(g.adj(), back.adj());
        assertArrayEquals(g.off(), back.off());
        assertEquals(g.w()[0], back.w()[0], 1e-3);
    }
}
