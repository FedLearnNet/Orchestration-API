package bio.cosy.featurecloud.orchestration.listener;

import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.subscription.Cancellable;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.time.Duration;

@ApplicationScoped
public class ListenerStartup {
    @Inject
    DockerEventListener dockerEventListener;

    @Inject
    ManagedExecutor executor;

    // Under the Kubernetes backend there is no Docker daemon to listen to,
    // and the events only drive pipeline cleanup, which stays Docker-only.
    @ConfigProperty(name = "orch.execution-backend", defaultValue = "docker")
    String backend;

    private Cancellable subscription;

    void onStartup(@Observes @Priority(3000) StartupEvent event) {
        if (!"docker".equals(backend)) {
            Log.infof("Docker event listener not started (orch.execution-backend=%s)", backend);
            return;
        }
        subscription = dockerEventListener.events()
                .runSubscriptionOn(executor)
                .onFailure().invoke(failure -> Log.warn("Docker event stream failed; reconnecting", failure))
                .onFailure().retry().withBackOff(Duration.ofSeconds(1), Duration.ofSeconds(30)).indefinitely()
                .subscribe().with(dockerEventListener::dispatch,
                        failure -> Log.error("Docker event listener stopped", failure));
    }

    void onShutdown(@Observes @Priority(2000) ShutdownEvent event) {
        if (subscription != null) {
            subscription.cancel();
        }
    }
}
