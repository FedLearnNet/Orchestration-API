package bio.cosy.featurecloud.orchestration.api.orchestration.workflow.actions;

import bio.cosy.featurecloud.orchestration.helper.BaseName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class WorkflowNodeInputActionDTO implements BaseName {
    @NotBlank(message = "originalFileName cannot be blank")
    private String originalFileName;
    @NotBlank(message = "newFileName cannot be blank")
    private String newFileName;

    @NotNull(message = "inputNodeId cannot be null")
    private Long inputNodeId;
    private boolean isLastUsage = false;

    public String getBasename(Long workflowId) {
        return getWorkflowContainerName(workflowId, inputNodeId);
    }

    public String getContainerName(Long workflowId) {
        String name = getBasename(workflowId);
        name = name.replaceAll("_", "-");
        return name;
    }

    public String getOutputVolumeName(Long workflowId) {
        return createOutputVolumeName(getContainerName(workflowId));
    }

}
