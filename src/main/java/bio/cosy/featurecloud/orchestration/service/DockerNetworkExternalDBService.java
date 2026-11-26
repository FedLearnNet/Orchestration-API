package bio.cosy.featurecloud.orchestration.service;

import bio.cosy.featurecloud.orchestration.config.ContainerConfig;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class DockerNetworkExternalDBService {
    public static final String EXTERNAL_SOURCE_ALIAS = "flnet-db";
    private static final Pattern ADDRESS = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.-]*:([0-9]{1,5})$");
    private static final Pattern FORWARDER = Pattern.compile("^/?fl-([0-9a-f]{64})-db-forwarder$");

    @Inject
    DockerClient dockerClient;

    @Inject
    DockerNetworkService dockerNetworkService;

    @Inject
    ContainerConfig containerConfig;

    void onStartup(@Observes StartupEvent event) {
        if (!containerConfig.network().cleanupOrphans()) {
            return;
        }
        try {
            removeOrphanedForwarders();
        } catch (Exception ex) {
            // Docker being unreachable must not keep the API from starting
            Log.warnf(ex, "Failed to remove orphaned forwarders: %s", ex.getMessage());
        }
    }

    /**
     * Gives the app of a container an allowed external database and nothing else: a database container is
     * joined to its dedicated network, an address outside docker (host:port) is relayed by a forwarder
     * the app reaches as {@value #EXTERNAL_SOURCE_ALIAS}.
     */
    public void connectExternalSource(String containerId, String externalSource) {
        if (StringUtils.isBlank(externalSource)) {
            return;
        }
        if (!containerConfig.network().externalSources().orElse(List.of()).contains(externalSource)) {
            throw new IllegalStateException("External source '" + externalSource
                    + "' is not listed in 'container.network.external-sources'");
        }
        boolean address = ADDRESS.matcher(externalSource).matches();
        String participant = address ? startForwarder(containerId, externalSource) : externalSource;
        List<String> aliases = address ? List.of(EXTERNAL_SOURCE_ALIAS) : List.of();
        String prefix = "fl-" + containerId + "-network-";
        for (Network network : dockerClient.listNetworksCmd().exec()) {
            if (network.getName() != null && network.getName().startsWith(prefix)) {
                dockerNetworkService.connectContainerToNetwork(participant, network.getId(), aliases);
            }
        }
    }

    public void removeForwarder(String containerId) {
        try {
            dockerClient.removeContainerCmd(forwarderName(containerId)).withForce(true).exec();
            Log.infof("Removed the forwarder of container %s", containerId);
        } catch (NotFoundException ex) {
            Log.debugf("Container %s has no forwarder", containerId);
        } catch (DockerException ex) {
            Log.warnf("Failed to remove the forwarder of container %s: %s", containerId, ex.getMessage());
        }
    }

    public void removeOrphanedForwarders() {
        for (var container : dockerClient.listContainersCmd().withShowAll(true).exec()) {
            for (String name : Objects.requireNonNullElse(container.getNames(), new String[0])) {
                Matcher matcher = FORWARDER.matcher(name);
                if (matcher.matches() && !dockerNetworkService.containerExists(matcher.group(1))) {
                    removeForwarder(matcher.group(1));
                }
            }
        }
    }

    private String startForwarder(String containerId, String address) {
        String image = containerConfig.network().forwarderImage();
        String port = address.substring(address.lastIndexOf(':') + 1);
        var create = dockerClient.createContainerCmd(image)
                .withName(forwarderName(containerId))
                .withCmd("TCP-LISTEN:" + port + ",fork,reuseaddr", "TCP:" + address)
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode("bridge"));
        String forwarderId;
        try {
            forwarderId = create.exec().getId();
        } catch (NotFoundException ex) {
            pullForwarderImage(image);
            forwarderId = create.exec().getId();
        }
        dockerClient.startContainerCmd(forwarderId).exec();
        Log.infof("Started forwarder %s to %s for container %s", forwarderId, address, containerId);
        return forwarderId;
    }

    private void pullForwarderImage(String image) {
        Log.infof("Pulling forwarder image %s", image);
        try (PullImageResultCallback pull = dockerClient.pullImageCmd(image).exec(new PullImageResultCallback())) {
            pull.awaitCompletion(containerConfig.pullTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException("Forwarder image " + image + " is not available", ex);
        }
    }

    private static String forwarderName(String containerId) {
        return "fl-" + containerId + "-db-forwarder";
    }

}
