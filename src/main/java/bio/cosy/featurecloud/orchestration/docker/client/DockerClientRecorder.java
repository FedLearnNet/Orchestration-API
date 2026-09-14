package bio.cosy.featurecloud.orchestration.docker.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import bio.cosy.featurecloud.orchestration.docker.client.config.DockerClientRuntimeConfig;
import bio.cosy.featurecloud.orchestration.docker.client.config.DockerRuntimeConfig;
import com.github.dockerjava.api.DockerClient;

import io.quarkus.runtime.annotations.Recorder;

/**
 * Runtime recorder for Docker client initialization and management.
 * This recorder is responsible for creating and managing Docker client instances
 * during the Quarkus application startup phase.
 *
 * <p>
 * The recorder handles:
 * </p>
 * <ul>
 * <li>Initialization of both default and named Docker clients</li>
 * <li>Client configuration management</li>
 * <li>Client instance caching</li>
 * <li>Bean creation for dependency injection</li>
 * </ul>
 *
 * <p>
 * Example configuration in application.properties:
 * </p>
 *
 * <pre>
 * # Default client
 * quarkus.docker.docker-host=tcp://localhost:2375
 *
 * # Named client
 * quarkus.docker."production".docker-host=tcp://prod-host:2375
 * quarkus.docker."production".enabled=true
 * </pre>
 *
 * @see DockerClient
 * @see DockerClientFactory
 * @see DockerRuntimeConfig
 */
@Recorder
public class DockerClientRecorder {

    private static final Map<String, DockerClient> clients = Collections.synchronizedMap(new HashMap<>());
    private final DockerRuntimeConfig config;

    public DockerClientRecorder(DockerRuntimeConfig config) {
        this.config = config;
    }

    /**
     * Initializes Docker clients for the given set of names.
     * This method is called during application startup to create and cache
     * Docker client instances.
     *
     * @param names Set of client names to initialize
     * @throws IllegalStateException if configuration is missing for any client
     * @throws IllegalArgumentException if names is null
     */
    public void initialize(Set<String> names) {
        if (names == null) {
            throw new IllegalArgumentException("Client names set cannot be null");
        }

        for (String name : names) {
            initializeClient(name);
        }
    }

    /**
     * Initializes a single Docker client instance.
     *
     * @param name The client name to initialize
     * @throws IllegalStateException if configuration is missing for the client
     */
    private void initializeClient(String name) {
        DockerClientRuntimeConfig clientConfig = getConfigForName(config, name)
                .orElseThrow(() -> new IllegalStateException(
                        String.format("No configuration found for Docker client: %s", name)));

        // Only initialize if enabled
        if (clientConfig.enabled()) {
            clients.computeIfAbsent(name, k -> createDockerClient(clientConfig));
        }
    }

    /**
     * Creates a new Docker client instance with the given configuration.
     *
     * @param clientConfig The client configuration to use
     * @return A new DockerClient instance
     */
    private DockerClient createDockerClient(DockerClientRuntimeConfig clientConfig) {
        return new DockerClientFactory(clientConfig).createClient();
    }

    /**
     * Retrieves the configuration for a given client name.
     * Handles both default and named client configurations.
     *
     * @param cfg The Docker runtime configuration
     * @param name The client name
     * @return Optional containing the client configuration if found
     */
    static Optional<DockerClientRuntimeConfig> getConfigForName(DockerRuntimeConfig cfg, String name) {
        if (DockerRuntimeConfig.isDefaultClient(name)) {
            return Optional.ofNullable(cfg.defaultDockerClient());
        }

        return cfg.namedDockerClients().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /**
     * Creates a supplier for the default Docker client bean.
     * This supplier is used by the CDI container for dependency injection.
     *
     * @return Supplier that provides the default Docker client instance
     */
    public Supplier<DockerClient> createDockerClientBean() {
        return () -> {
            DockerClient client = clients.get(DockerRuntimeConfig.DEFAULT_CLIENT_NAME);
            if (client == null) {
                throw new IllegalStateException("Default Docker client not initialized");
            }
            return client;
        };
    }

    /**
     * Creates a supplier for a named Docker client bean.
     * This supplier is used by the CDI container for dependency injection
     * of named clients.
     *
     * @param clientName The name of the client to create a supplier for
     * @return Supplier that provides the named Docker client instance
     */
    public Supplier<DockerClient> createNamedDockerClientBean(String clientName) {
        return () -> {
            DockerClient client = clients.get(clientName);
            if (client == null) {
                throw new IllegalStateException(String.format("Docker client '%s' not initialized", clientName));
            }
            return client;
        };
    }
}