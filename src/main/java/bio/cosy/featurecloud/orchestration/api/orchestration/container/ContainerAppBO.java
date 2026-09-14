package bio.cosy.featurecloud.orchestration.api.orchestration.container;

import bio.cosy.featurecloud.orchestration.api.logging.ContainerLogAO;
import bio.cosy.featurecloud.orchestration.api.logging.ContainerLogDTO;
import bio.cosy.featurecloud.orchestration.config.ContainerConfig;
import bio.cosy.featurecloud.orchestration.docker.ContainerNetworkAccess;
import bio.cosy.featurecloud.orchestration.service.DockerAppService;
import bio.cosy.featurecloud.orchestration.service.DockerNetworkService;
import bio.cosy.featurecloud.orchestration.service.DockerPullService;
import bio.cosy.featurecloud.orchestration.service.DockerVolumeService;
import bio.cosy.featurecloud.orchestration.service.DockerCleanupService;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Container;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.OnOverflow;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static bio.cosy.featurecloud.orchestration.api.orchestration.container.ContainerServiceImpl.LOG_CHANNEL;

@ApplicationScoped
public class ContainerAppBO {

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

    @Inject
    @Channel(LOG_CHANNEL)
    @OnOverflow(OnOverflow.Strategy.DROP)
    Emitter<ContainerLogDTO> logEmitter;

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

    public CreateContainerResponse startContainer(StartAppDTO createDTO) {
        Log.infof("Starting container process for application ID: %d in group",
                createDTO.getIdentifier(), createDTO.getGroupId());

        ContainerNetworkAccess access = ContainerNetworkAccess.of(
                createDTO.getNeedsInternetAccess(), createDTO.getNeedsHostAccess(), false);
        dockerNetworkService.ensureAccessIsConfigured(access);
        ensureNotRunningAlready(createDTO);
        try {
            Log.infof("Loading application image: %s", createDTO.getAppImage());
            dockerPullService.loadApplicationImage(createDTO.getAppImage());
        } catch (jakarta.ws.rs.NotFoundException e) {
            Log.errorf("Failed to load Docker image %s: %s", createDTO.getAppImage(), e.getMessage());
            throw new NotFoundException(e.getMessage());
        } catch (Exception e) {
            Log.errorf("Failed to load Docker image %s: %s", createDTO.getAppImage(), e.getMessage());
        }

        CreateContainerResponse response;
        ContainerRunEntity run = containerRunAO.create(createDTO);
        Log.info("No volumes required for this container");
        response = dockerAppService.startApplication(createDTO);

        dockerNetworkService.connectContainerToNetworks(response.getId(), access);

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
                            if (containerConfig.logContainerLogs()) {
                                Log.infof("Database stream - container %s log: %s", created.getId(), log);
                            }
                            ContainerLogDTO logDTO = containerLogAO.createLogTransactional(logId, log);
                            sendMessage(logDTO);
                        }, failure -> {
                            Log.errorf("Failed to database stream logs for container %s: %s", created.getId(), failure.getMessage());
                        }, () -> {
                            Log.infof("Log database stream for container %s has ended", created.getId());
                        })
        );
    }

    private void ensureNotRunningAlready(StartAppDTO createDTO) {
        Log.debugf("Checking if container %d group %d already exists",
                createDTO.getIdentifier(), createDTO.getGroupId());

        Optional<Container> currentContainer = dockerAppService.getCurrentContainer(createDTO.getIdentifier(), createDTO.getGroupId());
        Optional<Container> nameContainer = dockerAppService.getByName(createDTO.getContainerName());

        if (currentContainer.isEmpty() && nameContainer.isEmpty()) {
            Log.debug("No existing container found, proceeding with creation");
            return;
        }
        if (currentContainer.isPresent()) {
            dockerCleanupService.cleanupContainer(currentContainer.get().getId(), true, true, true);
        }
        nameContainer.filter(container -> currentContainer.stream()
                        .noneMatch(current -> current.getId().equals(container.getId())))
                .ifPresent(container ->
                        dockerCleanupService.cleanupContainer(container.getId(), true, true, true));
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


    public void stopContainers(List<String> containerIds, boolean hardCleanup) {
        executor.execute(() ->
                Multi.createFrom().iterable(containerIds)
                        .emitOn(Infrastructure.getDefaultWorkerPool())
                        .subscribe().with(
                                containerId -> {
                                    try {
                                        stopContainer(containerId, hardCleanup);
                                    } catch (Exception e) {
                                        Log.errorf("Failed to stop container %s: %s", containerId, e.getMessage());
                                    }
                                },
                                failure -> Log.errorf("Failed during bulk container cleanup: %s", failure.getMessage())
                        )
        );
    }

    public void sendMessage(ContainerLogDTO info) {
        if (info == null) {
            Log.warn("Log info is null, cannot send SSE event");
            return;
        }
        if (logEmitter == null) {
            Log.warn("Log emitter is not available, cannot send SSE event");
            return;
        }
        try {
            logEmitter.send(info);
            Log.debug("SSE event sent: " + info);
        } catch (Exception e) {
            Log.debug("Attempted to send SSE event, but client connection was already closed.", e);
        }
    }
}
