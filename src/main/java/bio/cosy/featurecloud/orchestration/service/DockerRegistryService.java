package bio.cosy.featurecloud.orchestration.service;

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

@ApplicationScoped
public class DockerRegistryService {

    @Inject
    @NamedDockerClient("private")
    DockerClient dockerClientPrivate;

    @Inject
    DockerPullService dockerPullService;

    public boolean imageExistsInRegistry(String imageName) {
        if (imageName == null || imageName.isBlank()) {
            throw new BadRequestException("Image name must not be blank");
        }
        String registry = DockerPullService.registryOf(imageName);
        if (!dockerPullService.isRegistryOf(registry, "private")) {
            Log.errorf("Docker image %s is not from an allowed registry", imageName);
            throw new ForbiddenException("Docker image registry not allowed: " + registry);
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
                Log.infof("Docker image %s not available in registry %s (status %d)", imageName, registry, status);
                return false;
            }
            throw new IllegalStateException("Registry lookup for " + imageName + " failed with status " + status
                    + ": " + new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Registry lookup for " + imageName + " failed", e);
        }
    }
}
