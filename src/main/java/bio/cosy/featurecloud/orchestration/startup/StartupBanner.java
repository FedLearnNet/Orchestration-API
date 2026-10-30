package bio.cosy.featurecloud.orchestration.startup;

import bio.cosy.featurecloud.orchestration.config.ContainerConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Locale;

@ApplicationScoped
public class StartupBanner {

    private static final Logger LOG = Logger.getLogger(StartupBanner.class);

    @ConfigProperty(name = "quarkus.application.version")
    String version;

    @Inject
    ContainerConfig containerConfig;

    void onStartup(@Observes @Priority(1) StartupEvent event) {
        if (version.toLowerCase(Locale.ROOT).endsWith("dev")) {
            LOG.warnf("DEVELOPMENT BUILD: version %s ends in 'dev' and may be unstable. "
                    + "Do not use it for production deployments.", version);
        }
        if (containerConfig.network().alwaysBridge()) {
            LOG.warn("NETWORK ISOLATION OVERRIDE: CONTAINER_NETWORK_ALWAYS_BRIDGE=true "
                    + "(container.network.always-bridge). Containers join configured non-internal networks "
                    + "even when their access flags deny internet access, which may also expose the host. "
                    + "Use this setting only for local development.");
        }
    }
}
