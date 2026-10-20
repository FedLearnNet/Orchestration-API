package bio.cosy.featurecloud.orchestration.events;

import bio.cosy.featurecloud.orchestration.api.orchestration.container.ContainerPipelineBO;
import bio.cosy.featurecloud.orchestration.service.DockerCleanupService;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class DockerEvents {

    public static final String CONTAINER_DIED = "containerDied";

    @Inject
    DockerCleanupService dockerCleanupService;

    @Inject
    ContainerPipelineBO pipelineBO;

    @ConsumeEvent(value = CONTAINER_DIED, blocking = true, ordered = true)
    public void containerDied(String containerId) {
        dockerCleanupService.cleanupContainer(containerId, true, true, true);
        pipelineBO.forgetContainer(containerId);
    }

}
