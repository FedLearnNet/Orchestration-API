package bio.cosy.featurecloud.orchestration.api.logging;

import bio.cosy.featurecloud.orchestration.base.BaseDTO;
import io.quarkus.hibernate.orm.panache.common.ProjectedFieldName;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@RegisterForReflection
public class ContainerLogDTO  extends BaseDTO {
    private Long appId;

    private String appImage;

    private Long workflowId;

    private Long workflowStep;

    private Long workflowMaxSteps;

    private String containerName;

    private String containerId;

    String log;

    public ContainerLogDTO(
            @ProjectedFieldName("containerRun.appId") Long appId,
            @ProjectedFieldName("containerRun.appImage") String appImage,
            @ProjectedFieldName("containerRun.workflowId") Long workflowId,
            @ProjectedFieldName("containerRun.containerName") String containerName,
            @ProjectedFieldName("containerRun.containerId") String containerId,
            String log
    ) {
        this.appId = appId;
        this.appImage = appImage;
        this.workflowId = workflowId;
        this.containerName = containerName;
        this.containerId = containerId;
        this.log = log;
    }
}
