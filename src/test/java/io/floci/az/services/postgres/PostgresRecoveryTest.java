package io.floci.az.services.postgres;

import com.github.dockerjava.api.model.Container;
import io.floci.az.core.docker.ContainerDetector;
import io.floci.az.core.docker.ContainerLifecycleManager;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(PostgresRecoveryTest.RealModeProfile.class)
class PostgresRecoveryTest {

    public static class RealModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "floci-az.services.postgres.mocked", "false",
                "floci-az.services.postgres.startup-timeout-seconds", "1",
                "floci-az.services.docker-network", "recovery-network");
        }
    }

    private static final String NAME = "recovered-pg";
    private static final String CONTAINER_NAME = "floci-az-pg-" + NAME;
    private static final String PATH = "/subscriptions/recovery-sub/resourceGroups/recovery-rg"
        + "/providers/Microsoft.DBforPostgreSQL/flexibleServers/" + NAME
        + "?api-version=2025-08-01";

    @Inject PostgresState state;
    @InjectMock ContainerLifecycleManager containers;
    @InjectMock ContainerDetector detector;

    @BeforeEach
    void reset() {
        state.clear();
        when(detector.isRunningInContainer()).thenReturn(false);
    }

    @AfterEach
    void clearState() {
        state.clear();
    }

    @Test
    void readAdoptsExistingSidecarAndRestoresEndpoint() throws Exception {
        state.putServer(restoredServer());
        state.putDatabase(NAME, PostgresState.DatabaseEntry.create("fiskdirekt", NAME, null, null));
        Container existing = mock(Container.class);
        when(existing.getId()).thenReturn("existing-container-id");
        when(containers.findByName(CONTAINER_NAME)).thenReturn(Optional.of(existing));

        try (ServerSocket listener = new ServerSocket(0)) {
            int port = listener.getLocalPort();
            when(containers.adopt("existing-container-id", List.of(5432), "recovery-network"))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo("existing-container-id",
                    Map.of(5432, new ContainerLifecycleManager.EndpointInfo("localhost", port))));

            given().get(PATH).then().statusCode(200)
                .body("properties.state", equalTo("Ready"))
                .body("properties.localPort", equalTo(port));
            given().get("/devstoreaccount1-postgres/flexibleServers/" + NAME + "/connect")
                .then().statusCode(200)
                .body("host", equalTo("localhost"))
                .body("port", equalTo(port));

            verify(containers).adopt("existing-container-id", List.of(5432), "recovery-network");
            verify(containers, never()).removeIfExists(anyString());
            verify(containers, never()).createAndStart(any());
            assertTrue(state.databaseExists(NAME, "fiskdirekt"));
        }
    }

    @Test
    void missingSidecarAndVolumeDoesNotStartEmptyDatabase() {
        state.putServer(restoredServer());
        when(containers.findByName(CONTAINER_NAME)).thenReturn(Optional.empty());
        when(containers.volumeExists("floci-az-pg-data-" + NAME)).thenReturn(false);

        given().get(PATH).then().statusCode(200)
            .body("properties.state", equalTo("Creating"));
        given().get("/devstoreaccount1-postgres/flexibleServers/" + NAME + "/connect")
            .then().statusCode(500)
            .body("error.code", equalTo("ContainerRecoveryFailed"));
        verify(containers, never()).removeIfExists(anyString());
        verify(containers, never()).createAndStart(any());
    }

    @Test
    void containerModeReportsSidecarNameAndInternalPort() throws Exception {
        state.putServer(restoredServer());
        when(detector.isRunningInContainer()).thenReturn(true);
        Container existing = mock(Container.class);
        when(existing.getId()).thenReturn("existing-container-id");
        when(containers.findByName(CONTAINER_NAME)).thenReturn(Optional.of(existing));

        try (ServerSocket listener = new ServerSocket(0)) {
            when(containers.adopt("existing-container-id", List.of(5432), "recovery-network"))
                .thenReturn(new ContainerLifecycleManager.ContainerInfo("existing-container-id",
                    Map.of(5432, new ContainerLifecycleManager.EndpointInfo("localhost", listener.getLocalPort()))));

            given().get(PATH).then().statusCode(200)
                .body("properties.fullyQualifiedDomainName", equalTo(CONTAINER_NAME))
                .body("properties.localPort", equalTo(5432));
            given().get("/devstoreaccount1-postgres/flexibleServers/" + NAME + "/connect")
                .then().statusCode(200)
                .body("host", equalTo(CONTAINER_NAME))
                .body("port", equalTo(5432));
        }
    }

    private static PostgresState.ServerEntry restoredServer() {
        return new PostgresState.ServerEntry(
            NAME, "recovery-sub", "recovery-rg", "eastus", "17", "pgadmin", "password",
            "Standard_B1ms", "Burstable", 32, null, 0, "localhost", Map.of(),
            new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), Instant.now());
    }
}
