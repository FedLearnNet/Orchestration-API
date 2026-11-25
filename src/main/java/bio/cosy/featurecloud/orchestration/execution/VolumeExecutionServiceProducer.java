package bio.cosy.featurecloud.orchestration.execution;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Mirrors {@link AppExecutionServiceProducer} — same {@code orch.execution-backend}
 * property selects both, and the same qualifier reasoning applies.
 */
@ApplicationScoped
public class VolumeExecutionServiceProducer {

    @Inject
    @DockerBackend
    VolumeExecutionService dockerVolumeAdapter;

    @Inject
    @KubernetesBackend
    VolumeExecutionService kubernetesVolumeService;

    @ConfigProperty(name = "orch.execution-backend", defaultValue = "docker")
    String backend;

    @Produces
    @ApplicationScoped
    public VolumeExecutionService volumeExecutionService() {
        return switch (backend) {
            case "kubernetes" -> kubernetesVolumeService;
            case "docker" -> dockerVolumeAdapter;
            default -> throw new IllegalStateException(
                    "Unknown orch.execution-backend '" + backend + "' — expected 'docker' or 'kubernetes'");
        };
    }
}
