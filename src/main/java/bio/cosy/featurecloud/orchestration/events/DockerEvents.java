package bio.cosy.featurecloud.orchestration.events;

import bio.cosy.featurecloud.orchestration.api.orchestration.container.ContainerPipelineBO;
import bio.cosy.featurecloud.orchestration.api.orchestration.container.ContainerRunAO;
import bio.cosy.featurecloud.orchestration.service.DockerCleanupService;
import bio.cosy.featurecloud.orchestration.service.DockerNetworkService;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class DockerEvents {

    public static final String CONTAINER_DIED = "containerDied";

    public static final String CONTAINER_DESTROYED = "containerDestroyed";

    @Inject
    DockerCleanupService dockerCleanupService;

    @Inject
    DockerNetworkService dockerNetworkService;

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

    // Auto-removed containers skip every cleanup path, so their dedicated networks go here
    @ConsumeEvent(value = CONTAINER_DESTROYED, blocking = true, ordered = true)
    public void containerDestroyed(String containerId) {
        dockerNetworkService.removeDedicatedNetworksOf(containerId);
    }

}
