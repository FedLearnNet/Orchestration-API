package bio.cosy.featurecloud.orchestration.docker;

import bio.cosy.featurecloud.orchestration.helper.NamingService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LabelsHelper {
    public static final String DOCKER_FEATURE_CLOUD_VERSION = "v1";

    public static Map<String, String> createLabels() {
        Map<String, String> maps = new HashMap<>();
        maps.put(DockerLabels.FEATURE_CLOUD_VERSION.toString(), DOCKER_FEATURE_CLOUD_VERSION);
        maps.put(DockerLabels.SYSTEM_NAME.toString(), NamingService.getSystemName());
        return maps;
    }

    public static boolean isOwned(Map<String, String> labels) {
        return labels != null && NamingService.getSystemName().equals(labels.get(DockerLabels.SYSTEM_NAME.toString()));
    }

    public static List<String> toFilters(Map<String, String> labels) {
        return labels.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).toList();
    }

    public static Map<String, String> createGroupLabels(Long id, Long groupId) {
        Map<String, String> maps = createLabels();
        maps.put(DockerLabels.FEATURE_CLOUD_APP_ID.toString(), id.toString());
        maps.put(DockerLabels.FEATURE_CLOUD_GROUP_ID.toString(), groupId.toString());
        return maps;
    }

    public static Map<String, String> createLabelsForStepSearch(String step) {
        Map<String, String> maps = createLabels();
        maps.put(DockerLabels.FEATURE_CLOUD_WORKFLOW_STEP.toString(), step);
        return maps;
    }


    public static Map<String, String> createLabelsForWorkflowNode(Long workflowId, Long workflowNodeId) {
        Map<String, String> maps = createLabelsWorkflow(workflowId);
        maps.put(DockerLabels.FEATURE_CLOUD_WORKFLOW_NODE.toString(), workflowNodeId.toString());
        return maps;
    }

    public static Map<String, String> createLabelsForWorkflowNode(Long workflowNodeId) {
        Map<String, String> maps = createLabels();
        maps.put(DockerLabels.FEATURE_CLOUD_WORKFLOW_NODE.toString(), workflowNodeId.toString());
        return maps;
    }

    public static Map<String, String> createLabelsForPipeline(Long pipelineId) {
        Map<String, String> maps = createLabels();
        maps.put(DockerLabels.FEATURE_CLOUD_PIPELINE.toString(), pipelineId.toString());
        return maps;
    }

    public static Map<String, String> createLabelsWorkflow(Long workflowId) {
        Map<String, String> maps = createLabels();
        maps.put(DockerLabels.FEATURE_CLOUD_WORKFLOW_ID.toString(), workflowId.toString());
        return maps;
    }
}
