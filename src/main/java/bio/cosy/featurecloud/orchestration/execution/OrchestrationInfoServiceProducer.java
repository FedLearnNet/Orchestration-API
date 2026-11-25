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
public class OrchestrationInfoServiceProducer {

    @Inject
    @DockerBackend
    OrchestrationInfoService dockerOrchestrationInfoAdapter;

    @Inject
    @KubernetesBackend
    OrchestrationInfoService kubernetesOrchestrationInfoService;

    @ConfigProperty(name = "orch.execution-backend", defaultValue = "docker")
    String backend;

    @Produces
    @ApplicationScoped
    public OrchestrationInfoService orchestrationInfoService() {
        return switch (backend) {
            case "kubernetes" -> kubernetesOrchestrationInfoService;
            case "docker" -> dockerOrchestrationInfoAdapter;
            default -> throw new IllegalStateException(
                    "Unknown orch.execution-backend '" + backend + "' — expected 'docker' or 'kubernetes'");
        };
    }
}
