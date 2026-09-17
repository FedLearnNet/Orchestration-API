package bio.cosy.featurecloud.orchestration.api.orchestration.container;

import bio.cosy.featurecloud.orchestration.config.ContainerConfig;
import bio.cosy.featurecloud.orchestration.docker.ContainerNetworkAccess;
import bio.cosy.featurecloud.orchestration.config.PipelineConfig;
import bio.cosy.featurecloud.orchestration.service.DockerAppService;
import bio.cosy.featurecloud.orchestration.service.DockerNetworkService;
import bio.cosy.featurecloud.orchestration.service.DockerPullService;
import bio.cosy.featurecloud.orchestration.service.DockerVolumeService;
import bio.cosy.featurecloud.orchestration.service.DockerCleanupService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Container;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class ContainerPipelineBO {

    @Inject
    DockerVolumeService dockerVolumeService;

    @Inject
    DockerAppService dockerAppService;

    @Inject
    DockerPullService dockerPullService;

    @Inject
    DockerNetworkService dockerNetworkService;

    @Inject
    DockerCleanupService dockerCleanupService;

    @Inject
    PipelineConfig config;

    @Inject
    ObjectMapper objectMapper;

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

    public CreateContainerResponse startPipeline(StartPipelineDTO createDTO) {
        Log.infof("Starting container process for pipeline ID: %d",
                createDTO.getPipelineId());
        //enrhcih config
        enrichConfig(createDTO);
        ensureNotRunningAlready(createDTO);
        try {
            Log.infof("Loading application image: %s", createDTO.getAppImage());
            dockerPullService.loadApplicationImage(createDTO.getAppImage());
        } catch (NotFoundException e) {
            Log.errorf("Failed to load Docker image %s: %s", createDTO.getAppImage(), e.getMessage());
            throw new NotFoundException(e.getMessage());
        } catch (Exception e) {
            Log.errorf("Failed to load Docker image %s: %s", createDTO.getAppImage(), e.getMessage());
            throw new IllegalStateException("Failed to pull Docker image " + createDTO.getAppImage() + ": " + e.getMessage(), e);
        }

        CreateContainerResponse response;
        response = dockerAppService.startPipeline(createDTO, config.hostRemove());

        // The build pipeline pulls and pushes images, so it always needs internet access
        dockerNetworkService.connectContainerToNetworks(response.getId(),
                new ContainerNetworkAccess(true, false, false));

        Log.infof("Container started successfully with ID: %s", response.getId());
        startedContainers.add(response.getId());
        return response;
    }

    private void enrichConfig(StartPipelineDTO createDTO) {
        if (config.dockerPassword().isEmpty() && config.dockerLogin()) {
            Log.errorf("Pipeline configuration is missing required Docker registry password. Please set PIPELINE_DOCKER_PASSWORD environment variable.");
            throw new BadRequestException("Pipeline configuration is missing required Docker registry password. Please set PIPELINE_DOCKER_PASSWORD environment variable.");
        }
        if (config.repoToken().isEmpty()) {
            Log.errorf("Pipeline configuration is missing required repository token. Please set PIPELINE_REPO_TOKEN environment variable.");
            throw new BadRequestException("Pipeline configuration is missing required repository token. Please set PIPELINE_REPO_TOKEN environment variable.");
        }
        addEnv(createDTO, "USE_BUILDX", config.useBuildx());
        addEnv(createDTO, "BUILDX_PLATFORMS", toJsonArray(config.buildxPlatforms()));
        addEnv(createDTO, "BUILDX_SBOM", config.buildxSbom());
        addEnv(createDTO, "BUILDX_NO_DEFAULT_OCI_ARTIFACT", config.buildxNoDefaultOciArtifacts().orElse(true));
        addOptionalEnv(createDTO, "BUILDX_PROVENANCE_MODE", config.buildxProvenanceMode());
        addEnv(createDTO, "COSIGN_SIGN", config.cosignSign());
        addEnv(createDTO, "DOCKER_LOGIN", config.dockerLogin());
        addEnv(createDTO, "DOCKER_REGISTRY", config.dockerRegistry());
        addEnv(createDTO, "DOCKER_GROUP", config.dockerGroup());
        addEnv(createDTO, "DOCKER_USERNAME", config.dockerUsername());
        addEnv(createDTO, "DOCKER_PASSWORD", config.dockerPassword().get());
        addEnv(createDTO, "REPO_TOKEN", config.repoToken().get());
        addEnv(createDTO, "REPO_PATH", config.repoPath());
    }

    private void addEnv(StartPipelineDTO dto, String key, Object value) {
        dto.addEnvironment(key + "=" + value);
    }

    private void addOptionalEnv(StartPipelineDTO dto, String key, Optional<?> value) {
        value.ifPresent(v -> dto.addEnvironment(key + "=" + v));
    }

    private void ensureNotRunningAlready(StartPipelineDTO createDTO) {
        Log.debugf("Checking if pipeline %d  already exists",
                createDTO.getPipelineId());

        Optional<Container> currentContainer = dockerAppService.getCurrentPipelineContainer(createDTO.getPipelineId());
        Optional<Container> nameContainer = dockerAppService.getByName(createDTO.getContainerName());

        if (currentContainer.isEmpty() && nameContainer.isEmpty()) {
            Log.debug("No existing container found, proceeding with creation");
            return;
        }

        if (currentContainer.isPresent()) {
            dockerCleanupService.cleanupContainer(currentContainer.get().getId(), true, true, true);
        }
        if (nameContainer.isPresent()) {
            dockerCleanupService.cleanupContainer(nameContainer.get().getId(), true, true, true);
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
        dockerCleanupService.cleanupContainer(containerId, true, hardCleanup, true);
    }

    private String toJsonArray(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Failed to serialize pipeline configuration value", e);
        }
    }
}