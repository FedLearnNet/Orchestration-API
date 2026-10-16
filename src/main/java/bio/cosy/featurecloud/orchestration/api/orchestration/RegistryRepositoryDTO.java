package bio.cosy.featurecloud.orchestration.api.orchestration;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RegistryRepositoryDTO {
    private String name;
    private List<String> tags;
}
