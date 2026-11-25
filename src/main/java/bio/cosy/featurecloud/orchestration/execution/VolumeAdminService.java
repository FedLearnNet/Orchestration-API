package bio.cosy.featurecloud.orchestration.execution;

import bio.cosy.featurecloud.orchestration.api.orchestration.volume.VolumeInfoDTO;

import java.nio.file.Path;
import java.util.List;

/**
 * Backend for the admin-facing {@code /volume/*} REST API (listing,
 * creating, uploading/downloading arbitrary files, removing volumes,
 * cleaning up a whole workflow's volumes). Deliberately separate from
 * {@link VolumeExecutionService}, which only covers what the FL/ETL run
 * flow itself needs (ensure/exists/copy-between-volumes) — this interface
 * covers the wider Docker-only admin surface identified in
 * docs/kubernetes-execution-backend.md ("Still not covered" #5 and #6).
 */
public interface VolumeAdminService {

    List<VolumeInfoDTO> listVolumes();

    VolumeInfoDTO getVolume(String name);

    VolumeInfoDTO createVolume(String name);

    /**
     * Copies a local file into the named volume, at the volume's OUTPUT
     * path (matching DockerVolumeService.moveDataToWorkspace's existing
     * behavior) under {@code targetFileName}.
     */
    void uploadFile(String volumeName, Path localFile, String targetFileName);

    /**
     * Copies the volume's INPUT path contents into a fresh local temp
     * directory and returns it, for the REST layer to zip up (matching
     * DockerVolumeService.moveDataToHost's existing behavior).
     */
    Path downloadToLocalDir(String volumeName);

    void moveDataFromInputToOutput(String inputVolumeName, String outputVolumeName);

    /**
     * Best-effort — returns null when the backend can't report size
     * cheaply (matches today's Docker behavior: VolumeServiceImpl.volumeSize
     * already returns null unconditionally, this isn't a regression).
     */
    Long volumeSize(String name);

    void removeVolume(String name);

    void removeAllVolumes();

    void cleanupWorkflowVolumes(Long workflowId);
}
