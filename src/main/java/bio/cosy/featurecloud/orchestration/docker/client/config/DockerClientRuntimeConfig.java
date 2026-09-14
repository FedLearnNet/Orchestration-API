package bio.cosy.featurecloud.orchestration.docker.client.config;

import java.time.Duration;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigGroup;
import io.smallrye.config.WithDefault;

/**
 * Configuration interface for Docker client runtime settings. This interface defines all the configuration properties available
 * for a Docker client instance.
 *
 * <p>
 * Configuration can be specified in application.properties using the following format:
 * </p>
 *
 * <pre>
 * # Default client configuration
 * quarkus.docker.docker-host=tcp://localhost:2375
 * quarkus.docker.connect-timeout=10s
 *
 * # Named client configuration
 * quarkus.docker.client-name.docker-host=tcp://other-host:2375
 * quarkus.docker.client-name.connect-timeout=5s
 * </pre>
 */
@ConfigGroup
public interface DockerClientRuntimeConfig {

    /**
     * Determines whether this Docker client configuration is enabled.
     *
     * <p>
     * For the clients, this is true by default.
     * </p>
     *
     * <p>
     * Configuration property: {@code quarkus.docker.[client-name.]enabled}
     * </p>
     *
     * @return boolean indicating if this client configuration is enabled
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Specifies the connection timeout when connecting to the Docker daemon.
     *
     * <p>
     * This timeout applies to the initial connection establishment.
     * If the connection cannot be established within this time, a timeout exception will be thrown.
     * </p>
     *
     * <p>
     * Configuration property: {@code quarkus.docker.[client-name.]connect-timeout}
     * </p>
     *
     * @return The connection timeout duration (default: 10 seconds)
     */
    @WithDefault("10s")
    Duration connectTimeout();

    /**
     * Specifies the read timeout for Docker API operations.
     *
     * <p>
     * This timeout applies to individual API operations after the connection
     * has been established. If an operation takes longer than this timeout, it will be interrupted.
     * </p>
     *
     * <p>
     * Configuration property: {@code quarkus.docker.[client-name.]read-timeout}
     * </p>
     *
     * @return The read timeout duration (default: 30 seconds)
     */
    @WithDefault("30s")
    Duration readTimeout();

    /**
     * Specifies the Docker daemon host URL.
     *
     * <p>
     * Supported formats include:
     * </p>
     * <ul>
     * <li>tcp://host:port</li>
     * <li>unix:///path/to/socket</li>
     * <li>npipe:////./pipe/docker_engine (Windows)</li>
     * </ul>
     *
     * <p>
     * If not specified, the default will be platform-specific:
     * unix:///var/run/docker.sock for Unix-like systems and
     * npipe:////./pipe/docker_engine for Windows.
     * </p>
     *
     * <p>
     * Configuration property: {@code quarkus.docker.[client-name.]docker-host}
     * </p>
     *
     * @return Optional Docker host URL
     */
    Optional<String> dockerHost();

    /**
     * Specifies the path to the Docker config file.
     *
     * <p>
     * This file contains registry authentication details and other Docker configuration.
     * If not specified, the default location (~/.docker/config.json) will be used.
     * </p>
     *
     * <p>
     * Configuration property: {@code quarkus.docker.[client-name.]docker-config}
     * </p>
     *
     * @return Optional Docker config file path
     */
    Optional<String> dockerConfig();

    /**
     * Specifies the Docker API version to use.
     *
     * <p>
     * If not specified, the latest supported version will be negotiated
     * with the Docker daemon automatically.
     * </p>
     *
     * <p>
     * Configuration property: {@code quarkus.docker.[client-name.]api-version}
     * </p>
     *
     * @return Optional Docker API version
     */
    Optional<String> apiVersion();

    /**
     * Specifies the Docker context to use.
     *
     * <p>
     * Docker contexts allow switching between different Docker endpoints
     * and their associated authentication settings.
     * </p>
     *
     * <p>
     * Configuration property: {@code quarkus.docker.[client-name.]docker-context}
     * </p>
     *
     * @return Optional Docker context name
     */
    Optional<String> dockerContext();

    /**
     * Specifies the path to the Docker TLS certificates.
     *
     * <p>
     * Required when using TLS authentication with the Docker daemon.
     * The directory should contain ca.pem, cert.pem, and key.pem files.
     * </p>
     *
     * <p>
     * Configuration property: {@code quarkus.docker.[client-name.]docker-cert-path}
     * </p>
     *
     * @return Optional path to Docker TLS certificates
     */
    Optional<String> dockerCertPath();

    /**
     * Determines whether to verify TLS certificates when connecting to the Docker daemon.
     *
     * <p>
     * Should be enabled in production environments when using TLS.
     * </p>
     *
     * <p>
     * Configuration property: {@code quarkus.docker.[client-name.]docker-tls-verify}
     * </p>
     *
     * @return Optional boolean indicating if TLS verification is enabled
     */
    Optional<Boolean> dockerTlsVerify();

    /**
     * Specifies the email address for Docker registry authentication.
     *
     * <p>
     * Configuration property: {@code quarkus.docker.[client-name.]registry-email}
     * </p>
     *
     * @return Optional registry email address
     */
    Optional<String> registryEmail();

    /**
     * Specifies the username for Docker registry authentication.
     *
     * <p>
     * Configuration property: {@code quarkus.docker.[client-name.]registry-username}
     * </p>
     *
     * @return Optional registry username
     */
    Optional<String> registryUsername();

    /**
     * Specifies the password for Docker registry authentication.
     *
     * <p>
     * Configuration property: {@code quarkus.docker.[client-name.]registry-password}
     * </p>
     *
     * @return Optional registry password
     */
    Optional<String> registryPassword();

    /**
     * Specifies the Docker registry URL.
     *
     * <p>
     * Configuration property: {@code quarkus.docker.[client-name.]registry-url}
     * </p>
     *
     * @return Optional registry URL
     */
    Optional<String> registryUrl();
}