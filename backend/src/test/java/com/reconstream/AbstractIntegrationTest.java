package com.reconstream;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("reconstream")
                    .withUsername("reconstream")
                    .withPassword("password");

    @ServiceConnection
    protected static final RedpandaContainer REDPANDA =
            new RedpandaContainer(DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v24.1.2"));

    static {
        POSTGRES.start();
        REDPANDA.start();
    }
}
