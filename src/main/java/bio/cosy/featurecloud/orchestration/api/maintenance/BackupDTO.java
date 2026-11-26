package bio.cosy.featurecloud.orchestration.api.maintenance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.Date;

/**
 * backups/&lt;name&gt;/backup.json 'flnet backup' writes.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BackupDTO {
    static final String MANIFEST = "backup.json";

    private String name;
    private Date createdAt;
    // version of the deployment it was taken from
    private String version;
    // with configuration and secrets, else only the databases
    private boolean backUpAll;
    // false while it runs, or when it failed (message) or was interrupted
    private boolean complete;
    private String message;
    // where it is on the server, set by orch-api
    private String path;
}
