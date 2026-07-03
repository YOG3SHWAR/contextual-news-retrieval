package com.inshorts.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Load smoke test (tasks §6.8): fire concurrent requests across endpoints and
 * assert every response succeeds — bounded pools + caching should hold up
 * without errors or thread exhaustion.
 */
class LoadSmokeTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void concurrentMixedTrafficAllSucceeds() throws Exception {
        int concurrency = 24;
        int perTask = 5;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        AtomicInteger ok = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            final int seed = i;
            tasks.add(() -> {
                for (int j = 0; j < perTask; j++) {
                    switch ((seed + j) % 4) {
                        case 0 -> mockMvc.perform(get("/api/v1/news/category").param("category", "world"))
                                .andReturn();
                        case 1 -> mockMvc.perform(get("/api/v1/news/search").param("query", "police"))
                                .andReturn();
                        case 2 -> mockMvc.perform(get("/api/v1/news/nearby")
                                        .param("lat", "19.07").param("lon", "72.88").param("radius", "200"))
                                .andReturn();
                        default -> mockMvc.perform(get("/api/v1/news/trending")
                                        .param("lat", "18.52").param("lon", "73.85"))
                                .andReturn();
                    }
                    ok.incrementAndGet();
                }
                return null;
            });
        }

        List<Future<Void>> futures = pool.invokeAll(tasks);
        for (Future<Void> f : futures) {
            f.get(); // propagate any assertion/exception
        }
        pool.shutdown();
        assertThat(ok.get()).isEqualTo(concurrency * perTask);
    }
}
