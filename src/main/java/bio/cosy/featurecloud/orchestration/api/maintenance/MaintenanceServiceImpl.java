package bio.cosy.featurecloud.orchestration.api.maintenance;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MaintenanceServiceImpl implements MaintenanceService {

    @Inject
    MaintenanceProgressBO progressBO;

    @Override
    public Multi<MaintenanceStatusDTO> status() {
        return progressBO.progress();
    }

    @Override
    public Multi<MaintenanceStatusDTO> start(MaintenanceMode mode, String version, boolean all, String backup) {
        return progressBO.start(mode, version, all, backup);
    }
}
