package bio.cosy.featurecloud.orchestration.api.orchestration.container;

import bio.cosy.featurecloud.orchestration.helper.BaseName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class StartPipelineDTO implements BaseName {

    @NotNull(message = "pipelineId cannot be null")
    private Long pipelineId;

    @NotBlank(message = "appImage cannot be blank")
    private String appImage;

    private List<String> environments = new ArrayList<>();

    public String getBasename() {
        return getPipelineName(pipelineId);
    }

    public String getContainerName() {
        String name = getBasename();
        name = name.replaceAll("_", "-");
        return name;
    }

    public void addEnvironment(String env) {
        if (env != null && !env.isBlank()) {
            environments.add(env);
        }
    }
}
