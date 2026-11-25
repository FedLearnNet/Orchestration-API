package bio.cosy.featurecloud.orchestration.api.orchestration.workflow;

import bio.cosy.featurecloud.orchestration.api.logging.ContainerLogAO;
import bio.cosy.featurecloud.orchestration.api.orchestration.container.ContainerRunAO;
import bio.cosy.featurecloud.orchestration.api.orchestration.container.ContainerRunEntity;
import bio.cosy.featurecloud.orchestration.api.orchestration.container.ContainerRunStatus;
import bio.cosy.featurecloud.orchestration.api.orchestration.workflow.actions.WorkflowNodeInputActionDTO;
import bio.cosy.featurecloud.orchestration.config.ContainerConfig;
import bio.cosy.featurecloud.orchestration.docker.ContainerNetworkAccess;
import bio.cosy.featurecloud.orchestration.docker.DockerVolumeType;
import bio.cosy.featurecloud.orchestration.execution.AppExecutionService;
import bio.cosy.featurecloud.orchestration.execution.AppInstance;
import bio.cosy.featurecloud.orchestration.execution.AppRun;
import bio.cosy.featurecloud.orchestration.execution.VolumeExecutionService;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class WorkflowNodeBO {

    @Inject
    AppExecutionService appExecutionService;

    @Inject
    VolumeExecutionService volumeExecutionService;

    @Inject
    ContainerRunAO containerRunAO;

    @Inject
    ContainerLogAO containerLogAO;

    @Inject
    ManagedExecutor executor;

    @Inject
    ContainerConfig containerConfig;

    private final Set<String> startedContainers = new HashSet<>();


    void onShutdown(@Observes ShutdownEvent ev) {
        if (!containerConfig.autoStop()) {
            Log.info("Auto-stop is disabled, skipping container shutdown");
            return;
        }
        for (String container : startedContainers) {
            try {
                stopContainer(container, true);
            } catch (NotFoundException e) {
                Log.warnf("Container %s is already stopped or removed; skipping shutdown cleanup", container);
            } catch (Exception e) {
                Log.errorf(e, "Failed to stop container %s during shutdown", container);
            }
        }
    }

    public AppRun startContainer(StartWorkflowNodeDTO createDTO) {
        Log.infof("Starting workflow node: %d in workflow: %d",
                createDTO.getWorkflowNodeId(), createDTO.getWorkflowId());

        ContainerNetworkAccess access = ContainerNetworkAccess.of(
                createDTO.getNeedsInternetAccess(),
                createDTO.getNeedsHostAccess(),
                createDTO.getNeedsFederatedLearningAccess());
        // Checked before anything is created so a misconfigured orchestrator does not leave a
        // started container behind that can never reach what it needs
        appExecutionService.ensureNetworkAccessConfigured(access);

        ensureNotRunningAlready(createDTO);
        try {
            Log.infof("Loading application image: %s", createDTO.getAppImage());
            appExecutionService.ensureImageAvailable(createDTO.getAppImage());
        } catch (NotFoundException e) {
            Log.errorf("Failed to load image %s: %s", createDTO.getAppImage(), e.getMessage());
            throw new NotFoundException(e.getMessage());
        } catch (Exception e) {
            Log.errorf(e, "Failed to load image %s for workflow %d node %d: %s",
                    createDTO.getAppImage(), createDTO.getWorkflowId(), createDTO.getWorkflowNodeId(), e.getMessage());
            throw new IllegalStateException("Failed to pull image " + createDTO.getAppImage() + ": " + e.getMessage(), e);
        }

        ContainerRunEntity run = containerRunAO.create(createDTO);

        AppRun response;
        try {
            Log.info("Volumes required for this container - preparing input and output volumes");
            String outputVolumeName = ensureOutputVolume(createDTO);
            //First output, then input to ensure data consistency
            String inputVolumeName = ensureInputVolume(createDTO);
            response = appExecutionService.startApplication(createDTO, inputVolumeName, outputVolumeName, false);

            appExecutionService.connectNetworks(response.id(), access);
        } catch (Exception e) {
            Log.errorf(e, "Failed to start container for workflow %d node %d (image %s, name %s): %s",
                    createDTO.getWorkflowId(), createDTO.getWorkflowNodeId(),
                    createDTO.getAppImage(), createDTO.getContainerName(), e.getMessage());
            throw e;
        }

        Log.infof("Container started successfully with ID: %s", response.id());
        startedContainers.add(response.id());

        containerRunAO.updateStatus(run.getId(), response.id(), ContainerRunStatus.STARTED);
        ensureLogStream(response, run.getId());

        return response;
    }


    public void ensureLogStream(AppRun created, Long logId) {
        Log.infof("Starting log database stream for container %s", created.id());

        executor.execute(() ->
                appExecutionService.getLogById(created.id())
                        .emitOn(Infrastructure.getDefaultExecutor())
                        .subscribe()
                        .with(log -> {
                            if(containerConfig.logContainerLogs()) {
                                Log.infof("Database stream - container %s log: %s", created.id(), log);
                            }
                            containerLogAO.createLogTransactional(logId, log);
                        }, failure -> {
                            Log.errorf("Failed to database stream logs for container %s: %s", created.id(), failure.getMessage());
                        }, () -> {
                            Log.infof("Log database stream for container %s has ended", created.id());
                        })
        );
    }

    private void ensureNotRunningAlready(StartWorkflowNodeDTO createDTO) {
        Log.debugf("Checking if container for workflow %d node %d already exists",
                createDTO.getWorkflowId(), createDTO.getWorkflowNodeId());

        Optional<AppInstance> currentContainer = appExecutionService.getCurrentWorkflowNode(createDTO.getWorkflowId(), createDTO.getWorkflowNodeId());
        Optional<AppInstance> nameContainer = appExecutionService.getByName(createDTO.getContainerName());

        if (currentContainer.isEmpty() && nameContainer.isEmpty()) {
            Log.debug("No existing container found, proceeding with creation");
            return;
        }

        // Either lookup may be empty on its own - a leftover container can be found by label, by
        // name, or by both, so each hit is cleaned up independently.
        currentContainer.ifPresent(container -> {
            Log.infof("Hard cleanup of container %s for workflow %d node %d",
                    container.id(), createDTO.getWorkflowId(), createDTO.getWorkflowNodeId());
            appExecutionService.cleanupContainer(container.id(), true, true, true);
        });
        nameContainer.filter(container -> currentContainer.stream()
                        .noneMatch(current -> current.id().equals(container.id())))
                .ifPresent(container -> {
                    Log.infof("Hard cleanup of container %s found by name %s",
                            container.id(), createDTO.getContainerName());
                    appExecutionService.cleanupContainer(container.id(), true, true, true);
                });
    }

    //All previous containers must be runned to ensure data consistency,
    private String ensureInputVolume(StartWorkflowNodeDTO createDTO) {
        Log.infof("Setting up input volume for workflow %d node %d", createDTO.getWorkflowId(), createDTO.getWorkflowNodeId());
        String newVolumeName = volumeExecutionService.ensureVolume(createDTO.getWorkflowId(), DockerVolumeType.INPUT, createDTO.getInputVolumeName());
        Log.infof("Created input volume: %s", newVolumeName);

        if (createDTO.getIsFistNode()) {
            Log.debug("First workflow step - no data migration needed for input volume");
            return newVolumeName;
        }

        ensurePreviousVolumes(createDTO);
        return newVolumeName;
    }

    private void ensurePreviousVolumes(StartWorkflowNodeDTO createDTO) {

        for (WorkflowNodeInputActionDTO inputAction : createDTO.getInputs()) {
            ensurePreviousVolume(createDTO.getInputVolumeName(), inputAction, createDTO.getWorkflowId());
        }
    }

    private void ensurePreviousVolume(String newVolumeName, WorkflowNodeInputActionDTO prevInput, Long workflowId) {
        Log.infof("Checking previous output volume %s for data migration to new input volume %s",
                prevInput.getOutputVolumeName(workflowId), newVolumeName);
        String prevVolumeName = prevInput.getOutputVolumeName(workflowId);
        if (!volumeExecutionService.volumeExists(prevVolumeName)) {
            Log.warnf("Previous output volume %s not found for data migration", prevVolumeName);
            throw new NotFoundException("Previous output volume not found: " + prevVolumeName);
        }

        try {
            Log.infof("Moving data from previous output volume %s to new input volume %s", prevVolumeName, newVolumeName);
            volumeExecutionService.copyBetweenVolumes(prevVolumeName, prevInput.getOriginalFileName(), newVolumeName, prevInput.getNewFileName());
        } catch (Exception e) {
            Log.errorf("Failed to move data between volumes: %s", e.getMessage());
            throw new BadRequestException(e.getMessage());
        }
        if (prevInput.isLastUsage()) {
            //Log.infof("Removing previous output volume: %s", prevVolumeName);
            //^dockerVolumeService.removeVolume(prevVolumeName);
        }
    }

    private String ensureOutputVolume(StartWorkflowNodeDTO createDTO) {
        Log.infof("Setting up output volume for workflow %d node %d", createDTO.getWorkflowId(), createDTO.getWorkflowNodeId());
        String newVolumeName = volumeExecutionService.ensureVolume(createDTO.getWorkflowId(), DockerVolumeType.OUTPUT, createDTO.getOutputVolumeName());
        Log.infof("Created output volume: %s", newVolumeName);
        return newVolumeName;
    }

    public void stopContainerStep(String step, boolean hardCleanup) {
        List<AppInstance> containers = appExecutionService.getByStepLabel(step);
        for (AppInstance com : containers) {
            stopContainer(com.id(), hardCleanup);
        }
    }

    public void stopContainer(String containerId, boolean hardCleanup) {
        Log.debugf("Checking if container %s already run and if stop",
                containerId);

        AppInstance container = appExecutionService.getById(containerId);
        if (container == null) {
            Log.warnf("Container with ID %s not found", containerId);
            return;
        }
        // Always remove the stopped app; outputs may still be needed by a later workflow step.
        appExecutionService.cleanupContainer(containerId, true, hardCleanup, true);
    }
}
