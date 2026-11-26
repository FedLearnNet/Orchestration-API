package bio.cosy.featurecloud.orchestration.api.maintenance;

import bio.cosy.featurecloud.orchestration.config.UpdaterConfig;
import bio.cosy.featurecloud.orchestration.docker.DockerLabels;
import bio.cosy.featurecloud.orchestration.docker.LabelsHelper;
import bio.cosy.featurecloud.orchestration.helper.LogHelper;
import bio.cosy.featurecloud.orchestration.service.DockerAppService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.Container;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;


@ApplicationScoped
public class MaintenanceProgressBO {

    private static final String BACKUPS_FOLDER = "backups";
    Duration interval = Duration.ofSeconds(1);

    @Inject
    MaintenanceBO maintenanceBO;

    @Inject
    DockerAppService dockerAppService;

    @Inject
    UpdaterConfig config;

    @Inject
    ObjectMapper objectMapper;

    // starting blocks, so it runs on a worker thread
    public Multi<MaintenanceStatusDTO> start(MaintenanceMode mode, String version, boolean all, String backup) {
        return withError(Uni.createFrom().item(() -> maintenanceBO.start(mode, version, all, backup))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onItem().transformToMulti(containerId -> followContainer(containerId, mode)));
    }

    // the status now, followed until flnet ends if it runs
    public Multi<MaintenanceStatusDTO> progress() {
        return withError(Uni.createFrom().item(this::findRunning)
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onItem().transformToMulti(running -> running
                        .map(container -> followContainer(container.getId(), modeOf(container)))
                        .orElseGet(() -> Multi.createFrom().item(this::readStatus))));
    }

    // the last log line as message while flnet runs; its log ends with it, then the status it left
    Multi<MaintenanceStatusDTO> followContainer(String containerId, MaintenanceMode mode) {
        Multi<MaintenanceStatusDTO> running = dockerAppService.getLogById(containerId)
                // gone already, e.g. after a quick failure: what it did is in the files
                .onFailure().recoverWithCompletion()
                .group().intoLists().every(interval)
                .filter(lines -> !lines.isEmpty())
                .emitOn(Infrastructure.getDefaultWorkerPool())
                .map(lines -> buildRunningStatus(mode, LogHelper.lastLine(lines)));
        Multi<MaintenanceStatusDTO> ended = Multi.createFrom().item(this::readStatus)
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
        return Multi.createBy().concatenating().streams(running, ended);
    }

    MaintenanceStatusDTO readStatus() {
        MaintenanceStatusDTO status = new MaintenanceStatusDTO();
        if (config.deploymentDir().isEmpty()) {
            return status;
        }
        status.setEnabled(true);
        status.setLastUpgrade(readLastUpgrade());
        status.setBackups(readBackups());
        return status;
    }

    // updates/state.json, null before the first upgrade
    UpgradeStateDTO readLastUpgrade() {
        Path file = Path.of(config.stateFile());
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(file.toFile(), UpgradeStateDTO.class);
        } catch (IOException e) {
            throw new InternalServerErrorException("Could not read " + file + ": " + e.getMessage());
        }
    }

    // backups/*/backup.json, newest first
    List<BackupDTO> readBackups() {
        Path directory = Path.of(config.backupsDir());
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.map(entry -> entry.resolve(BackupDTO.MANIFEST)).filter(Files::isRegularFile)
                    .map(this::readBackup).flatMap(Optional::stream)
                    .sorted(Comparator.comparing(BackupDTO::getCreatedAt, Comparator.nullsFirst(Comparator.<Date>naturalOrder())).reversed())
                    .toList();
        } catch (IOException e) {
            throw new InternalServerErrorException("Could not read " + directory + ": " + e.getMessage());
        }
    }

    // SSE has sent its 200 once the stream runs, so a failure, e.g. a refused start, ends it as a last status
    private static Multi<MaintenanceStatusDTO> withError(Multi<MaintenanceStatusDTO> stream) {
        return stream.onFailure().recoverWithItem(failure -> {
            Log.warnf("Maintenance failed: %s", failure.getMessage());
            MaintenanceStatusDTO status = new MaintenanceStatusDTO();
            status.setLastError(failure.getMessage());
            return status;
        });
    }

    private Optional<Container> findRunning() {
        return config.deploymentDir().flatMap(directory ->
                dockerAppService.getByLabels(LabelsHelper.createLabelsForMaintenanceCheck(directory)));
    }

    private MaintenanceStatusDTO buildRunningStatus(MaintenanceMode mode, String message) {
        MaintenanceStatusDTO status = readStatus();
        status.setRunning(mode);
        status.setLastLogMessage(message);
        return status;
    }

    private Optional<BackupDTO> readBackup(Path manifest) {
        try {
            BackupDTO backup = objectMapper.readValue(manifest.toFile(), BackupDTO.class);
            // the folder on the server, as an admin finds it
            config.deploymentDir().ifPresent(directory -> backup.setPath(
                    Path.of(directory).resolve(BACKUPS_FOLDER).resolve(manifest.getParent().getFileName()).toString()));
            return Optional.of(backup);
        } catch (IOException e) {
            Log.warnf("Skipping %s: %s", manifest, e.getMessage());
            return Optional.empty();
        }
    }

    private static MaintenanceMode modeOf(Container container) {
        return MaintenanceMode.valueOf(container.getLabels().get(DockerLabels.FL_NET_MAINTENANCE_MODE.toString()));
    }
}
