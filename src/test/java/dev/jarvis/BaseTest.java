package dev.jarvis;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots the whole application on a real PostgreSQL with pgvector (same image as compose.yaml).
 * One container for the whole test run, so every subclass shares the same cached Spring context.
 */
// Settings from the developer's database must not leak into tests
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "jarvis.settings.enabled=false")
public abstract class BaseTest {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start(); // stopped by Testcontainers when the JVM exits
    }
}
