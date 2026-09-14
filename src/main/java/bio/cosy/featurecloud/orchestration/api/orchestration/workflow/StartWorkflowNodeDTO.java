package bio.cosy.featurecloud.orchestration.api.orchestration.workflow;

import bio.cosy.featurecloud.orchestration.api.orchestration.workflow.actions.WorkflowNodeInputActionDTO;
import bio.cosy.featurecloud.orchestration.helper.BaseName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class StartWorkflowNodeDTO implements BaseName {

    @NotNull(message = "workflowNodeId cannot be null")
    private Long workflowNodeId;

    @NotBlank(message = "appImage cannot be blank")
    private String appImage;

    @NotNull(message = "workflowId cannot be null")
    private Long workflowId;

    private List<WorkflowNodeInputActionDTO> inputs = new ArrayList<>();

    private Boolean isFistNode = false;
    private Boolean isLastNode = false;

    //Network settings
    private Boolean needsInternetAccess = false;
    private Boolean needsHostAccess = false;
    private Boolean needsFederatedLearningAccess = false;

    private Boolean enableRemoteResultSaving = false;

    private List<String> environments = new ArrayList<>();

    public String getBasename() {
        return getWorkflowContainerName(workflowId, workflowNodeId);
    }

    public String getContainerName() {
        String name = getBasename();
        name = name.replaceAll("_", "-");
        return name;
    }

    public String getInputVolumeName() {
        return createInputVolumeName(getContainerName());
    }

    public String getOutputVolumeName() {
        return createOutputVolumeName(getContainerName());
    }


    public void addEnvironment(String env) {
        if (env != null && !env.isBlank()) {
            environments.add(env);
        }
    }
}
