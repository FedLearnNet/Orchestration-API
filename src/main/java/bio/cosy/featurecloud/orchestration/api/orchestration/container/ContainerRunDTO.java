package bio.cosy.featurecloud.orchestration.api.orchestration.container;

import bio.cosy.featurecloud.orchestration.base.BaseDTO;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(callSuper = true)
@Data
@RegisterForReflection
@AllArgsConstructor
@NoArgsConstructor
public class ContainerRunDTO extends BaseDTO {
    private Long appId;

    private String appImage;

    private Long workflowId;

    private String containerName;

    private String containerId;

    private ContainerRunStatus status;
}
