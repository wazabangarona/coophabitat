package sn.coophabitat.it;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Base des tests d'intégration : une vraie base PostgreSQL 16 (Testcontainers), migrations Flyway réelles,
 * triggers d'intégrité actifs. Le conteneur est partagé par toutes les classes de test (contexte Spring en cache).
 * Les tables étant en ajout seul, chaque test crée ses propres données avec des identifiants uniques.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    static final Path STORAGE;

    static {
        POSTGRES.start();
        try {
            STORAGE = Files.createTempDirectory("coophabitat-test-docs");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final String MOCK_SECRET = "secret-de-test-du-simulateur";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("coophabitat.environment", () -> "TEST");
        r.add("coophabitat.security.jwt-secret", () -> "secret-jwt-de-test-0123456789-abcdefghij");
        r.add("coophabitat.security.cookie-secure", () -> "false");
        r.add("coophabitat.payment.mock-enabled", () -> "true");
        r.add("coophabitat.payment.mock-webhook-secret", () -> MOCK_SECRET);
        r.add("coophabitat.storage.path", STORAGE::toString);
        r.add("coophabitat.jobs.enabled", () -> "false");
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected Fixtures fx;

    @Autowired
    protected JdbcTemplate jdbc;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }
}
