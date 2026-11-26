package bio.cosy.featurecloud.orchestration.api.maintenance;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MaintenanceStatusDTO {
    private boolean enabled;
    private MaintenanceMode running;
    private String lastLogMessage;
    private String lastError;
    private UpgradeStateDTO lastUpgrade;
    private List<BackupDTO> backups = new ArrayList<>();
}
