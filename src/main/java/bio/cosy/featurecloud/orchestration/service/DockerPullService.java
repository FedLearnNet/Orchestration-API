package bio.cosy.featurecloud.orchestration.service;

import bio.cosy.featurecloud.orchestration.config.ContainerConfig;
import bio.cosy.featurecloud.orchestration.config.PipelineConfig;
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
    @NamedDockerClient("private")
    DockerClient dockerClientPrivate;

    @Inject
    @NamedDockerClient("ghcr")
    DockerClient dockerClientGhcr;

    @Inject
    DockerRuntimeConfig dockerRuntimeConfig;

    @Inject
    PipelineConfig pipelineConfig;

    @Inject
    ContainerConfig containerConfig;

    public void loadApplicationImage(String imageName) throws Exception {
        if (isRegistryOf(imageName, "private")) {
            loadApplicationImage(imageName, dockerClientPrivate, "private");
        } else if (isRegistryOf(imageName, "ghcr") && containerConfig.runtimeImages().stream()
                .anyMatch(runtimeImage -> isImage(imageName, runtimeImage))) {
            loadApplicationImage(imageName, dockerClientGhcr, "ghcr");
        } else if (containerConfig.localImagePrefixes().stream().anyMatch(imageName::startsWith)) {
            // built by a local-only pipeline: never pulled, so a registry can not serve an image of the same name
            if (!isAvailableLocally(imageName, dockerClientPrivate)) {
                throw new NotFoundException("Locally built Docker image not found: " + imageName);
            }
        } else {
            Log.errorf("Docker image %s is not from an allowed registry", imageName);
            throw new ForbiddenException("Docker image registry not allowed: " + imageName);
        }
    }

    public void loadPipelineImage(String imageName) throws Exception {
        if (!isRegistryOf(imageName, "ghcr") || !isPipelineImage(imageName)) {
            Log.errorf("Pipeline image %s is not an allowed image", imageName);
            throw new ForbiddenException("Pipeline image not allowed: " + imageName);
        }
        loadApplicationImage(imageName, dockerClientGhcr, "ghcr");
    }

    // the image must be exactly pipeline.image-prefix, with any tag or digest
    private boolean isPipelineImage(String imageName) {
        return isImage(imageName, pipelineConfig.imagePrefix());
    }

    static boolean isImage(String imageName, String name) {
        return imageName.equals(name) || imageName.startsWith(name + ":") || imageName.startsWith(name + "@");
    }

    // An image without registry is on Docker Hub, e.g. nginx:alpine is docker.io/library/nginx:alpine
    public static String qualified(String imageName) {
        int slash = imageName.indexOf('/');
        String first = slash > 0 ? imageName.substring(0, slash) : "";
        if (first.contains(".") || first.contains(":") || first.equals("localhost")) {
            return imageName;
        }
        return "docker.io/" + (slash > 0 ? "" : "library/") + imageName;
    }

    // Ensures that the registry URL (ignoring any https/http prefix and trailing slashes)
    // is a prefix of the image string
    // -> ensures that the image belongs to the registry configured for the named docker client
    boolean isRegistryOf(String imageName, String clientName) {
        String image = qualified(imageName);
        DockerClientRuntimeConfig config = dockerRuntimeConfig.namedDockerClients().get(clientName);
        return config != null && config.enabled() && config.registryUrl()
            .map(registryurl -> registryurl.replaceFirst("^https?://", "").replaceAll("/+$", ""))
            .filter(url -> {
                boolean matches = image.startsWith(url + "/");
                if (!matches) {
                    Log.infof("Registry mismatch for Docker client '%s': image registry '%s', normalized configured registry '%s'",
                            clientName, image, url);
                }
                return matches;
            })
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
