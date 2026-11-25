package bio.cosy.featurecloud.orchestration.execution.docker;

import bio.cosy.featurecloud.orchestration.api.orchestration.container.StartAppDTO;
import bio.cosy.featurecloud.orchestration.api.orchestration.workflow.StartWorkflowNodeDTO;
import bio.cosy.featurecloud.orchestration.docker.ContainerNetworkAccess;
import bio.cosy.featurecloud.orchestration.execution.AppExecutionService;
import bio.cosy.featurecloud.orchestration.execution.AppInstance;
import bio.cosy.featurecloud.orchestration.execution.AppRun;
import bio.cosy.featurecloud.orchestration.execution.AppStatus;
import bio.cosy.featurecloud.orchestration.execution.DockerBackend;
import bio.cosy.featurecloud.orchestration.service.DockerAppService;
import bio.cosy.featurecloud.orchestration.service.DockerCleanupService;
import bio.cosy.featurecloud.orchestration.service.DockerNetworkService;
import bio.cosy.featurecloud.orchestration.service.DockerPullService;

import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerMount;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * The Docker backend: wraps the existing, unmodified {@link DockerAppService},
 * {@link DockerCleanupService}, {@link DockerNetworkService} and
 * {@link DockerPullService} behind {@link AppExecutionService}. No
 * behavioural change: every call is a direct delegation.
 */
@DockerBackend
@ApplicationScoped
public class DockerExecutionAdapter implements AppExecutionService {

    @Inject
    DockerAppService dockerAppService;

    @Inject
    DockerPullService dockerPullService;

    @Inject
    DockerCleanupService dockerCleanupService;

    @Inject
    DockerNetworkService dockerNetworkService;

    @Override
    public void ensureImageAvailable(String image) {
        try {
            dockerPullService.loadApplicationImage(image);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // Callers turn this into a failed start, as they did when they
            // called DockerPullService directly.
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static AppRun toAppRun(CreateContainerResponse response) {
        return new AppRun(response.getId());
    }

    private static AppInstance toAppInstance(Container container) {
        String name = container.getNames() != null && container.getNames().length > 0
                ? container.getNames()[0].replaceFirst("^/", "")
                : container.getId();
        List<String> volumeNames = container.getMounts() == null
                ? List.of()
                : container.getMounts().stream()
                        .map(ContainerMount::getName)
                        .filter(java.util.Objects::nonNull)
                        .toList();
        return new AppInstance(
                container.getId(),
                name,
                container.getImage(),
                container.getLabels(),
                toAppStatus(container.getState()),
                volumeNames,
                // On a Docker network a container is reachable by its own name.
                name
        );
    }

    private static AppStatus toAppStatus(String dockerState) {
        if (dockerState == null) {
            return AppStatus.UNKNOWN;
        }
        return switch (dockerState.toLowerCase()) {
            case "created", "restarting" -> AppStatus.PENDING;
            case "running" -> AppStatus.RUNNING;
            case "exited" -> AppStatus.SUCCEEDED; // docker-java doesn't expose the exit code on the list view; ContainerAppBO never distinguished exit codes here either
            case "dead" -> AppStatus.FAILED;
            default -> AppStatus.UNKNOWN;
        };
    }

    @Override
    public AppRun startApplication(StartWorkflowNodeDTO dto, String inputVolumeName, String outputVolumeName, boolean autokill) {
        return toAppRun(dockerAppService.startApplication(dto, inputVolumeName, outputVolumeName, autokill));
    }

    @Override
    public AppRun startApplication(StartAppDTO dto) {
        return toAppRun(dockerAppService.startApplication(dto));
    }

    @Override
    public List<AppInstance> showRunning() {
        return dockerAppService.showRunning().stream().map(DockerExecutionAdapter::toAppInstance).toList();
    }

    @Override
    public List<AppInstance> showFeatureCloudContainers() {
        return dockerAppService.showFeatureCloudContainers().stream().map(DockerExecutionAdapter::toAppInstance).toList();
    }

    @Override
    public AppInstance getById(String id) {
        return toAppInstance(dockerAppService.getById(id));
    }

    @Override
    public List<AppInstance> getByStepLabel(String step) {
        return dockerAppService.getByStepLabel(step).stream().map(DockerExecutionAdapter::toAppInstance).toList();
    }

    @Override
    public void cleanupContainer(String id, boolean removeContainer, boolean removeVolumes, boolean removeDedicatedNetwork) {
        dockerCleanupService.cleanupContainer(id, removeContainer, removeVolumes, removeDedicatedNetwork);
    }

    @Override
    public void cleanupWorkflow(Long workflowId) {
        dockerCleanupService.cleanupWorkflow(workflowId);
    }

    @Override
    public void cleanupWorkflowNode(Long workflowNodeId) {
        dockerCleanupService.cleanupWorkflowNode(workflowNodeId);
    }

    @Override
    public void ensureNetworkAccessConfigured(ContainerNetworkAccess access) {
        dockerNetworkService.ensureAccessIsConfigured(access);
    }

    @Override
    public void connectNetworks(String id, ContainerNetworkAccess access) {
        dockerNetworkService.connectContainerToNetworks(id, access);
    }

    @Override
    public void removeImage(String name) {
        dockerCleanupService.removeImage(name);
    }

    @Override
    public Optional<AppInstance> getCurrentContainer(Long id, Long groupId) {
        return dockerAppService.getCurrentContainer(id, groupId).map(DockerExecutionAdapter::toAppInstance);
    }

    @Override
    public Optional<AppInstance> getCurrentWorkflowNode(Long workflowId, Long workflowNodeId) {
        return dockerAppService.getCurrentWorkflowNode(workflowId, workflowNodeId).map(DockerExecutionAdapter::toAppInstance);
    }

    @Override
    public Optional<AppInstance> getByName(String name) {
        return dockerAppService.getByName(name).map(DockerExecutionAdapter::toAppInstance);
    }

    @Override
    public Multi<String> getLogById(String id) {
        return dockerAppService.getLogById(id);
    }
}
