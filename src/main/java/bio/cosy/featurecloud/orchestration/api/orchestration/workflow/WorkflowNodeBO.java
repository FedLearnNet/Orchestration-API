package bio.cosy.featurecloud.orchestration.api.orchestration.workflow;

import bio.cosy.featurecloud.orchestration.api.logging.ContainerLogAO;
import bio.cosy.featurecloud.orchestration.api.orchestration.container.ContainerRunAO;
import bio.cosy.featurecloud.orchestration.api.orchestration.container.ContainerRunEntity;
import bio.cosy.featurecloud.orchestration.api.orchestration.container.ContainerRunStatus;
import bio.cosy.featurecloud.orchestration.api.orchestration.workflow.actions.WorkflowNodeInputActionDTO;
import bio.cosy.featurecloud.orchestration.config.ContainerConfig;
import bio.cosy.featurecloud.orchestration.docker.ContainerNetworkAccess;
import bio.cosy.featurecloud.orchestration.docker.DockerVolumeType;
import bio.cosy.featurecloud.orchestration.service.DockerAppService;
import bio.cosy.featurecloud.orchestration.service.DockerNetworkService;
import bio.cosy.featurecloud.orchestration.service.DockerPullService;
import bio.cosy.featurecloud.orchestration.service.DockerVolumeService;
import bio.cosy.featurecloud.orchestration.service.DockerCleanupService;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.model.Container;
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
    DockerVolumeService dockerVolumeService;

    @Inject
    DockerAppService dockerAppService;

    @Inject
    DockerPullService dockerPullService;

    @Inject
    DockerCleanupService dockerCleanupService;

    @Inject
    ContainerRunAO containerRunAO;

    @Inject
    ContainerLogAO containerLogAO;

    @Inject
    ManagedExecutor executor;

    @Inject
    DockerNetworkService dockerNetworkService;

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

    public CreateContainerResponse startContainer(StartWorkflowNodeDTO createDTO) {
        Log.infof("Starting workflow node: %d in workflow: %d",
                createDTO.getWorkflowNodeId(), createDTO.getWorkflowId());

        ContainerNetworkAccess access = ContainerNetworkAccess.of(
                createDTO.getNeedsInternetAccess(),
                createDTO.getNeedsHostAccess(),
                createDTO.getNeedsFederatedLearningAccess());
        // Checked before anything is created so a misconfigured orchestrator does not leave a
        // started container behind that can never reach what it needs
        dockerNetworkService.ensureAccessIsConfigured(access);

        ensureNotRunningAlready(createDTO);
        try {
            Log.infof("Loading application image: %s", createDTO.getAppImage());
            dockerPullService.loadApplicationImage(createDTO.getAppImage());
        } catch (NotFoundException e) {
            Log.errorf("Failed to load Docker image %s: %s", createDTO.getAppImage(), e.getMessage());
            throw new NotFoundException(e.getMessage());
        } catch (Exception e) {
            Log.errorf(e, "Failed to load Docker image %s for workflow %d node %d: %s",
                    createDTO.getAppImage(), createDTO.getWorkflowId(), createDTO.getWorkflowNodeId(), e.getMessage());
        }

        CreateContainerResponse response;
        ContainerRunEntity run = containerRunAO.create(createDTO);

        try {
            Log.info("Volumes required for this container - preparing input and output volumes");
            String outputVolumeName = ensureOutputVolume(createDTO);
            //First output, then input to ensure data consistency
            String inputVolumeName = ensureInputVolume(createDTO);
            response = dockerAppService.startApplication(createDTO, inputVolumeName, outputVolumeName, false);

            dockerNetworkService.connectContainerToNetworks(response.getId(), access);
        } catch (Exception e) {
            Log.errorf(e, "Failed to start container for workflow %d node %d (image %s, name %s): %s",
                    createDTO.getWorkflowId(), createDTO.getWorkflowNodeId(),
                    createDTO.getAppImage(), createDTO.getContainerName(), e.getMessage());
            throw e;
        }

        Log.infof("Container started successfully with ID: %s", response.getId());
        startedContainers.add(response.getId());

        containerRunAO.updateStatus(run.getId(), response.getId(), ContainerRunStatus.STARTED);
        ensureLogStream(response, run.getId());

        return response;
    }


    public void ensureLogStream(CreateContainerResponse created, Long logId) {
        Log.infof("Starting log database stream for container %s", created.getId());

        executor.execute(() ->
                dockerAppService.getLogById(created.getId())
                        .emitOn(Infrastructure.getDefaultExecutor())
                        .subscribe()
                        .with(log -> {
                            if(containerConfig.logContainerLogs()) {
                                Log.infof("Database stream - container %s log: %s", created.getId(), log);
                            }
                            containerLogAO.createLogTransactional(logId, log);
                        }, failure -> {
                            Log.errorf("Failed to database stream logs for container %s: %s", created.getId(), failure.getMessage());
                        }, () -> {
                            Log.infof("Log database stream for container %s has ended", created.getId());
                        })
        );
    }

    private void ensureNotRunningAlready(StartWorkflowNodeDTO createDTO) {
        Log.debugf("Checking if container for workflow %d node %d already exists",
                createDTO.getWorkflowId(), createDTO.getWorkflowNodeId());

        Optional<Container> currentContainer = dockerAppService.getCurrentWorkflowNode(createDTO.getWorkflowId(), createDTO.getWorkflowNodeId());
        Optional<Container> nameContainer = dockerAppService.getByName(createDTO.getContainerName());

        if (currentContainer.isEmpty() && nameContainer.isEmpty()) {
            Log.debug("No existing container found, proceeding with creation");
            return;
        }

        // Either lookup may be empty on its own - a leftover container can be found by label, by
        // name, or by both, so each hit is cleaned up independently.
        currentContainer.ifPresent(container -> {
            Log.infof("Hard cleanup of container %s for workflow %d node %d",
                    container.getId(), createDTO.getWorkflowId(), createDTO.getWorkflowNodeId());
            dockerCleanupService.cleanupContainer(container.getId(), true, true, true);
        });
        nameContainer.filter(container -> currentContainer.stream()
                        .noneMatch(current -> current.getId().equals(container.getId())))
                .ifPresent(container -> {
                    Log.infof("Hard cleanup of container %s found by name %s",
                            container.getId(), createDTO.getContainerName());
                    dockerCleanupService.cleanupContainer(container.getId(), true, true, true);
                });
    }

    //All previous containers must be runned to ensure data consistency,
    private String ensureInputVolume(StartWorkflowNodeDTO createDTO) {
        Log.infof("Setting up input volume for workflow %d node %d", createDTO.getWorkflowId(), createDTO.getWorkflowNodeId());
        String newVolumeName = dockerVolumeService.createVolume(createDTO.getWorkflowId(), DockerVolumeType.INPUT, createDTO.getInputVolumeName()).getName();
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
        InspectVolumeResponse volume = dockerVolumeService.getVolume(prevVolumeName);
        if (volume == null) {
            Log.warnf("Previous output volume %s not found for data migration", prevVolumeName);
            throw new NotFoundException("Previous output volume not found: " + prevVolumeName);
        }

        try {
            Log.infof("Moving data from previous output volume %s to new input volume %s", prevVolumeName, newVolumeName);
            dockerVolumeService.moveFileToToAnotherContainer(prevVolumeName, newVolumeName, prevInput.getOriginalFileName(), prevInput.getNewFileName());
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
        String newVolumeName = dockerVolumeService.createVolume(createDTO.getWorkflowId(), DockerVolumeType.OUTPUT, createDTO.getOutputVolumeName()).getName();
        Log.infof("Created output volume: %s", newVolumeName);
        return newVolumeName;
    }

    public void stopContainerStep(String step, boolean hardCleanup) {
        List<Container> containers = dockerAppService.getByStepLabel(step);
        for (Container com : containers) {
            stopContainer(com.getId(), hardCleanup);
        }
    }

    public void stopContainer(String containerId, boolean hardCleanup) {
        Log.debugf("Checking if container %s already run and if stop",
                containerId);

        Container container = dockerAppService.getById(containerId);
        if (container == null) {
            Log.warnf("Container with ID %s not found", containerId);
            return;
        }
        dockerCleanupService.cleanupContainer(containerId, hardCleanup, hardCleanup, true);
    }
}