package bio.cosy.featurecloud.orchestration.service;


import bio.cosy.featurecloud.orchestration.config.ContainerConfig;
import bio.cosy.featurecloud.orchestration.docker.DockerLabels;
import bio.cosy.featurecloud.orchestration.docker.DockerVolumeType;
import bio.cosy.featurecloud.orchestration.docker.LabelsHelper;
import bio.cosy.featurecloud.orchestration.helper.NamingService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateVolumeResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.io.FileUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class DockerVolumeService {

    public static final Map<DockerVolumeType, String> VOLUME_PATH = Map.of(
            DockerVolumeType.INPUT, "/mnt/input",
            DockerVolumeType.OUTPUT, "/mnt/output",
            DockerVolumeType.DATA, "/mnt/data"
    );

    @Inject
    ContainerConfig containerConfig;

    @Inject
    DockerClient dockerClient;

    @Inject
    DockerAppService dockerAppService;


    public List<InspectVolumeResponse> listVolumes() {
        Log.info("Listing all FeatureCloud volumes");
        List<InspectVolumeResponse> volumes = dockerClient.listVolumesCmd()
                .withFilter("label", LabelsHelper.toFilters(LabelsHelper.createLabels()))
                .exec()
                .getVolumes();

        Log.infof("Found %d FeatureCloud volumes", volumes.size());
        return volumes;
    }

    public void cleanupWorkflowVolumes(Long workflowId) {
        Log.infof("Searching for volume in workflow %d", workflowId);
        Map<String, String> serviceLabels = LabelsHelper.createLabelsWorkflow(workflowId);
        List<String> filters = LabelsHelper.toFilters(serviceLabels);
        Log.infof("Finding workflow volumes for cleanup: labels=%s", serviceLabels);

        List<InspectVolumeResponse> volumes = dockerClient.listVolumesCmd()
                .withDanglingFilter(true)
                .withFilter("label", filters)
                .exec()
                .getVolumes();

        if (volumes != null && !volumes.isEmpty()) {
            for (InspectVolumeResponse volume : volumes) {
                Log.infof("Found volume %s for workflow %d", volume.getName(), workflowId);
                removeVolume(volume.getName());
                Log.infof("volume %s for workflow %d removed", volume.getName(), workflowId);
            }
        } else {
            Log.infof("No volumes found for workflow %d", workflowId);
        }
    }


    public InspectVolumeResponse getVolume(String name) {
        Log.infof("Inspecting volume: %s", name);
        InspectVolumeResponse volume = dockerClient.inspectVolumeCmd(name).exec();
        Log.debugf("Volume details: name=%s, driver=%s", volume.getName(), volume.getDriver());
        return volume;
    }

    public void removeVolume(String name) {
        try {
            InspectVolumeResponse volume = dockerClient.inspectVolumeCmd(name).exec();
            if (!LabelsHelper.isOwned(volume.getLabels())) {
                Log.warnf("Refusing volume removal: volume=%s expectedSystem=%s actualSystem=%s", name,
                        NamingService.getSystemName(), volume.getLabels() == null ? null
                                : volume.getLabels().get(DockerLabels.SYSTEM_NAME.toString()));
                return;
            }
            Log.infof("Removing volume=%s system_name=%s", name, NamingService.getSystemName());
            dockerClient.removeVolumeCmd(name)
                    .exec();
            Log.infof("Successfully removed volume: %s", name);
        } catch (NotFoundException e) {
            Log.warnf("Volume %s is already removed (system_name=%s)", name, NamingService.getSystemName());
        } catch (Exception e) {
            Log.warnf("Failed to remove volume %s: %s", name, e.getMessage());
        }
    }

    public void removeAllVolumes() {
        Log.info("Removing all FeatureCloud volumes");
        List<InspectVolumeResponse> volumes = listVolumes();
        Log.infof("Found %d volumes to remove", volumes.size());

        for (InspectVolumeResponse vol : volumes) {
            removeVolume(vol.getName());
        }

        Log.info("Volume cleanup completed");
    }


    public CreateVolumeResponse createVolume(String volumeName) {
        Log.infof("Creating new volume with name: %s", volumeName);
        CreateVolumeResponse volume = dockerClient.createVolumeCmd()
                .withLabels(LabelsHelper.createLabels())
                .withName(volumeName)
                .exec();
        Log.infof("Volume created successfully: %s", volumeName);
        return volume;
    }

    public CreateVolumeResponse createVolume(Long workflowId, DockerVolumeType dockerVolumeType, String name) {
        Log.infof("Creating new %s volume '%s' for workflow: %d", dockerVolumeType, name, workflowId);

        Map<String, String> labels = LabelsHelper.createLabelsWorkflow(workflowId);
        labels.put(DockerLabels.FEATURE_CLOUD_VOLUME_TYPE.toString(), dockerVolumeType.toString());

        CreateVolumeResponse volume = dockerClient.createVolumeCmd()
                .withName(name)
                .withLabels(labels)
                .exec();

        Log.infof("Volume '%s' created successfully for workflow %d", name, workflowId);
        return volume;
    }


    public void moveDataFromInputToOutput(String volumeName, String outputVolumeName) throws Exception {
        if (volumeName == null || volumeName.isEmpty()) {
            Log.warn("Cannot move data: source volume name is empty");
            throw new Exception("volumeName is empty");
        }

        Log.infof("Moving data from volume '%s' to output volume '%s'", volumeName, outputVolumeName);

        String inputPath = VOLUME_PATH.get(DockerVolumeType.INPUT) + "/.";
        String outputPath = VOLUME_PATH.get(DockerVolumeType.OUTPUT) + "/";

        String[] cmd = buildCopyWithChownCmd(inputPath, outputPath);

        String moveDataImage = "alpine:latest";
        Log.infof("Using image '%s' for data migration", moveDataImage);

        dockerAppService.loadApplicationImage(moveDataImage);
        String containerId = startApplication(moveDataImage, volumeName, outputVolumeName, null, cmd);

        if (dockerAppService.removeApplication(containerId)) {
            Log.info("Successfully removed intermediary container used for data migration");
        } else {
            Log.warn("Failed to remove intermediary container after data migration");
        }
    }

    public void moveFileToToAnotherContainer(String volumeName, String outputVolumeName, String prevName, String newName) throws Exception {
        if (volumeName == null || volumeName.isEmpty()) {
            Log.warn("Cannot move data: source volume name is empty");
            throw new Exception("volumeName is empty");
        }

        Log.infof("Moving data from volume '%s' to output volume '%s'", volumeName, outputVolumeName);

        String inputPath = VOLUME_PATH.get(DockerVolumeType.INPUT) + "/" + prevName;
        String outputPath = VOLUME_PATH.get(DockerVolumeType.OUTPUT) + "/" + newName;

        String[] cmd = buildCopyWithChownCmd(inputPath, outputPath);
        String moveDataImage = "alpine:latest";
        Log.infof("Using image '%s' for data migration", moveDataImage);

        dockerAppService.loadApplicationImage(moveDataImage);
        String containerId = startApplication(moveDataImage, volumeName, outputVolumeName, null, cmd);

        if (dockerAppService.removeApplication(containerId)) {
            Log.info("Successfully removed intermediary container used for data migration");
        } else {
            Log.warn("Failed to remove intermediary container after data migration");
        }
    }

    public void moveDataToWorkspace(String volumeName, Path file, String outputFilename) throws Exception {
        if (volumeName == null || volumeName.isEmpty()) {
            Log.warn("Cannot move data to workspace: volume name is empty");
            throw new Exception("volumeName is empty");
        }
        if (!file.toFile().exists()) {
            Log.warn("Cannot move data to workspace: file does not exist");
            throw new Exception("File does not exist: " + file.toAbsolutePath());
        }
        String fileName = file.getFileName().toString();
        Path hostDir = file.getParent();
        if (containerConfig.fileTransferVolume().path().isPresent()) {
            Path targetDir = Paths.get(containerConfig.fileTransferVolume().path().get());
            try {
                Files.createDirectories(targetDir);
                Path target = targetDir.resolve(file.getFileName());
                Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                chmodReadableForAll(target);
                Log.infof("Successfully moved data to workspace '%s'", fileName);
            } catch (IOException e) {
                Log.errorf(e, "Failed to move file '%s' to '%s'", file, targetDir);
                throw new Exception("Failed to move file: " + e.getMessage(), e);
            }
        }
        Log.infof("Moving file '%s' to volume workspace '%s'", fileName, volumeName);


        String source = VOLUME_PATH.get(DockerVolumeType.DATA);
        if (containerConfig.fileTransferVolume().path().isPresent()) {
            source = containerConfig.fileTransferVolume().path().get();
        }
        String inputPath = source + "/" + fileName;
        String outputPath = VOLUME_PATH.get(DockerVolumeType.OUTPUT) + "/" + outputFilename;

        String[] cmd = buildCopyWithChownCmd(inputPath, outputPath);

        String moveDataImage = "alpine:latest";
        Log.debugf("Using image '%s' for file transfer operation", moveDataImage);

        dockerAppService.loadApplicationImage(moveDataImage);
        String containerId;
        if (containerConfig.fileTransferVolume().name().isPresent()) {
            Log.infof("Using container volume '%s' for file transfer", containerConfig.fileTransferVolume().name().get());
            containerId = startApplication(moveDataImage, containerConfig.fileTransferVolume().name().get(), volumeName, null, cmd);
        } else {
            containerId = startApplication(moveDataImage, null, volumeName, hostDir.toAbsolutePath().toString(), cmd);
        }

        if (dockerAppService.removeApplication(containerId)) {
            Log.info("Successfully removed intermediary container after file transfer");
        } else {
            Log.warn("Failed to remove intermediary container after file transfer");
        }

        if (containerConfig.fileTransferVolume().path().isPresent()) {
            Log.infof("removing temp file from transfer volume path '%s'", containerConfig.fileTransferVolume().path().get());
            Path targetDir = Paths.get(containerConfig.fileTransferVolume().path().get());
            Path target = targetDir.resolve(file.getFileName());
            Files.deleteIfExists(target);
        }
    }


    public void moveDataToHost(String volumeName, String hostDataPath) throws Exception {
        if (volumeName == null || volumeName.isEmpty()) {
            Log.warn("Cannot move data to host: volume name is empty");
            throw new Exception("volumeName is empty");
        }
        // Docker can create a missing named volume when the transfer container mounts it.
        // Inspect first so a typo cannot silently export a new, empty volume.
        dockerClient.inspectVolumeCmd(volumeName).exec();

        String copyTo = VOLUME_PATH.get(DockerVolumeType.DATA) + "/";
        Path hostDir = Paths.get(hostDataPath);
        String hostDirName = hostDir.getFileName().toString();
        if (containerConfig.fileTransferVolume().path().isPresent()) {
            Path targetDir = Paths.get(containerConfig.fileTransferVolume().path().get(), hostDirName);
            copyTo = VOLUME_PATH.get(DockerVolumeType.OUTPUT) + "/" + hostDirName + "/";
            try {
                Files.createDirectories(targetDir);
                Log.infof("Create temp folder on transfer Volume '%s'", targetDir.toAbsolutePath().toString());
            } catch (IOException e) {
                throw new Exception("Create temp folder on transfer Volume: " + e.getMessage(), e);
            }
        }


        Log.infof("Moving data from volume '%s' to host path '%s'", volumeName, hostDataPath);

        String inputPath = VOLUME_PATH.get(DockerVolumeType.INPUT) + "/.";
        String outputPath = copyTo;

        String[] cmd = buildCopyWithChownCmd(inputPath, outputPath);

        String moveDataImage = "alpine:latest";
        Log.debugf("Using image '%s' for data export operation", moveDataImage);

        dockerAppService.loadApplicationImage(moveDataImage);
        String containerId;
        if (containerConfig.fileTransferVolume().path().isEmpty()) {
            containerId = startApplication(moveDataImage, volumeName, "", hostDataPath, cmd);
        } else if (containerConfig.fileTransferVolume().name().isPresent()) {
            Log.infof("Move to intermedia folder");
            containerId = startApplication(moveDataImage, volumeName, containerConfig.fileTransferVolume().name().get(), null, cmd);
            try {
                Path targetDir = Paths.get(containerConfig.fileTransferVolume().path().get(), hostDirName);
                Path sourceDir = Paths.get(hostDataPath);
                FileUtils.copyDirectory(targetDir.toFile(), sourceDir.toFile());
                Log.infof("Successfully moved files from %s host path: %s",
                        targetDir.toAbsolutePath().toString(),
                        hostDataPath);
                FileUtils.deleteDirectory(targetDir.toFile());
                Log.infof("Successfully deleted intermediary container after file transfer");
            } catch (IOException e) {
                Log.errorf("Failed to move files to host path: %s", e.getMessage());
                throw new Exception("Failed to move files to host: " + e.getMessage(), e);
            }
        } else {
            Log.errorf("Cannot move data to host: volume name is empty");
            return;
        }
        if (dockerAppService.removeApplication(containerId)) {
            Log.info("Successfully removed intermediary container after data export");
        } else {
            Log.warn("Failed to remove intermediary container after data export");
        }
    }


    public void deleteVolume(String volumeName) {
        Log.infof("Deleting volume: %s", volumeName);
        try {
            dockerClient.removeVolumeCmd(volumeName).exec();
            Log.infof("Successfully deleted volume: %s", volumeName);
        } catch (Exception e) {
            Log.errorf("Failed to delete volume %s: %s", volumeName, e.getMessage());
        }
    }

    // Only used for the small utility containers, purely internal
    private String startApplication(
            String imageName,
            String inputVolumeName,
            String outputVolumeName,
            String hostDataPath,
            String[] cmdArray) {

        Log.debugf("Starting utility container with image %s for volume operation", imageName);
        List<Mount> mounts = new ArrayList<>();

        // Host directory (for upload/download)
        if (hostDataPath != null && !hostDataPath.isEmpty()) {
            Log.debugf("Mounting host path: %s to container path: %s",
                    hostDataPath, VOLUME_PATH.get(DockerVolumeType.DATA));
            mounts.add(new Mount()
                    .withType(MountType.BIND)
                    .withSource(hostDataPath)
                    .withTarget(VOLUME_PATH.get(DockerVolumeType.DATA))
                    .withReadOnly(false));
        }

        // Named Docker Volume for Input (download use-case)
        if (inputVolumeName != null && !inputVolumeName.isEmpty()) {
            Log.debugf("Mounting input volume: %s to container path: %s",
                    inputVolumeName, VOLUME_PATH.get(DockerVolumeType.INPUT));
            mounts.add(new Mount()
                    .withType(MountType.VOLUME)
                    .withSource(inputVolumeName)
                    .withTarget(VOLUME_PATH.get(DockerVolumeType.INPUT)));
        }

        // Named Docker Volume for Output (upload use-case)
        if (outputVolumeName != null && !outputVolumeName.isEmpty()) {
            Log.debugf("Mounting output volume: %s to container path: %s",
                    outputVolumeName, VOLUME_PATH.get(DockerVolumeType.OUTPUT));
            mounts.add(new Mount()
                    .withType(MountType.VOLUME)
                    .withSource(outputVolumeName)
                    .withTarget(VOLUME_PATH.get(DockerVolumeType.OUTPUT)));
        }

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withMounts(mounts);

        Log.debugf("Executing command: %s", String.join(" ", cmdArray));
        CreateContainerResponse container = dockerClient
                .createContainerCmd(imageName)
                .withHostConfig(hostConfig)
                .withEntrypoint("sh", "-c")
                .withCmd(String.join(" ", cmdArray))
                .exec();

        String containerId = container.getId();
        Log.infof("Starting utility container: %s", containerId);

        try {
            dockerClient.startContainerCmd(containerId).exec();
        } catch (Exception e) {
            Log.errorf(e, "Failed to start utility container %s (image=%s)", containerId, imageName);
            throw e;
        }

        Log.infof("Container %s started; waiting for completion…", containerId);
        int statusCode = dockerClient
                .waitContainerCmd(containerId)
                .exec(new WaitContainerResultCallback())
                .awaitStatusCode();

        Log.infof("File-transfer utility completed: container=%s exitCode=%d", containerId, statusCode);

        if (statusCode != 0) {
            // Retrieve and log container output for debugging
            try {
                ByteArrayOutputStream logStream = new ByteArrayOutputStream();
                dockerClient.logContainerCmd(containerId)
                        .withStdOut(true)
                        .withStdErr(true)
                        .withTimestamps(true)
                        .exec(new ResultCallback.Adapter<Frame>() {
                            @Override
                            public void onNext(Frame frame) {
                                try {
                                    logStream.write(frame.getPayload());
                                } catch (IOException ioe) {
                                    Log.warnf("Error reading log frame: %s", ioe.getMessage());
                                }
                                super.onNext(frame);
                            }
                        })
                        .awaitCompletion();

                String containerLogs = logStream.toString(StandardCharsets.UTF_8);
                Log.errorf("Logs from failed container %s:\n%s", containerId, containerLogs);
            } catch (InterruptedException e) {
                Log.errorf("Failed to retrieve logs for container %s: %s", containerId, e.getMessage());
            }
        }
        return containerId;
    }

    private String[] buildCopyWithChownCmd(String inputPath, String outputPath) {
        if (inputPath == null || inputPath.isBlank()) {
            throw new IllegalArgumentException("inputPath is empty");
        }
        if (outputPath == null || outputPath.isBlank()) {
            throw new IllegalArgumentException("outputPath is empty");
        }

        String in = inputPath.replace("\"", "\\\"");
        String out = outputPath.replace("\"", "\\\"");

        String chownCmd = getChownUserCommand(outputPath).orElse(":");

        return new String[]{
                "sh", "-c",
                "set -e; " +
                        "cp -a \"" + in + "\" \"" + out + "\"; " +
                        chownCmd
        };
    }

    private void chmodReadableForAll(Path p) {
        try {
            if (Files.getFileAttributeView(p, PosixFileAttributeView.class) == null) return;
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-rw-rw-")); // 644
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private Optional<String> getChownUserCommand(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }

        List<String> users = containerConfig.fileTransfer().grantUsers()
                .map(list -> list.stream().filter(s -> s != null && !s.isBlank()).toList())
                .orElse(List.of());

        List<String> groups = containerConfig.fileTransfer().grantGroup()
                .map(list -> list.stream().filter(s -> s != null && !s.isBlank()).toList())
                .orElse(List.of());

        if (users.isEmpty()) {
            return Optional.empty();
        }
        String defaultGroup = groups.isEmpty() ? null : groups.get(0);

        List<String> parts = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            String u = users.get(i);

            String g = null;
            if (!groups.isEmpty()) {
                g = (i < groups.size()) ? groups.get(i) : defaultGroup;
            }
            String target = "\"" + path.replace("\"", "\\\"") + "\"";
            if (g == null || g.isBlank()) {
                parts.add("chown -R " + u + " " + target + " 2>/dev/null || true");
            } else {
                parts.add("chown -R " + u + ":" + g + " " + target + " 2>/dev/null || true");
            }
            parts.add("chmod -R 777 " + target + " 2>/dev/null || true");
        }
        String command = String.join("; ", parts);
        Log.infof("Create chown command for users %s and groups %s: %s", users, groups, command);
        return Optional.of(command);
    }
}
