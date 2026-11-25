package bio.cosy.featurecloud.orchestration.execution.docker;

import bio.cosy.featurecloud.orchestration.api.orchestration.volume.VolumeInfoDTO;
import bio.cosy.featurecloud.orchestration.execution.DockerBackend;
import bio.cosy.featurecloud.orchestration.execution.VolumeAdminService;
import bio.cosy.featurecloud.orchestration.service.DockerVolumeService;

import com.github.dockerjava.api.command.InspectVolumeResponse;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Wraps the existing, unmodified {@link DockerVolumeService} to satisfy
 * {@link VolumeAdminService}. No behavioural change from today — same
 * pattern as {@link DockerVolumeAdapter} for {@link VolumeExecutionService}.
 */
@DockerBackend
@ApplicationScoped
public class DockerVolumeAdminAdapter implements VolumeAdminService {

    @Inject
    DockerVolumeService dockerVolumeService;

    private VolumeInfoDTO toDTO(InspectVolumeResponse v) {
        return VolumeInfoDTO.docker(v.getName(), v.getDriver(), v.getMountpoint(), v.getLabels(), v.getOptions());
    }

    @Override
    public List<VolumeInfoDTO> listVolumes() {
        return dockerVolumeService.listVolumes().stream().map(this::toDTO).toList();
    }

    @Override
    public VolumeInfoDTO getVolume(String name) {
        return toDTO(dockerVolumeService.getVolume(name));
    }

    @Override
    public VolumeInfoDTO createVolume(String name) {
        var created = dockerVolumeService.createVolume(name);
        return VolumeInfoDTO.docker(created.getName(), created.getDriver(), created.getMountpoint(), created.getLabels(), null);
    }

    @Override
    public void uploadFile(String volumeName, Path localFile, String targetFileName) {
        try {
            dockerVolumeService.moveDataToWorkspace(volumeName, localFile, targetFileName);
        } catch (Exception e) {
            throw new InternalServerErrorException("Upload failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Path downloadToLocalDir(String volumeName) {
        try {
            Path tmpDir = Files.createTempDirectory("vol-download-" + volumeName + "-");
            dockerVolumeService.moveDataToHost(volumeName, tmpDir.toAbsolutePath().toString());
            return tmpDir;
        } catch (com.github.dockerjava.api.exception.NotFoundException e) {
            // A missing volume stays a 404 (VolumeServiceImpl maps it), as before.
            throw e;
        } catch (Exception e) {
            throw new InternalServerErrorException("Download failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void moveDataFromInputToOutput(String inputVolumeName, String outputVolumeName) {
        try {
            dockerVolumeService.moveDataFromInputToOutput(inputVolumeName, outputVolumeName);
        } catch (Exception e) {
            Log.errorf("Error moving files from input to output volume: %s", e.getMessage());
            throw new RuntimeException("Error moving files from input to output volume", e);
        }
    }

    @Override
    public Long volumeSize(String name) {
        // Matches VolumeServiceImpl.volumeSize's existing (pre-this-change)
        // behavior of returning null unconditionally — not a regression.
        return null;
    }

    @Override
    public void removeVolume(String name) {
        dockerVolumeService.removeVolume(name);
    }

    @Override
    public void removeAllVolumes() {
        dockerVolumeService.removeAllVolumes();
    }

    @Override
    public void cleanupWorkflowVolumes(Long workflowId) {
        dockerVolumeService.cleanupWorkflowVolumes(workflowId);
    }
}
