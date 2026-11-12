package bio.cosy.featurecloud.orchestration.service;


import bio.cosy.featurecloud.orchestration.docker.client.config.DockerRuntimeConfig;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Statistics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class DockerOrchestration {

    private static final Duration STATS_TIMEOUT = Duration.ofSeconds(5);

    @Inject
    DockerClient dockerClient;

    @Inject
    DockerRuntimeConfig config;

    public Info getInfo() {
        // DockerClientFactory factory = new DockerClientFactory(config.defaultDockerClient());
        //SystemDfCmdExec systemDfCmdExec = new SystemDfCmdExec(factory.);
        return dockerClient.infoCmd().exec();
    }

    /**
     * One-shot stats of all running containers, requested in parallel.
     * Containers not answering within the timeout are skipped.
     */
    public List<Statistics> loadRunningContainerStats() {
        List<StatsCallback> callbacks = dockerClient.listContainersCmd().exec().stream()
                .map(container -> dockerClient.statsCmd(container.getId())
                        .withNoStream(true)
                        .exec(new StatsCallback()))
                .toList();

        long deadline = System.nanoTime() + STATS_TIMEOUT.toNanos();
        List<Statistics> result = new ArrayList<>();
        for (StatsCallback callback : callbacks) {
            try {
                long remaining = Math.max(0, deadline - System.nanoTime());
                if (callback.awaitCompletion(remaining, TimeUnit.NANOSECONDS) && callback.statistics != null) {
                    result.add(callback.statistics);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                closeQuietly(callback);
            }
        }
        return result;
    }

    private void closeQuietly(StatsCallback callback) {
        try {
            callback.close();
        } catch (IOException ignored) {
        }
    }

    private static class StatsCallback extends ResultCallback.Adapter<Statistics> {
        private volatile Statistics statistics;

        @Override
        public void onNext(Statistics statistics) {
            this.statistics = statistics;
        }
    }
}
