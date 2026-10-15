package bio.cosy.featurecloud.orchestration.service;

import bio.cosy.featurecloud.orchestration.api.orchestration.RegistryImagesDTO;
import bio.cosy.featurecloud.orchestration.api.orchestration.RegistryRepositoryDTO;
import bio.cosy.featurecloud.orchestration.config.PipelineConfig;
import bio.cosy.featurecloud.orchestration.docker.client.NamedDockerClient;
import bio.cosy.featurecloud.orchestration.docker.client.config.DockerRuntimeConfig;
import bio.cosy.featurecloud.orchestration.docker.registry.DockerRegistryRestClient;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.UriBuilder;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

@ApplicationScoped
public class DockerRegistryService {
    @Inject
    @RestClient
    DockerRegistryRestClient registryClient;

    @Inject
    @NamedDockerClient("private")
    DockerClient dockerClientPrivate;

    @Inject
    DockerRuntimeConfig dockerRuntimeConfig;

    @Inject
    DockerPullService dockerPullService;

    @Inject
    PipelineConfig pipelineConfig;

    public RegistryImagesDTO getRegistryImages() {
        String registry = registryName();
        try {
            var repositories = new ArrayList<RegistryRepositoryDTO>();
            var names = registryClient.listAllRepositories();
            for (String name : names) {
                validateRepository(name);
                var tags = registryClient.listAllTags(name);
                repositories.add(new RegistryRepositoryDTO(name, tags));
            }
            return new RegistryImagesDTO(registry, repositories);
        } catch (ProcessingException e) {
            throw new WebApplicationException("Could not read the Docker registry catalogue", e, 502);
        }
    }

    public String resolveToolImage(String slug, String commitHash) {
        if (slug == null || !slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new BadRequestException("Invalid tool slug");
        }
        if (commitHash == null || !commitHash.matches("(?i)(?:[0-9a-f]{40}|[0-9a-f]{64})")) {
            throw new BadRequestException("A full Git commit hash is required");
        }
        String registry = pipelineConfig.dockerRegistry().filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalStateException("Pipeline Docker registry is not configured"))
                .replaceFirst("^https?://", "").replaceAll("/+$", "");
        String group = pipelineConfig.dockerGroup().replaceAll("^/+|/+$", "");
        return registry + "/" + (group.isEmpty() ? "" : group + "/") + slug + ":" + commitHash.toLowerCase(Locale.ROOT);
    }

    public boolean imageExistsInRegistry(String imageName) {
        if (imageName == null || imageName.isBlank()) {
            throw new BadRequestException("Image name must not be blank");
        }
        if (!dockerPullService.isRegistryOf(imageName, "private")) {
            Log.errorf("Docker image %s is not from an allowed registry", imageName);
            throw new ForbiddenException("Docker image registry not allowed: " + imageName);
        }
        if (!(dockerClientPrivate instanceof DockerClientImpl client)) {
            throw new IllegalStateException("Private docker client does not expose its http client");
        }

        String path = UriBuilder.fromPath("/distribution/{name}/json")
                .build(new Object[]{imageName}, false)
                .getRawPath();
        DockerHttpClient.Request request = DockerHttpClient.Request.builder()
                .method(DockerHttpClient.Request.Method.GET)
                .path(path)
                .build();

        try (DockerHttpClient.Response response = client.getHttpClient().execute(request)) {
            int status = response.getStatusCode();
            if (status >= 200 && status < 300) {
                return true;
            }
            if (status == 401 || status == 403 || status == 404) {
                Log.infof("Docker image %s not available in registry (status %d)", imageName, status);
                return false;
            }
            throw new IllegalStateException("Registry lookup for " + imageName + " failed with status " + status
                    + ": " + new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Registry lookup for " + imageName + " failed", e);
        }
    }

    private void validateRepository(String name) {
        if (!name.matches("[a-z0-9]+(?:(?:[._-]+|/)[a-z0-9]+)*")) {
            throw new BadRequestException("Invalid registry repository name");
        }
    }

    private String registryName() {
        var config = dockerRuntimeConfig.namedDockerClients().get("private");
        if (config == null || !config.enabled() || config.registryUrl().isEmpty()) {
            throw new WebApplicationException("Private Docker registry is not configured", 503);
        }
        return config.registryUrl().orElseThrow().replaceFirst("^https?://", "").replaceAll("/+$", "");
    }
}
