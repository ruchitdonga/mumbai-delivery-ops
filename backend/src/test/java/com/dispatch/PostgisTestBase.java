package com.dispatch;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts one throwaway PostGIS container (needs Docker) shared by every Spring integration test.
 * The simulation does not auto-run in tests and only 2 days of history are generated, to stay fast.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"sim.autostart=false", "sim.history-days=2", "osrm.url=http://127.0.0.1:1/table/v1/driving/", "roads.overpass-url=", "roads.file=target/no-road-graph.bin"})
public abstract class PostgisTestBase {

    @ServiceConnection
    static final PostgreSQLContainer DB =
            new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    static {
        DB.start();
    }
}
