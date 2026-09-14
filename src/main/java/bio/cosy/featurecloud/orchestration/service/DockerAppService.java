package bio.cosy.featurecloud.orchestration.service;

import bio.cosy.featurecloud.orchestration.api.orchestration.container.StartAppDTO;
import bio.cosy.featurecloud.orchestration.api.orchestration.container.StartPipelineDTO;
import bio.cosy.featurecloud.orchestration.api.orchestration.workflow.StartWorkflowNodeDTO;
import bio.cosy.featurecloud.orchestration.config.ContainerConfig;
import bio.cosy.featurecloud.orchestration.docker.DockerLabels;
import bio.cosy.featurecloud.orchestration.docker.DockerVolumeType;
import bio.cosy.featurecloud.orchestration.docker.LabelsHelper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.*;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static bio.cosy.featurecloud.orchestration.service.DockerVolumeService.VOLUME_PATH;

@ApplicationScoped
public class DockerAppService {

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerConfig containerConfig;


    public void loadApplicationImage(String imageName) throws Exception {
        Log.infof("Pulling Docker image: %s", imageName);
        try {
            dockerClient.pullImageCmd(imageName)
                    .exec(new PullImageResultCallback())
                    .awaitCompletion();
            Log.infof("Successfully pulled Docker image: %s", imageName);
        } catch (Exception e) {
            Log.errorf("Failed to pull Docker image %s: %s", imageName, e.getMessage());
            throw e;
        }
    }

    public boolean removeApplication(String containerId) {
        Log.infof("Removing container with ID: %s", containerId);
        try {
            dockerClient.removeContainerCmd(containerId).exec();
            Log.infof("Successfully removed container: %s", containerId);
            return true;
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            Log.warnf("Container %s is already removed", containerId);
            return true;
        } catch (Exception e) {
            Log.errorf(e, "Failed to remove container %s: %s", containerId, e.getMessage());
            return false;
        }
    }

    public void stopContainer(String containerId) {
        Log.infof("Stopping container: %s", containerId);
        try {
            dockerClient.stopContainerCmd(containerId).exec();
            Log.infof("Container stopped successfully: %s", containerId);
        } catch (com.github.dockerjava.api.exception.NotModifiedException e) {
            Log.warnf("Container %s is already stopped", containerId);
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            Log.warnf("Container %s is already removed", containerId);
        } catch (Exception e) {
            Log.errorf(e, "Failed to stop container %s", containerId);
            throw e;
        }
    }

    public List<Container> showRunning() {
        Log.info("Listing all running containers");
        List<Container> containers = dockerClient.listContainersCmd()
                .withLabelFilter(LabelsHelper.createLabels()).withShowAll(true).exec();
        Log.infof("Found %d containers (including stopped ones)", containers.size());
        return containers;
    }

    public List<Container> showFeatureCloudContainers() {
        Log.info("Listing all FeatureCloud containers");
        Map<String, String> labels = LabelsHelper.createLabels();
        List<Container> containers = dockerClient.listContainersCmd()
                .withLabelFilter(labels)
                .withShowAll(true)
                .exec();
        Log.infof("Found %d FeatureCloud containers", containers.size());
        return containers;
    }

    public Container getById(String id) {
        Log.infof("Looking for container with ID: %s", id);
        return Optional.ofNullable(dockerClient.listContainersCmd().withIdFilter(List.of(id)).withShowAll(true).exec())
                .filter(containers -> !containers.isEmpty())
                .map(List::getFirst)
                .map(container -> {
                    Log.infof("Found container: %s", id);
                    return container;
                })
                .orElseThrow(() -> {
                    Log.warnf("Container not found with ID: %s", id);
                    return new NotFoundException("Container with ID " + id + " not found");
                });
    }
    public List<Container> getByStepLabel(String step){
        Map<String, String> serviceLabels = LabelsHelper.createLabelsForStepSearch(step);
        Log.infof("Finding containers: labels=%s", serviceLabels);
        return dockerClient.listContainersCmd()
                .withLabelFilter(serviceLabels)
                .withShowAll(true)
                .exec();
    }

    public List<Container> getWorkflowContainers(Long workflowId) {
        Log.infof("Searching for containers in workflow %d", workflowId);
        Map<String, String> serviceLabels = LabelsHelper.createLabelsWorkflow(workflowId);
        Log.infof("Finding containers: labels=%s", serviceLabels);
        List<Container> containers = dockerClient.listContainersCmd()
                .withLabelFilter(serviceLabels)
                .withShowAll(true)
                .exec();
        if (containers != null && !containers.isEmpty()) {
            Log.infof("Found %d containers for workflow %d", containers.size(), workflowId);
            return containers;
        } else {
            Log.infof("No container found for workflow %d", workflowId);
            return List.of();
        }
    }

    public List<Container> getWorkflowNodeContainers(Long workflowNodeId) {
        Log.infof("Searching for containers in workflow node %d", workflowNodeId);
        Map<String, String> serviceLabels = LabelsHelper.createLabelsForWorkflowNode(workflowNodeId);
        Log.infof("Finding containers: labels=%s", serviceLabels);
        List<Container> containers = dockerClient.listContainersCmd()
                .withLabelFilter(serviceLabels)
                .withShowAll(true)
                .exec();
        if (containers != null && !containers.isEmpty()) {
            Log.infof("Found %d containers for workflow node %d", containers.size(), workflowNodeId);
            return containers;
        } else {
            Log.infof("No container found for workflow node %d", workflowNodeId);
            return List.of();
        }
    }

    public Optional<Container> getCurrentContainer(Long id, Long group) {
        Log.infof("Searching for current container in group %d at id %d", group, id);
        Map<String, String> serviceLabels = LabelsHelper.createGroupLabels(id, group);
        Log.infof("Finding containers: labels=%s", serviceLabels);

        List<Container> containers = dockerClient.listContainersCmd()
                .withLabelFilter(serviceLabels)
                .withShowAll(true)
                .exec();

        if (containers != null && !containers.isEmpty()) {
            Log.infof("Found current container %s for group %d",
                    containers.getFirst().getId(), group);
            return Optional.of(containers.getFirst());
        } else {
            Log.infof("No container found for id %d group %d", id, group);
            return Optional.empty();
        }
    }

    public Optional<Container> getCurrentWorkflowNode(Long workflowId, Long workflowNodeId) {
        Log.infof("Searching for current workflow node %d in workflow %d", workflowNodeId, workflowId);
        Map<String, String> serviceLabels = LabelsHelper.createLabelsForWorkflowNode(workflowId, workflowNodeId);
        Log.infof("Finding containers: labels=%s", serviceLabels);

        List<Container> containers = dockerClient.listContainersCmd()
                .withLabelFilter(serviceLabels)
                .withShowAll(true)
                .exec();

        if (containers != null && !containers.isEmpty()) {
            Log.infof("Found current container %s for workflow %d node %d",
                    containers.getFirst().getId(), workflowId, workflowNodeId);
            return Optional.of(containers.getFirst());
        } else {
            Log.infof("No container found for workflow %d node %d", workflowId, workflowNodeId);
            return Optional.empty();
        }
    }

    public Optional<Container> getCurrentPipelineContainer(Long pipelineId) {
        Log.infof("Searching for current container in pipeline %d", pipelineId);
        Map<String, String> serviceLabels = LabelsHelper.createLabelsForPipeline(pipelineId);
        Log.infof("Finding containers: labels=%s", serviceLabels);

        List<Container> containers = dockerClient.listContainersCmd()
                .withLabelFilter(serviceLabels)
                .withShowAll(true)
                .exec();

        if (containers != null && !containers.isEmpty()) {
            Log.infof("Found current container %s for pipeline %d",
                    containers.getFirst().getId(), pipelineId);
            return Optional.of(containers.getFirst());
        } else {
            Log.infof("No container found for pipeline %d", pipelineId);
            return Optional.empty();
        }
    }


    public Optional<Container> getByName(String name) {
        Log.infof("Searching for current container for name %s", name);

        List<Container> containers = dockerClient.listContainersCmd()
                .withNameFilter(List.of(name))
                .withLabelFilter(LabelsHelper.createLabels())
                .withShowAll(true)
                .exec();

        if (containers != null && !containers.isEmpty()) {
            Log.infof("Found current container %s for name %s",
                    containers.getFirst().getId(), name);
            return Optional.of(containers.getFirst());
        } else {
            Log.infof("No container found for name %s", name);
            return Optional.empty();
        }
    }

    public CreateContainerResponse startApplication(
            StartWorkflowNodeDTO dto,
            String inputVolumeName,
            String outputVolumeName,
            boolean autokill) {

        Log.infof("Creating container for node %d in workflow %d",
                dto.getWorkflowNodeId(), dto.getWorkflowId());

        List<Mount> mounts = new ArrayList<>();

        if (StringUtils.isNotBlank(inputVolumeName)) {
            Log.infof("Mounting input volume: %s to path %s",
                    inputVolumeName, VOLUME_PATH.get(DockerVolumeType.INPUT));
            mounts.add(new Mount()
                    .withType(MountType.VOLUME)
                    .withSource(inputVolumeName)
                    .withTarget(VOLUME_PATH.get(DockerVolumeType.INPUT)));
        }

        if (StringUtils.isNotBlank(outputVolumeName)) {
            Log.infof("Mounting output volume: %s to path %s",
                    outputVolumeName, VOLUME_PATH.get(DockerVolumeType.OUTPUT));
            mounts.add(new Mount()
                    .withType(MountType.VOLUME)
                    .withSource(outputVolumeName)
                    .withTarget(VOLUME_PATH.get(DockerVolumeType.OUTPUT)));
        }

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withMounts(mounts)
                .withNetworkMode("none");
                // If we don't set networkMode to none docker auto attaches the
                // default bridge (with internet access etc)
                // We add a custom dedicated network later anyways

        if (Boolean.TRUE.equals(dto.getNeedsHostAccess())) {
            hostConfig.withExtraHosts("host.docker.internal:host-gateway");
        }

        containerConfig.memory().limit().ifPresent(hostConfig::withMemory);
        containerConfig.memory().swap().ifPresent(hostConfig::withMemorySwap);
        containerConfig.cpu().shares().ifPresent(hostConfig::withCpuShares);
        if (autokill) {
            containerConfig.enable().autokill().ifPresent(hostConfig::withAutoRemove);
        }
        containerConfig.enable().oomkillDisable().ifPresent(hostConfig::withOomKillDisable);

        Map<String, String> labels = LabelsHelper.createLabelsForWorkflowNode(dto.getWorkflowId(), dto.getWorkflowNodeId());
        dto.getEnvironments().stream()
                .filter(env -> env.startsWith("APP_ID"))
                .findFirst().ifPresent(env -> {
                    if (env.startsWith("APP_ID=")) {
                        String stepType = env.substring("APP_ID=".length());
                        labels.put(DockerLabels.FEATURE_CLOUD_WORKFLOW_STEP.toString(), stepType);
                    }
                });
        Log.infof("Creating container with ownership labels=%s", labels);

        try {
            Log.infof("Creating container with image: %s, name: %s", dto.getAppImage(), dto.getContainerName());
            CreateContainerResponse container = dockerClient
                    .createContainerCmd(dto.getAppImage())
                    .withLabels(labels)
                    .withName(dto.getContainerName())
                    .withEnv(dto.getEnvironments())
                    // .withEntrypoint("tail", "-f", "/dev/null")
                    .withHostConfig(hostConfig)
                    .exec();
            String containerId = container.getId();
            Log.infof("Starting container with ID: %s", containerId);
            dockerClient.startContainerCmd(containerId).exec();


            Log.infof("Container %s successfully started and running", containerId);
            return container;
        } catch (Exception e) {
            Log.errorf("Failed to create or start container: %s", e.getMessage());
            throw e;
        }
    }

    public CreateContainerResponse startApplication(
            StartAppDTO dto) {

        Log.infof("Creating container for group %d in id %d",
                dto.getGroupId(), dto.getIdentifier());

        HostConfig hostConfig = HostConfig.newHostConfig();
        if (Boolean.TRUE.equals(dto.getNeedsHostAccess())) {
            hostConfig.withExtraHosts("host.docker.internal:host-gateway");
        }
        containerConfig.memory().limit().ifPresent(hostConfig::withMemory);
        containerConfig.memory().swap().ifPresent(hostConfig::withMemorySwap);
        containerConfig.cpu().shares().ifPresent(hostConfig::withCpuShares);
        containerConfig.enable().autokill().ifPresent(hostConfig::withAutoRemove);
        containerConfig.enable().oomkillDisable().ifPresent(hostConfig::withOomKillDisable);

        Map<String, String> labels = LabelsHelper.createGroupLabels(dto.getIdentifier(), dto.getGroupId());
        Log.infof("Creating container with ownership labels=%s", labels);

        try {
            Log.infof("Creating container with image: %s, name: %s", dto.getAppImage(), dto.getContainerName());
            CreateContainerResponse container = dockerClient
                    .createContainerCmd(dto.getAppImage())
                    .withLabels(labels)
                    .withName(dto.getContainerName())
                    .withEnv(dto.getEnvironments())
                    // .withEntrypoint("tail", "-f", "/dev/null")
                    .withHostConfig(hostConfig)
                    .exec();
            String containerId = container.getId();
            Log.infof("Starting container with ID: %s", containerId);
            dockerClient.startContainerCmd(containerId).exec();


            Log.infof("Container %s successfully started and running", containerId);
            return container;
        } catch (Exception e) {
            Log.errorf("Failed to create or start container: %s", e.getMessage());
            throw e;
        }
    }


    public CreateContainerResponse startPipeline(
            StartPipelineDTO dto, Boolean hostRemove) {

        Log.infof("Creating container for pipeline %d",
                dto.getPipelineId());

        List<Mount> mounts = new ArrayList<>();

        Log.infof("Mounting docker socket volume: %s");
        mounts.add(new Mount()
                .withType(MountType.BIND)  // Changed from VOLUME to BIND
                .withSource("/var/run/docker.sock")
                .withTarget("/var/run/docker.sock"));


        HostConfig hostConfig = HostConfig.newHostConfig()
                .withMounts(mounts);

        containerConfig.memory().limit().ifPresent(hostConfig::withMemory);
        containerConfig.memory().swap().ifPresent(hostConfig::withMemorySwap);
        containerConfig.cpu().shares().ifPresent(hostConfig::withCpuShares);
        hostConfig.withAutoRemove(hostRemove == null || hostRemove);
        containerConfig.enable().oomkillDisable().ifPresent(hostConfig::withOomKillDisable);

        Map<String, String> labels = LabelsHelper.createLabelsForPipeline(dto.getPipelineId());
        Log.infof("Creating container with ownership labels=%s", labels);

        try {
            Log.infof("Creating container with image: %s, name: %s", dto.getAppImage(), dto.getContainerName());
            CreateContainerResponse container = dockerClient
                    .createContainerCmd(dto.getAppImage())
                    .withLabels(labels)
                    .withUser("0:0")
                    .withName(dto.getContainerName())
                    .withEnv(dto.getEnvironments())
                    .withHostConfig(hostConfig)
                    .exec();
            String containerId = container.getId();
            Log.infof("Starting container with ID: %s", containerId);
            dockerClient.startContainerCmd(containerId).exec();


            Log.infof("Container %s successfully started and running", containerId);
            return container;
        } catch (Exception e) {
            Log.errorf("Failed to create or start container: %s", e.getMessage());
            throw e;
        }
    }

    public Multi<String> getLogById(String id) {
        Log.infof("Retrieving logs from container: %s", id);
        return Multi.createFrom().emitter(emitter -> {
            try {
                Log.debug("Setting up log streaming connection");
                dockerClient.logContainerCmd(id)
                        .withFollowStream(true)
                        .withSince(200)
                        .withStdOut(true)
                        .withStdErr(true)
                        .exec(new ResultCallback.Adapter<Frame>() {
                            @Override
                            public void onNext(Frame frame) {
                                if (frame != null) {
                                    emitter.emit(new String(frame.getPayload()));
                                }
                            }

                            @Override
                            public void onComplete() {
                                Log.infof("Log streaming completed for container: %s", id);
                                emitter.complete();
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                Log.errorf("Error streaming logs from container %s: %s", id, throwable.getMessage());
                                emitter.fail(throwable);
                            }
                        });
            } catch (Exception e) {
                Log.errorf("Failed to set up log streaming for container %s: %s", id, e.getMessage());
                emitter.fail(e);
            }
        });
    }

    public Boolean isRunning(String containerId) {
        try {
            Container container = getById(containerId);
            return container.getState().equalsIgnoreCase("running");
        } catch (NotFoundException e) {
            Log.warnf("Container with ID %s not found", containerId);
            return false;
        }
    }
}
