package bio.cosy.featurecloud.orchestration.service;

import bio.cosy.featurecloud.orchestration.docker.client.NamedDockerClient;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class DockerPullService {

    @Inject
    @NamedDockerClient("featurecloud")
    DockerClient dockerClient;

    @Inject
    @NamedDockerClient("gitlab")
    DockerClient dockerClientGitlab;

    @Inject
    DockerClient defaultDockerClient;


    public void loadApplicationImage(String imageName) throws Exception {
        try {
            if (imageName.contains("gitlab.cosy.bio")) {
                loadApplicationImage(imageName, dockerClientGitlab, "gitlab");
            } else if (imageName.contains("featurecloud")) {
                loadApplicationImage(imageName, dockerClient, "featurecloud");
            } else {
                loadApplicationImage(imageName, defaultDockerClient, "default");
            }
        } catch (NotFoundException e) {
            Log.errorf("Docker image %s not found: %s", imageName, e.getMessage());
            throw new jakarta.ws.rs.NotFoundException("Docker image not found: " + imageName, e);
        }
    }


    private void loadApplicationImage(String imageName, DockerClient client, String config) throws Exception {
        Log.infof("Try Pulling Docker image: %s at %s", imageName, config);
        try {
            client.pullImageCmd(imageName)
                    .exec(new PullImageResultCallback())
                    .awaitCompletion();
            Log.infof("Successfully pulled Docker image: %s", imageName);
        } catch (NotFoundException e) {
            Log.errorf("Docker image %s not found: %s", imageName, e.getMessage());
            throw new jakarta.ws.rs.NotFoundException("Docker image not found: " + imageName, e);
        } catch (Exception e) {
            Log.errorf("Failed to pull Docker image %s: %s", imageName, e.getMessage());
            throw e;
        }
    }
}