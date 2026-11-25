package bio.cosy.featurecloud.orchestration.health;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Version;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Liveness;


@Liveness
public class DockerHealthCheck implements HealthCheck {

    @Inject
    DockerClient docker;

    // Despite application.properties setting orch.docker.health-check=true,
    // that value was never actually read anywhere — DockerRuntimeConfig's
    // @ConfigMapping uses prefix "quarkus.docker", not "orch.docker", so the
    // property name here was orphaned. Wiring it directly to this check
    // is what makes "disabled" actually work
    // for the Kubernetes execution backend, where there's no Docker daemon
    // to ping at all.
    @ConfigProperty(name = "orch.docker.health-check", defaultValue = "true")
    boolean healthCheckEnabled;

    @Override
    public HealthCheckResponse call() {
        if (!healthCheckEnabled) {
            return HealthCheckResponse.named("docker-host-readiness").up().build();
        }
        long start = System.nanoTime();
        try {
            docker.pingCmd().exec(); // erreichbar?

            Version version;
            try {
                version = docker.versionCmd().exec();
            } catch (Exception ignored) {
                version = null;
            }

            Info info;
            try {
                info = docker.infoCmd().exec();
            } catch (Exception ignored) {
                info = null;
            }

            long ms = (System.nanoTime() - start) / 1_000_000;
            HealthCheckResponseBuilder b = HealthCheckResponse.named("docker-host-readiness")
                    .status(true)
                    .withData("latencyMs", ms);

            if (version != null) {
                b.withData("serverVersion", version.getVersion())
                        .withData("kernelVersion", version.getKernelVersion())
                        .withData("apiVersion", version.getApiVersion());
            }
            if (info != null) {
                b.withData("containersRunning", info.getContainersRunning())
                        .withData("name", info.getName())
                        .withData("driver", info.getDriver());
            }
            return b.build();
        } catch (Exception e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            return HealthCheckResponse.named("docker-host-readiness")
                    .status(false)
                    .withData("latencyMs", ms)
                    .withData("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build();
        }
    }
}
