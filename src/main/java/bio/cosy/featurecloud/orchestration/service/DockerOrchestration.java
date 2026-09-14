package bio.cosy.featurecloud.orchestration.service;


import bio.cosy.featurecloud.orchestration.docker.client.config.DockerRuntimeConfig;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Info;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class DockerOrchestration {

    @Inject
    DockerClient dockerClient;

    @Inject
    DockerRuntimeConfig config;

    public Info getInfo() {
        // DockerClientFactory factory = new DockerClientFactory(config.defaultDockerClient());
        //SystemDfCmdExec systemDfCmdExec = new SystemDfCmdExec(factory.);
        return dockerClient.infoCmd().exec();
    }
}
