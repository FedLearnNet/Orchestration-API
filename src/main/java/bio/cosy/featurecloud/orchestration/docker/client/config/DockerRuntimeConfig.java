package bio.cosy.featurecloud.orchestration.docker.client.config;

import bio.cosy.featurecloud.orchestration.docker.client.NamedDockerClient;
import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithName;
import io.smallrye.config.WithParentName;

import java.util.Map;
import java.util.Optional;

/**
 * Main configuration interface for the Quarkus Docker client extension.
 * This interface defines the structure for both the default Docker client and named Docker client configurations.
 *
 * <p>
 * The configuration is available during both build time and runtime, and uses the prefix "quarkus.docker".
 * </p>
 *
 * <p>
 * <strong>Configuration examples:</strong>
 * </p>
 *
 * <pre>
 * # Default client configuration
 * quarkus.docker.docker-host=tcp://localhost:2375
 * quarkus.docker.connect-timeout=10s
 * quarkus.docker.read-timeout=30s
 *
 * # Health check configuration
 * quarkus.docker.health-check=true
 *
 * # Named client configuration
 * quarkus.docker."production".docker-host=tcp://prod-host:2375
 * quarkus.docker."production".connect-timeout=5s
 * quarkus.docker."production".enabled=true
 *
 * # Multiple named clients
 * quarkus.docker."staging".docker-host=tcp://stage-host:2375
 * quarkus.docker."development".docker-host=unix:///var/run/docker.sock
 * </pre>
 *
 * <p>
 * <strong>Usage with injection:</strong>
 * </p>
 *
 * <pre>
 * // Default client
 * {@literal @}Inject
 * DockerClient dockerClient;
 *
 * // Named client
 * {@literal @}Inject
 * {@literal @}NamedDockerClient("production")
 * DockerClient productionClient;
 * </pre>
 *
 * @see DockerClientRuntimeConfig
 * @see NamedDockerClient
 */
@ConfigMapping(prefix = "orch.docker")
@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
public interface DockerRuntimeConfig {

    /**
     * The root name for Docker configuration properties.
     */
    public static final String DOCKER_CONFIG_ROOT_NAME = "docker";

    /**
     * The identifier used for the default Docker client configuration.
     */
    public static final String DEFAULT_CLIENT_NAME = "<default>";

    /**
     * Configuration for the default Docker client.
     *
     * <p>
     * This configuration is used when no specific client name is specified
     * during injection or when using the default bean.
     * </p>
     *
     * <p>
     * Configuration properties for the default client use the direct prefix
     * "quarkus.docker", for example:
     * </p>
     *
     * <pre>
     * quarkus.docker.docker-host=tcp://localhost:2375
     * quarkus.docker.connect-timeout=10s
     * </pre>
     *
     * @return The default Docker client configuration
     */
    @WithParentName
    DockerClientRuntimeConfig defaultDockerClient();

    /**
     * Controls whether the Docker client health check is enabled.
     *
     * <p>
     * When enabled, a health check will be registered that verifies
     * the connection to the Docker daemon for all configured clients.
     * This is useful for monitoring the availability of Docker services.
     * </p>
     *
     * <p>
     * Configuration property: {@code quarkus.docker.health-check}
     * </p>
     *
     * <p>
     * Example:
     * </p>
     *
     * <pre>
     * quarkus.docker.health-check=true
     * </pre>
     *
     * @return Optional boolean indicating if health check is enabled
     */
    @WithName("health-check")
    Optional<Boolean> enableHealthCheck();

    /**
     * Configuration map for named Docker clients.
     *
     * <p>
     * Each entry in this map represents a separate Docker client configuration
     * that can be referenced using the {@link NamedDockerClient}
     * qualifier.
     * </p>
     *
     * <p>
     * Configuration for named clients uses the format "quarkus.docker.[name]",
     * where [name] is the identifier for the specific client configuration.
     * </p>
     *
     * <p>
     * Example configuration:
     * </p>
     *
     * <pre>
     * quarkus.docker."production".docker-host=tcp://prod-host:2375
     * quarkus.docker."production".connect-timeout=5s
     * quarkus.docker."staging".docker-host=tcp://stage-host:2375
     * </pre>
     *
     * <p>
     * Example usage:
     * </p>
     *
     * <pre>
     * {@literal @}Inject
     * {@literal @}NamedDockerClient("production")
     * DockerClient productionClient;
     * </pre>
     *
     * @return Map of named Docker client configurations
     */
    @WithParentName
    @ConfigDocMapKey("docker-client-name")
    Map<String, DockerClientRuntimeConfig> namedDockerClients();

    /**
     * Determines if the given name represents the default Docker client.
     *
     * @param name The client name to check
     * @return true if the name represents the default client, false otherwise
     */
    static boolean isDefaultClient(String name) {
        return DEFAULT_CLIENT_NAME.equalsIgnoreCase(name);
    }

    /**
     * Constructs a configuration property key for a given client name and property.
     *
     * <p>
     * This method handles the different formats needed for default and named clients:
     * - Default client: "quarkus.docker.[property]"
     * - Named client: "quarkus.docker.[name].[property]"
     * </p>
     *
     * @param name    The client name (use DEFAULT_CLIENT_NAME for default client)
     * @param radical The property name (e.g., "docker-host", "connect-timeout")
     * @return The full configuration property key
     * @throws IllegalArgumentException if name or radical is null
     */
    static String propertyKey(String name, String radical) {
        if (name == null || radical == null) {
            throw new IllegalArgumentException("Name and radical cannot be null");
        }

        String prefix = DEFAULT_CLIENT_NAME.equals(name)
                ? "orch.docker."
                : "orch.docker.\"" + name + "\".";

        return prefix + radical;
    }
}
