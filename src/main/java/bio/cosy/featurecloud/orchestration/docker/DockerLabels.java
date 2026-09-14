package bio.cosy.featurecloud.orchestration.docker;

public enum DockerLabels {
    FEATURE_CLOUD_VERSION("featurecloud.version"),
    SYSTEM_NAME("system_name"),
    FEATURE_CLOUD_APP_ID("featurecloud.app.id"),
    FEATURE_CLOUD_GROUP_ID("featurecloud.group.id"),
    FEATURE_CLOUD_APP_VERSION("featurecloud.app.version"),
    FEATURE_CLOUD_WORKFLOW_ID("featurecloud.workflow.id"),
    FEATURE_CLOUD_WORKFLOW_NODE("featurecloud.workflow.node"),
    FEATURE_CLOUD_WORKFLOW_STEP("featurecloud.workflow.step"),
    FEATURE_CLOUD_PIPELINE("featurecloud.pipeline"),
    FEATURE_CLOUD_WORKFLOW_MAX_STEPS("featurecloud.workflow.maxsteps"),
    FEATURE_CLOUD_VOLUME_TYPE("featurecloud.volume.type");

    private String name;

    DockerLabels(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
