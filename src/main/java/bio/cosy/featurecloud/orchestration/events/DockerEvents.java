package bio.cosy.featurecloud.orchestration.events;

import bio.cosy.featurecloud.orchestration.api.orchestration.container.ContainerPipelineBO;
import bio.cosy.featurecloud.orchestration.api.orchestration.container.ContainerRunAO;
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

    @Inject
    ContainerRunAO containerRunAO;

    @ConsumeEvent(value = CONTAINER_DIED, blocking = true, ordered = true)
    public void containerDied(ContainerDiedEvent event) {
        containerRunAO.recordExit(event.getContainerId(), event.getExitCode());
        // Only pipelines are cleaned up here, app containers keep their volumes for the learning API
        if (event.isPipeline()) {
            dockerCleanupService.cleanupContainer(event.getContainerId(), true, true, true);
            pipelineBO.forgetContainer(event.getContainerId());
        }
    }

}
