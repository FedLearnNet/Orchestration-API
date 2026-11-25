package bio.cosy.featurecloud.orchestration.execution;

import bio.cosy.featurecloud.orchestration.docker.DockerVolumeType;

/**
 * Backend-neutral "make sure this run has a volume to write/read from"
 * operation, plus the minimum needed to hand one workflow node's output to
 * the next node's input. See docs/kubernetes-execution-backend.md ("Storage"
 * section) for the design.
 *
 * <p>Deliberately not covered: the rest of {@code DockerVolumeService}'s
 * copy helpers ({@code moveDataToWorkspace}, {@code moveDataToHost} — host
 * bind-mount based, used for browser upload/export, not the workflow-to-workflow
 * handoff this interface covers) and volume deletion.
 */
public interface VolumeExecutionService {

    /**
     * Creates the named volume if it doesn't already exist. Returns the
     * same name it was given — the return type mirrors
     * {@code DockerVolumeService.createVolume}'s call sites, which all just
     * want the name back for chaining into {@code startApplication}.
     */
    String ensureVolume(Long workflowId, DockerVolumeType type, String name);

    /**
     * Whether the named volume currently exists — used to give a clear
     * "previous output volume not found" error before attempting a copy
     * from it, rather than letting that fail deeper in the copy itself.
     */
    boolean volumeExists(String name);

    /**
     * Copies one file from {@code sourceVolumeName} to {@code destVolumeName}
     * under a (possibly different) name, then best-effort chowns/chmods the
     * destination per {@code container.file-transfer.grant-users}/
     * {@code grant-group} — mirrors {@code DockerVolumeService.moveFileToToAnotherContainer}.
     * This is what hands one workflow node's output to the next node's input.
     */
    void copyBetweenVolumes(String sourceVolumeName, String sourceFileName, String destVolumeName, String destFileName);
}
