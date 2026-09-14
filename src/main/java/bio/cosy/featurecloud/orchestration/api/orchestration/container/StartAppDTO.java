package bio.cosy.featurecloud.orchestration.api.orchestration.container;

import bio.cosy.featurecloud.orchestration.helper.BaseName;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class StartAppDTO implements BaseName {

    @NotNull(message = "identifier cannot be null")
    private Long identifier;

    @NotBlank(message = "appImage cannot be blank")
    private String appImage;

    @NotNull(message = "groupId cannot be null")
    private Long groupId;

    private Boolean needsInternetAccess = false;
    private Boolean needsHostAccess = false;
    private List<String> environments = new ArrayList<>();

    public String getBasename() {
        return getContainerName(groupId, identifier);
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
