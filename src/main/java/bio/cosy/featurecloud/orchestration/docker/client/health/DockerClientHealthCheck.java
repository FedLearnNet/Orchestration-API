package bio.cosy.featurecloud.orchestration.docker.client.health;

import bio.cosy.featurecloud.orchestration.docker.client.NamedDockerClient;
import bio.cosy.featurecloud.orchestration.docker.client.DockerClientProducer;
import bio.cosy.featurecloud.orchestration.docker.client.config.DockerClientRuntimeConfig;
import bio.cosy.featurecloud.orchestration.docker.client.config.DockerRuntimeConfig;
import com.github.dockerjava.api.DockerClient;
import io.smallrye.mutiny.TimeoutException;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A MicroProfile Health readiness check implementation for Docker clients.
 * This health check verifies the connectivity to Docker daemons for all configured clients.
 *
 * <p>
 * The health check:
 * </p>
 * <ul>
 * <li>Discovers all Docker clients (both default and named) at startup</li>
 * <li>Performs ping operations to verify daemon connectivity</li>
 * <li>Respects configured connection timeouts</li>
 * <li>Reports detailed status for each client</li>
 * </ul>
 *
 * <p>
 * Configuration example:
 * </p>
 *
 * <pre>
 * quarkus.docker.health-check=true
 * quarkus.docker.connect-timeout=5s
 * </pre>
 *
 * @see NamedDockerClient
 * @see DockerRuntimeConfig
 */
@Readiness
@ApplicationScoped
public class DockerClientHealthCheck implements HealthCheck {

    private static final String HEALTH_CHECK_NAME = "Docker daemon connection health check";
    private static final String CLIENT_STATUS_FORMAT = "client [%s]: %s";
    private static final String STATUS_OK = "OK";
    private static final String REASON_KEY = "reason";
    private static final String DEFAULT_CLIENT_DISPLAY_NAME = "default";

    private final Map<String, DockerClient> clients = new HashMap<>();
    private final DockerRuntimeConfig config;
    private final DockerClientProducer dockerClientProducer;

    public DockerClientHealthCheck(DockerRuntimeConfig config, DockerClientProducer dockerClientProducer) {
        if (config == null) {
            throw new IllegalArgumentException("DockerRuntimeConfig cannot be null");
        }
        if (dockerClientProducer == null) {
            throw new IllegalArgumentException("DockerClientProducer cannot be null");
        }
        this.config = config;
        this.dockerClientProducer = dockerClientProducer;
    }

    /**
     * Initializes the health check by discovering all Docker clients.
     * Called automatically after construction.
     */
    @PostConstruct
    protected void init() {
        if (config.defaultDockerClient().enabled()) {
            clients.put(DockerRuntimeConfig.DEFAULT_CLIENT_NAME, dockerClientProducer.defaultDockerClient());
        }

        config.namedDockerClients().entrySet().stream()
                .filter(entry -> entry.getValue().enabled())
                .forEach(entry -> clients.put(entry.getKey(), dockerClientProducer.getNamedDockerClient(entry.getKey())));
    }

    /**
     * Gets the configured timeout for a specific client.
     *
     * @param name The client name
     * @return The configured timeout duration
     * @throws IllegalStateException if the client configuration cannot be found
     */
    private Duration getTimeout(String name) {
        DockerClientRuntimeConfig clientConfig = DockerRuntimeConfig.isDefaultClient(name)
                ? config.defaultDockerClient()
                : Optional.ofNullable(config.namedDockerClients().get(name))
                .orElseThrow(() -> new IllegalStateException("Configuration not found for client: " + name));

        return clientConfig.connectTimeout();
    }

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named(HEALTH_CHECK_NAME).up();

        for (Map.Entry<String, DockerClient> entry : clients.entrySet()) {
            String clientName = entry.getKey();
            DockerClient client = entry.getValue();

            try {
                checkClientHealth(builder, clientName, client);
            } catch (TimeoutException e) {
                return createErrorResponse(builder, clientName, "timeout");
            } catch (Exception e) {
                return createErrorResponse(builder, clientName,
                        e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }

        return builder.build();
    }

    /**
     * Checks the health of a specific Docker client.
     *
     * @param builder    The response builder
     * @param clientName The client name
     * @param client     The Docker client to check
     * @throws TimeoutException if the check times out
     */
    private void checkClientHealth(HealthCheckResponseBuilder builder, String clientName, DockerClient client) {
        Duration timeout = getTimeout(clientName);
        Uni.createFrom()
                .item(() -> client.pingCmd().exec())
                .await()
                .atMost(timeout);

        String displayName = DockerRuntimeConfig.isDefaultClient(clientName) ? DEFAULT_CLIENT_DISPLAY_NAME : clientName;
        builder.up().withData(displayName, String.format(CLIENT_STATUS_FORMAT, displayName, STATUS_OK));
    }

    /**
     * Creates an error response for a failed health check.
     *
     * @param builder      The response builder
     * @param clientName   The client name
     * @param errorMessage The error message
     * @return A completed HealthCheckResponse
     */
    private HealthCheckResponse createErrorResponse(HealthCheckResponseBuilder builder,
                                                    String clientName,
                                                    String errorMessage) {
        return builder.down()
                .withData(REASON_KEY, String.format(CLIENT_STATUS_FORMAT, clientName, errorMessage))
                .build();
    }
}
