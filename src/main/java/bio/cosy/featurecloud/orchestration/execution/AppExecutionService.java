package bio.cosy.featurecloud.orchestration.execution;

import bio.cosy.featurecloud.orchestration.api.orchestration.container.StartAppDTO;
import bio.cosy.featurecloud.orchestration.api.orchestration.workflow.StartWorkflowNodeDTO;
import bio.cosy.featurecloud.orchestration.docker.ContainerNetworkAccess;
import io.smallrye.mutiny.Multi;

import java.util.List;
import java.util.Optional;

/**
 * Backend-neutral interface for running FL/ETL Tool containers — the
 * execution-side counterpart to what {@code DockerAppService} does today,
 * implemented by both a Docker backend ({@code DockerExecutionAdapter},
 * wrapping the existing, unmodified {@code DockerAppService}) and a
 * Kubernetes backend ({@link
 * bio.cosy.featurecloud.orchestration.execution.k8s.KubernetesExecutionService}).
 * See {@code docs/kubernetes-execution-backend.md} for the design and what's
 * deliberately *not* covered by this interface yet (Docker-in-Docker
 * pipelines, host-access, volume-to-volume copy helpers).
 */
public interface AppExecutionService {

    /**
     * Best-effort pre-pull of the Tool image before starting a run. Only
     * meaningful for the Docker backend (which needs an explicit pull step);
     * Kubernetes has kubelet do this declaratively as part of creating the
     * Job's Pod, so the default here is a no-op rather than every other
     * implementation having to override it with an empty body.
     */
    default void ensureImageAvailable(String image) {
    }

    AppRun startApplication(StartWorkflowNodeDTO dto, String inputVolumeName, String outputVolumeName, boolean autokill);

    AppRun startApplication(StartAppDTO dto);

    List<AppInstance> showRunning();

    List<AppInstance> showFeatureCloudContainers();

    AppInstance getById(String id);

    List<AppInstance> getByStepLabel(String step);

    /**
     * Stops a run and, as asked, removes it, its volumes and its own network
     * isolation. Mirrors {@code DockerCleanupService.cleanupContainer}, which
     * every caller used before this interface existed: the run is always
     * stopped, and a run this deployment doesn't own is left alone.
     */
    void cleanupContainer(String id, boolean removeContainer, boolean removeVolumes, boolean removeDedicatedNetwork);

    /** Removes every run of a workflow, with its volumes. */
    void cleanupWorkflow(Long workflowId);

    /** Removes every run of a workflow node; volumes are kept. */
    void cleanupWorkflowNode(Long workflowNodeId);

    /**
     * Fails before anything is created if the deployment can't give a run
     * the network access it asked for (e.g. federated learning without a
     * configured controller), so no run is left behind that can never reach
     * what it needs.
     */
    void ensureNetworkAccessConfigured(ContainerNetworkAccess access);

    /**
     * Gives a started run the network access it asked for. Docker attaches
     * the container to its networks after start; the Kubernetes backend
     * already applies a run's isolation when it creates it, so the default
     * is a no-op.
     */
    default void connectNetworks(String id, ContainerNetworkAccess access) {
    }

    /**
     * Removes a locally cached image. Kubernetes nodes manage their own image
     * cache, so the default is a no-op.
     */
    default void removeImage(String name) {
    }

    Optional<AppInstance> getCurrentContainer(Long id, Long groupId);

    Optional<AppInstance> getCurrentWorkflowNode(Long workflowId, Long workflowNodeId);

    Optional<AppInstance> getByName(String name);

    Multi<String> getLogById(String id);
}
