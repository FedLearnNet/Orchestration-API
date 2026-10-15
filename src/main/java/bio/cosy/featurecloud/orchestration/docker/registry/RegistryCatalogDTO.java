package bio.cosy.featurecloud.orchestration.docker.registry;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;

import java.util.List;

@Data
@RegisterForReflection
public class RegistryCatalogDTO {
    private List<String> repositories;
}
