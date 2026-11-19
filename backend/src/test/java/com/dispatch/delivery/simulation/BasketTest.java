package com.dispatch.delivery.simulation;

import com.dispatch.delivery.model.Live.Product;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BasketTest {

    @Test
    void essentialsAreBoughtAboutThreeTimesAsOftenAndNeverTwiceInOneBasket() {
        List<Product> products = List.of(new Product(1, "MLK", "Milk", "Dairy", 66), new Product(2, "BRD", "Bread", "Bakery", 45),
                new Product(3, "DET", "Detergent", "Household", 130), new Product(4, "CHP", "Chips", "Snacks", 20));
        Random r = new Random(7);
        Map<Integer, Integer> hits = new HashMap<>();
        for (int i = 0; i < 20_000; i++) {
            List<Product> pick = SimulationEngine.pickProducts(products, 1, r);
            hits.merge(pick.get(0).id(), 1, Integer::sum);
        }
        double ratio = (hits.get(1) + hits.get(2)) / (double) (hits.get(3) + hits.get(4));
        assertEquals(3.0, ratio, 0.25, "dairy/bakery vs the rest");

        List<Product> basket = SimulationEngine.pickProducts(products, 4, r);
        assertEquals(4, new HashSet<>(basket).size(), "no product twice");
        assertEquals(4, SimulationEngine.pickProducts(products, 9, r).size(), "never more than exist");
    }
}
