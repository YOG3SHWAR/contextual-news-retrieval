package com.inshorts.news;

import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for integration tests: real PostgreSQL/PostGIS + Redis via Testcontainers
 * (design §9).
 *
 * <p>Uses the <b>singleton container</b> pattern: the containers are started once
 * in a static initializer and shared by every test class (never stopped per
 * class), so all same-config test classes reuse a single cached Spring context
 * and a single connection pool — avoiding container churn and pool exhaustion.
 *
 * <p>The LLM is disabled by default so tests run offline and deterministically
 * (heuristic mode); fault-injection tests override the client bean.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES;
    static final RedisContainer REDIS;

    static {
        POSTGRES = new PostgreSQLContainer<>(
                DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("news")
                .withUsername("news")
                .withPassword("news");
        REDIS = new RedisContainer(DockerImageName.parse("redis:7"));
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("news.llm.enabled", () -> "false");
        registry.add("news.trending.simulator.enabled", () -> "true");
    }
}
