package bio.cosy.featurecloud.orchestration.service;

import bio.cosy.featurecloud.orchestration.helper.NamingService;
import com.github.dockerjava.api.model.Container;
import bio.cosy.featurecloud.orchestration.docker.DockerLabels;
import bio.cosy.featurecloud.orchestration.docker.LabelsHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import io.quarkus.logging.Log;
import java.util.List;

@ApplicationScoped
public class DockerCleanupService {

    @Inject
    DockerAppService dockerAppService;

    @Inject
    DockerNetworkService dockerNetworkService;

    @Inject
    DockerVolumeService dockerVolumeService;

    /**
     * Cleans up a Docker container and its associated resources.
     * Stops the container, removes its dedicated network, and optionally removes the container and its associated volumes.
     *
     * @param containerId            The ID of the container to clean up.
     * @param removeContainer        Whether to remove the container itself. The container is always stopped when calling this method.
     * @param removeVolumes          Whether to remove volumes associated with the container.
     * @param removeDedicatedNetwork Whether to remove the dedicated network associated with the container.
     */
    public void cleanupContainer(String containerId, boolean removeContainer, boolean removeVolumes, boolean removeDedicatedNetwork) {
        if (removeVolumes && !removeContainer) {
            Log.warn("Removing volumes without removing the container may fail.");
        }
        Container container;
        try {
            container = dockerAppService.getById(containerId);
        } catch (NotFoundException e) {
            Log.warnf("Skipping cleanup: container=%s system_name=%s is already removed", containerId, NamingService.getSystemName());
            return;
        }
        // Check ownership even for direct cleanup requests and even when volumes are kept.
        if (!LabelsHelper.isOwned(container.getLabels())) {
            Log.warnf("Refusing cleanup: container=%s expectedSystem=%s actualSystem=%s", containerId,
                    NamingService.getSystemName(), container.getLabels() == null ? null
                            : container.getLabels().get(DockerLabels.SYSTEM_NAME.toString()));
            return;
        }
        Log.infof("Cleaning up container=%s names=%s state=%s labels=%s (removeContainer=%s, removeVolumes=%s, removeDedicatedNetwork=%s)",
                containerId, java.util.Arrays.toString(container.getNames()), container.getState(), container.getLabels(),
                removeContainer, removeVolumes, removeDedicatedNetwork);

        // 2. Stop container (always)
        if (dockerAppService.isRunning(containerId)) {
            dockerAppService.stopContainer(containerId);
        }

        // 3. Remove dedicated network while the container can still be inspected
        if (removeDedicatedNetwork) {
            dockerNetworkService.removeDedicatedNetwork(containerId);
        }

        // 4. Remove container
        if (removeContainer && !dockerAppService.removeApplication(containerId)) {
            throw new IllegalStateException("Failed to remove container " + containerId
                    + " for system_name=" + NamingService.getSystemName() + "; retaining its volumes");
        }

        // 5. Remove associated volumes (using fetched metadata)
        if (removeVolumes && container.getMounts() != null) {
            container.getMounts().forEach(mount -> {
                if (mount.getName() != null) { // Filter out anonymous/bind mounts where name is null
                    Log.infof("Removing volume: %s", mount.getName());
                    dockerVolumeService.removeVolume(mount.getName());
                }
            });
        }
    }

    public void cleanupWorkflow(Long workflowId) {
        List<Container> containers = dockerAppService.getWorkflowContainers(workflowId);
        for (Container container : containers) {
            cleanupContainer(container.getId(), true, true, true);
        }
    }

    public void cleanupWorkflowNode(Long workflowNodeId) {
        List<Container> containers = dockerAppService.getWorkflowNodeContainers(workflowNodeId);
        for (Container container : containers) {
            cleanupContainer(container.getId(), true, false, true);
        }
    }
}
