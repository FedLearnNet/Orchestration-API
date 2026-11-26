package bio.cosy.featurecloud.orchestration.api.maintenance;

import bio.cosy.featurecloud.orchestration.config.UpdaterConfig;
import bio.cosy.featurecloud.orchestration.docker.LabelsHelper;
import bio.cosy.featurecloud.orchestration.helper.VersionHelper;
import bio.cosy.featurecloud.orchestration.service.DockerAppService;
import bio.cosy.featurecloud.orchestration.service.DockerPullService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;


@ApplicationScoped
public class MaintenanceBO {
    private static final Pattern BACKUP_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    @Inject
    DockerAppService dockerAppService;

    @Inject
    DockerPullService dockerPullService;

    @Inject
    UpdaterConfig config;

    public synchronized String start(MaintenanceMode mode, String version, boolean all, String backup) {
        String directory = config.deploymentDir()
                .orElseThrow(() -> new ClientErrorException("This deployment does not support backups and upgrades yet. " +
                        "Run 'flnet <client|platform> init' once with a current flnet.", Response.Status.CONFLICT));
        if (mode == null || !VersionHelper.isValidVersion(version)) {
            throw new BadRequestException("A mode and a version X.Y.Z, X.Y.Z.dev or X.Y.Z.rcN are required.");
        }
        checkBackupName(mode, backup);
        checkIfRunning();
        String image = config.imagePrefix() + ":" + version;
        dockerPullService.loadUpdaterImage(image);
        return dockerAppService.startFlnet(image, directory,
                LabelsHelper.createLabelsForMaintenance(directory, mode.name()), buildCommand(mode, directory, all, backup));
    }

    private static void checkBackupName(MaintenanceMode mode, String backup) {
        if (mode == MaintenanceMode.RESTORE && (backup == null || !BACKUP_NAME.matcher(backup).matches())) {
            throw new BadRequestException("A restore needs the name of a backup.");
        }
    }

    private void checkIfRunning() {
        if (config.deploymentDir().flatMap(directory ->
                dockerAppService.getByLabels(LabelsHelper.createLabelsForMaintenanceCheck(directory))).isPresent()) {
            throw new ClientErrorException("A backup or upgrade is already running.", Response.Status.CONFLICT);
        }
    }

    private List<String> buildCommand(MaintenanceMode mode, String directory, boolean backupAll, String backup) {
        List<String> command = new ArrayList<>();

        switch (mode) {
            case BACKUP -> command.add("backup");
            case RESTORE -> command.addAll(List.of("restore", backup, "--yes"));
            default -> command.addAll(List.of("upgrade", mode.name().toLowerCase(Locale.ROOT)));
        }

        command.addAll(List.of("--dir", directory));
        if (mode == MaintenanceMode.BACKUP && backupAll) {
            command.add("--all");
        }
        return command;
    }
}
