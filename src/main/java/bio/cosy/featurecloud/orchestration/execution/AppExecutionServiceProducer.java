package bio.cosy.featurecloud.orchestration.execution;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Selects which {@link AppExecutionService} backend is active, driven by
 * {@code orch.execution-backend}. Defaults to {@code docker} — the existing,
 * unmodified behaviour — so this is opt-in, not a breaking change. See
 * {@code docs/kubernetes-execution-backend.md}.
 *
 * <p>{@code DockerExecutionAdapter}/{@code KubernetesExecutionService} are
 * injected via their {@link DockerBackend}/{@link KubernetesBackend}
 * qualifiers rather than by bare type — without a qualifier, both concrete
 * classes would themselves be discovered as unqualified
 * {@code AppExecutionService} beans too, which is ambiguous with this
 * producer for every other injection point in the app.
 */
@ApplicationScoped
public class AppExecutionServiceProducer {

    @Inject
    @DockerBackend
    AppExecutionService dockerExecutionAdapter;

    @Inject
    @KubernetesBackend
    AppExecutionService kubernetesExecutionService;

    @ConfigProperty(name = "orch.execution-backend", defaultValue = "docker")
    String backend;

    @Produces
    @ApplicationScoped
    public AppExecutionService appExecutionService() {
        return switch (backend) {
            case "kubernetes" -> kubernetesExecutionService;
            case "docker" -> dockerExecutionAdapter;
            default -> throw new IllegalStateException(
                    "Unknown orch.execution-backend '" + backend + "' — expected 'docker' or 'kubernetes'");
        };
    }
}
