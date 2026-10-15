package bio.cosy.featurecloud.orchestration.listener;

import bio.cosy.featurecloud.orchestration.docker.DockerLabels;
import bio.cosy.featurecloud.orchestration.docker.LabelsHelper;
import bio.cosy.featurecloud.orchestration.events.DockerEvents;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventType;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import io.vertx.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;

import static bio.cosy.featurecloud.orchestration.docker.LabelsHelper.PIPELINE_VERSION;

@ApplicationScoped
public class DockerEventListener {

    @Inject
    DockerClient dockerClient;

    @Inject
    EventBus bus;

    public Multi<Event> events() {
        return Multi.createFrom().emitter(emitter -> {
            try {
                Map<String, String> labels = LabelsHelper.createLabels();
                //TODO ONLY FOR NOW
                labels.put(DockerLabels.FEATURE_CLOUD_PIPELINE_VERSION.toString(), PIPELINE_VERSION);
                dockerClient.eventsCmd()
                        .withEventTypeFilter(EventType.CONTAINER)
                        .withLabelFilter(labels)
                        //TODO ONLY FOR NOW
                        .withEventFilter("die")
                        .exec(new ResultCallback.Adapter<Event>() {
                            @Override
                            public void onStart(Closeable stream) {
                                super.onStart(stream);
                                emitter.onTermination(() -> {
                                    try {
                                        close();
                                    } catch (IOException e) {
                                        Log.warn("Failed to close Docker event stream", e);
                                    }
                                });
                            }

                            @Override
                            public void onNext(Event event) {
                                if (event != null) {
                                    emitter.emit(event);
                                }
                            }

                            @Override
                            public void onError(Throwable failure) {
                                if (!emitter.isCancelled()) {
                                    emitter.fail(failure);
                                }
                            }

                            @Override
                            public void onComplete() {
                                onError(new IOException("Docker event stream ended"));
                            }
                        });
            } catch (Exception e) {
                emitter.fail(e);
            }
        });
    }

    public void dispatch(Event event) {
        if (event == null || event.getActor() == null) {
            return;
        }
        String id = event.getActor().getId();
        if (StringUtils.isEmpty(id)) {
            id = event.getId();
        }
        if (StringUtils.isEmpty(id)) {
            return;
        }
        bus.send(DockerEvents.CONTAINER_DIED, id);
    }

}
