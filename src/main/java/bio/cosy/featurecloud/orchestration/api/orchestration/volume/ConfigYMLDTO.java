package bio.cosy.featurecloud.orchestration.api.orchestration.volume;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ConfigYMLDTO {
    @NotNull(message = "Content must not be null")
    String content;
    String fileName;
    String fileType;
}
