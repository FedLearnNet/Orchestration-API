package bio.cosy.featurecloud.orchestration.docker.client;

import bio.cosy.featurecloud.orchestration.docker.client.config.DockerClientRuntimeConfig;
import bio.cosy.featurecloud.orchestration.docker.client.config.DockerRuntimeConfig;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import io.smallrye.config.SmallRyeConfig;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class DockerClientProducer {

    @Inject
    DockerRuntimeConfig dockerRuntimeConfig;

    @Inject
    SmallRyeConfig smallRyeConfig;

    private volatile DockerClient defaultClient;
    private final Map<String, DockerClient> namedClients = new ConcurrentHashMap<>();

    @Produces
    @Singleton
    public DockerClient defaultDockerClient() {
        if (defaultClient == null) {
            synchronized (this) {
                if (defaultClient == null) {
                    defaultClient = createClient(
                            DockerRuntimeConfig.DEFAULT_CLIENT_NAME,
                            dockerRuntimeConfig.defaultDockerClient()
                    );
                }
            }
        }
        return defaultClient;
    }

    @Produces
    @NamedDockerClient("_internal")
    public DockerClient namedDockerClient(InjectionPoint injectionPoint) {
        if (injectionPoint == null || injectionPoint.getAnnotated() == null) {
            throw new IllegalStateException("Named Docker clients must be injected with @NamedDockerClient");
        }

        NamedDockerClient qualifier = injectionPoint.getAnnotated().getAnnotation(NamedDockerClient.class);
        if (qualifier == null || qualifier.value() == null || qualifier.value().isBlank()) {
            throw new IllegalStateException("Missing @NamedDockerClient value at injection point: " + injectionPoint);
        }

        return getNamedDockerClient(qualifier.value());
    }

    public DockerClient getNamedDockerClient(String clientName) {
        if (clientName == null || clientName.isBlank()) {
            throw new IllegalArgumentException("Docker client name must not be blank");
        }

        return namedClients.computeIfAbsent(clientName, name -> {
            DockerClientRuntimeConfig cfg = dockerRuntimeConfig.namedDockerClients().get(name);
            if (cfg == null) {
                throw new IllegalStateException(
                        "No Docker client configuration found for named client '" + name + "'. " +
                                "Expected configuration under prefix orch.docker.\"" + name + "\".*"
                );
            }
            if (!cfg.enabled()) {
                throw new IllegalStateException(
                        "Docker client '" + name + "' is configured but disabled."
                );
            }
            return createClient(name, cfg);
        });
    }

    private DockerClient createClient(String clientName, DockerClientRuntimeConfig cfg) {
        DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder();

        cfg.dockerHost().filter(s -> !s.isBlank()).ifPresent(builder::withDockerHost);
        cfg.apiVersion().filter(s -> !s.isBlank()).ifPresent(builder::withApiVersion);
        cfg.dockerConfig().filter(s -> !s.isBlank()).ifPresent(builder::withDockerConfig);
        cfg.dockerContext().filter(s -> !s.isBlank()).ifPresent(builder::withDockerContext);
        cfg.dockerCertPath().filter(s -> !s.isBlank()).ifPresent(builder::withDockerCertPath);
        cfg.dockerTlsVerify().ifPresent(v -> builder.withDockerTlsVerify(v ? "1" : "0"));

        cfg.registryUsername().filter(s -> !s.isBlank()).ifPresent(builder::withRegistryUsername);
        cfg.registryPassword().filter(s -> !s.isBlank()).ifPresent(builder::withRegistryPassword);
        cfg.registryEmail().filter(s -> !s.isBlank()).ifPresent(builder::withRegistryEmail);
        cfg.registryUrl().filter(s -> !s.isBlank()).ifPresent(builder::withRegistryUrl);

        DefaultDockerClientConfig dockerConfig = builder.build();

        URI dockerHostUri = URI.create(dockerConfig.getDockerHost().toString());

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(dockerHostUri)
                .sslConfig(dockerConfig.getSSLConfig())
                .maxConnections(100)
                .connectionTimeout(cfg.connectTimeout())
                .responseTimeout(cfg.readTimeout())
                .build();

        return DockerClientImpl.getInstance(dockerConfig, httpClient);
    }

    @PreDestroy
    void shutdown() {
        closeQuietly(defaultClient);
        namedClients.values().forEach(this::closeQuietly);
        namedClients.clear();
    }

    private void closeQuietly(DockerClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception ignored) {
        }
    }
}
