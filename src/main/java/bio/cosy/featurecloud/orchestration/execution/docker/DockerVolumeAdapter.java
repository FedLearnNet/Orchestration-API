package bio.cosy.featurecloud.orchestration.execution.docker;

import bio.cosy.featurecloud.orchestration.docker.DockerVolumeType;
import bio.cosy.featurecloud.orchestration.execution.DockerBackend;
import bio.cosy.featurecloud.orchestration.execution.VolumeExecutionService;
import bio.cosy.featurecloud.orchestration.service.DockerVolumeService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Wraps the existing, unmodified {@link DockerVolumeService} to satisfy
 * {@link VolumeExecutionService}. No behavioural change from today.
 */
@DockerBackend
@ApplicationScoped
public class DockerVolumeAdapter implements VolumeExecutionService {

    @Inject
    DockerVolumeService dockerVolumeService;

    @Override
    public String ensureVolume(Long workflowId, DockerVolumeType type, String name) {
        return dockerVolumeService.createVolume(workflowId, type, name).getName();
    }

    @Override
    public boolean volumeExists(String name) {
        try {
            return dockerVolumeService.getVolume(name) != null;
        } catch (Exception e) {
            // docker-java's inspectVolumeCmd throws NotFoundException rather than
            // returning null for a missing volume — normalize both to "false"
            // here so callers get one consistent contract.
            return false;
        }
    }

    @Override
    public void copyBetweenVolumes(String sourceVolumeName, String sourceFileName, String destVolumeName, String destFileName) {
        try {
            dockerVolumeService.moveFileToToAnotherContainer(sourceVolumeName, destVolumeName, sourceFileName, destFileName);
        } catch (Exception e) {
            throw new jakarta.ws.rs.BadRequestException(e.getMessage(), e);
        }
    }
}
