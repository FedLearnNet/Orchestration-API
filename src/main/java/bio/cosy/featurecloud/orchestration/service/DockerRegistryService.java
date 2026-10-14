package bio.cosy.featurecloud.orchestration.service;

import bio.cosy.featurecloud.orchestration.config.PipelineConfig;
import bio.cosy.featurecloud.orchestration.docker.client.NamedDockerClient;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.UriBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@ApplicationScoped
public class DockerRegistryService {

    @Inject
    @NamedDockerClient("private")
    DockerClient dockerClientPrivate;

    @Inject
    DockerPullService dockerPullService;

    @Inject
    PipelineConfig pipelineConfig;

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
}
