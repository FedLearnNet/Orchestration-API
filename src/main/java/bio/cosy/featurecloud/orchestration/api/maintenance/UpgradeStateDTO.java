package bio.cosy.featurecloud.orchestration.api.maintenance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Date;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpgradeStateDTO {
    private MaintenanceMode step;
    private boolean done;
    private String flNetSoftwareVersion;
    private String lastLogMessage;
    private Date updatedAt;
}
