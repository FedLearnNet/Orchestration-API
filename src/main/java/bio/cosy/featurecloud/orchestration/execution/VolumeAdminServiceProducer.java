package bio.cosy.featurecloud.orchestration.execution;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Mirrors {@link VolumeExecutionServiceProducer} — same
 * {@code orch.execution-backend} property selects both.
 */
@ApplicationScoped
public class VolumeAdminServiceProducer {

    @Inject
    @DockerBackend
    VolumeAdminService dockerVolumeAdminAdapter;

    @Inject
    @KubernetesBackend
    VolumeAdminService kubernetesVolumeAdminService;

    @ConfigProperty(name = "orch.execution-backend", defaultValue = "docker")
    String backend;

    @Produces
    @ApplicationScoped
    public VolumeAdminService volumeAdminService() {
        return switch (backend) {
            case "kubernetes" -> kubernetesVolumeAdminService;
            case "docker" -> dockerVolumeAdminAdapter;
            default -> throw new IllegalStateException(
                    "Unknown orch.execution-backend '" + backend + "' — expected 'docker' or 'kubernetes'");
        };
    }
}
