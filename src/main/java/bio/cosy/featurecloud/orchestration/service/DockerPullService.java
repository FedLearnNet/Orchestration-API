package bio.cosy.featurecloud.orchestration.service;

import bio.cosy.featurecloud.orchestration.docker.client.NamedDockerClient;
import bio.cosy.featurecloud.orchestration.docker.client.config.DockerClientRuntimeConfig;
import bio.cosy.featurecloud.orchestration.docker.client.config.DockerRuntimeConfig;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;

@ApplicationScoped
public class DockerPullService {

    @Inject
    @NamedDockerClient("gitlab")
    DockerClient dockerClientGitlab;

    @Inject
    @NamedDockerClient("private")
    DockerClient dockerClientPrivate;

    @Inject
    @NamedDockerClient("ghcr")
    DockerClient dockerClientGhcr;

    @Inject
    DockerRuntimeConfig dockerRuntimeConfig;


    public void loadApplicationImage(String imageName) throws Exception {
        String registry = registryOf(imageName);
        if (isRegistryOf(registry, "gitlab")) {
            loadApplicationImage(imageName, dockerClientGitlab, "gitlab");
        } else if (isRegistryOf(registry, "private")) {
            loadApplicationImage(imageName, dockerClientPrivate, "private");
        } else {
            Log.errorf("Docker image %s is not from an allowed registry", imageName);
            throw new ForbiddenException("Docker image registry not allowed: " + registry);
        }
    }

    public void loadPipelineImage(String imageName) throws Exception {
        String registry = registryOf(imageName);
        if (!isRegistryOf(registry, "ghcr")) {
            Log.errorf("Pipeline image %s is not from an allowed registry", imageName);
            throw new ForbiddenException("Pipeline image registry not allowed: " + registry);
        }
        loadApplicationImage(imageName, dockerClientGhcr, "ghcr");
    }


    private static String registryOf(String imageName) {
        int slash = imageName.indexOf('/');
        String first = slash > 0 ? imageName.substring(0, slash) : "";
        return first.contains(".") || first.contains(":") || first.equals("localhost") ? first : "docker.io";
    }

    private boolean isRegistryOf(String registry, String clientName) {
        DockerClientRuntimeConfig config = dockerRuntimeConfig.namedDockerClients().get(clientName);
        return config != null && config.enabled() && config.registryUrl()
                .map(url -> url.replaceFirst("^https?://", "").replaceAll("/+$", ""))
                .filter(registry::equals)
                .isPresent();
    }


    private void loadApplicationImage(String imageName, DockerClient client, String config) throws Exception {
        Log.infof("Try Pulling Docker image: %s at %s", imageName, config);
        try {
            client.pullImageCmd(imageName)
                    .exec(new PullImageResultCallback())
                    .awaitCompletion();
            Log.infof("Successfully pulled Docker image: %s", imageName);
        } catch (Exception e) {
            if (isAvailableLocally(imageName, client)) {
                Log.warnf("Failed to pull Docker image %s, using the locally available image: %s", imageName, e.getMessage());
                return;
            }
            Log.errorf("Failed to pull Docker image %s: %s", imageName, e.getMessage());
            if (e instanceof NotFoundException) {
                throw new NotFoundException("Docker image not found: " + imageName, e);
            }
            throw e;
        }
    }

    private boolean isAvailableLocally(String imageName, DockerClient client) {
        try {
            client.inspectImageCmd(imageName).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }
}
