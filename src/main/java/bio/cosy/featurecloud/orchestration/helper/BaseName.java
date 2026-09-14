package bio.cosy.featurecloud.orchestration.helper;

import bio.cosy.featurecloud.orchestration.docker.DockerVolumeType;

public interface BaseName {

    default String getNetworkHash() {
        return NamingService.getSystemName();
    }

    default String getWorkflowContainerName(Long workflowId, Long workflowNodeId) {
        return getNetworkHash() + "_fc_w" + workflowId + "_n" + workflowNodeId;
    }

    default String getContainerName(Long groupId, Long id) {
        return getNetworkHash() + "_fc_g" + groupId + "_id" + id;
    }

    default String getPipelineName(Long pipelineId) {
        return getNetworkHash() + "_fc_p" + pipelineId;
    }

    default String createInputVolumeName(String containerName) {
        return containerName + "_volume_" + DockerVolumeType.INPUT.toString().toLowerCase();
    }

    default String createOutputVolumeName(String containerName) {
        return containerName + "_volume_" + DockerVolumeType.OUTPUT.toString().toLowerCase();
    }


}
