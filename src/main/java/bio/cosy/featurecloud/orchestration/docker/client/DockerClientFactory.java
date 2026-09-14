package bio.cosy.featurecloud.orchestration.docker.client;

import bio.cosy.featurecloud.orchestration.docker.client.config.DockerClientRuntimeConfig;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.apache.commons.lang3.SystemUtils;

/**
 * Factory for creating Docker client instances with configured settings. This factory handles the creation and configuration of
 * Docker clients, including platform-specific
 * settings and connection parameters.
 *
 * <p>
 * The factory creates clients with:
 * </p>
 * <ul>
 * <li>Platform-specific Docker daemon connections</li>
 * <li>SSL/TLS configuration when required</li>
 * <li>Registry authentication settings</li>
 * <li>Connection timeouts</li>
 * <li>API version configuration</li>
 * </ul>
 *
 * <p>
 * Usage example:
 * </p>
 *
 * <pre>
 * DockerClientRuntimeConfig config = // obtain configuration
 * DockerClientFactory factory = new DockerClientFactory(config);
 * DockerClient client = factory.createClient();
 * </pre>
 *
 * @see DockerClientRuntimeConfig
 * @see DockerClient
 */
public class DockerClientFactory {

    /**
     * Default Docker daemon socket paths for different platforms
     */
    private static final String WINDOWS_DOCKER_HOST = "npipe:////./pipe/docker_engine";
    private static final String UNIX_DOCKER_HOST = "unix:///var/run/docker.sock";

    private final DockerClientRuntimeConfig config;

    public DockerClientFactory(DockerClientRuntimeConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("DockerClientRuntimeConfig cannot be null");
        }
        this.config = config;
    }

    /**
     * Creates and configures a new Docker client instance.
     *
     * @return A configured Docker client instance
     * @throws IllegalStateException if the operating system is not supported or if required configuration is missing
     */
    public DockerClient createClient() {
        DockerClientConfig clientConfig = buildDockerClientConfig();
        DockerHttpClient httpClient = buildDockerHttpClient(clientConfig);
        return DockerClientImpl.getInstance(clientConfig, httpClient);
    }

    /**
     * Builds the Docker client configuration using the provided settings and platform-specific defaults.
     *
     * @return The configured DockerClientConfig
     * @throws IllegalStateException if the operating system is not supported
     */
    private DockerClientConfig buildDockerClientConfig() {
        DefaultDockerClientConfig.Builder configBuilder = DefaultDockerClientConfig.createDefaultConfigBuilder();

        // Configure Docker host with platform-specific defaults
        configureDockerHost(configBuilder);

        // Apply optional configuration settings
        applyOptionalConfiguration(configBuilder);

        return configBuilder.build();
    }

    /**
     * Configures the Docker host connection settings. Uses platform-specific defaults if not explicitly configured.
     *
     * @param builder The configuration builder
     * @throws IllegalStateException if the operating system is not supported
     */
    private void configureDockerHost(DefaultDockerClientConfig.Builder builder) {
        config.dockerHost().ifPresentOrElse(
                builder::withDockerHost,
                () -> {
                    if (SystemUtils.IS_OS_WINDOWS) {
                        builder.withDockerHost(WINDOWS_DOCKER_HOST);
                    } else if (SystemUtils.IS_OS_UNIX) {
                        builder.withDockerHost(UNIX_DOCKER_HOST);
                    } else {
                        throw new IllegalStateException(
                                "Unsupported operating system. Please explicitly configure 'dockerHost'");
                    }
                });
    }

    /**
     * Applies optional configuration settings to the builder if they are present.
     *
     * @param builder The configuration builder
     */
    private void applyOptionalConfiguration(DefaultDockerClientConfig.Builder builder) {
        config.dockerConfig().ifPresent(builder::withDockerConfig);
        config.apiVersion().ifPresent(builder::withApiVersion);
        config.dockerContext().ifPresent(builder::withDockerContext);
        config.dockerCertPath().ifPresent(builder::withDockerCertPath);
        config.dockerTlsVerify().ifPresent(builder::withDockerTlsVerify);

        // Registry configuration
        applyRegistryConfiguration(builder);
    }

    /**
     * Applies registry-specific configuration settings if present.
     *
     * @param builder The configuration builder
     */
    private void applyRegistryConfiguration(DefaultDockerClientConfig.Builder builder) {
        config.registryEmail().ifPresent(builder::withRegistryEmail);
        config.registryUsername().ifPresent(builder::withRegistryUsername);
        config.registryPassword().ifPresent(builder::withRegistryPassword);
        config.registryUrl().ifPresent(builder::withRegistryUrl);
    }

    /**
     * Builds the Docker HTTP client with configured timeouts and SSL settings.
     *
     * @param dockerConfig The Docker client configuration
     * @return Configured DockerHttpClient instance
     */
    private DockerHttpClient buildDockerHttpClient(DockerClientConfig dockerConfig) {
        return new ApacheDockerHttpClient.Builder()
                .dockerHost(dockerConfig.getDockerHost())
                .sslConfig(dockerConfig.getSSLConfig())
                .connectionTimeout(config.connectTimeout())
                .responseTimeout(config.readTimeout())
                .build();
    }
}
