package bio.cosy.featurecloud.orchestration.service;

import bio.cosy.featurecloud.orchestration.config.ContainerConfig;
import bio.cosy.featurecloud.orchestration.docker.ContainerNetworkAccess;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateNetworkResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Network;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

@ApplicationScoped
public class DockerNetworkService {

    private static final Set<String> PREDEFINED_NETWORKS = Set.of("bridge", "host", "none");

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerConfig containerConfig;

    /**
     * Finds all Docker networks that match the configured network names
     *
     * @return List of matching Docker networks
     */
    public List<Network> findNetworksByConfiguredNames() {
        var networkNames = containerConfig.network().names();
        if (networkNames.isPresent() && !networkNames.get().isEmpty()) {
            List<Network> networks = dockerClient.listNetworksCmd()
                    .withNameFilter(networkNames.get().toArray(new String[0]))
                    .exec();
            if (networks.isEmpty()) {
                Log.warnf("No Docker network matches the configured names: %s", networkNames.get());
            }
            Log.infof("Found %d Docker networks matching configured names: %s", networks.size(), networkNames.get());
            return networks;
        } else {
            Log.warn("No Docker network names configured, using default behavior");
        }
        return List.of();
    }

    /**
     * Connects a container to a specific Docker network
     *
     * @param containerId ID of the container to connect
     * @param networkName Name of the network to connect to
     */
    public void connectContainerToNetwork(String containerId, String networkName) {
        connectContainerToNetwork(containerId, networkName, List.of());
    }

    /**
     * Connects a container to a specific Docker network, optionally registering additional DNS
     * aliases for it on that network.
     *
     * @param containerId ID of the container to connect
     * @param networkName Name of the network to connect to
     * @param aliases     extra DNS names the container should answer to on that network
     */
    public void connectContainerToNetwork(String containerId, String networkName, List<String> aliases) {
        try {
            Log.infof("Connecting container %s to network %s (aliases=%s)", containerId, networkName, aliases);
            var cmd = dockerClient.connectToNetworkCmd()
                    .withContainerId(containerId)
                    .withNetworkId(networkName);
            if (!aliases.isEmpty()) {
                cmd.withContainerNetwork(new ContainerNetwork().withAliases(aliases));
            }
            cmd.exec();
        } catch (DockerException ex) {
            String msg = ex.getMessage();
            if (msg != null
                    && msg.contains("Status 403")
                    && msg.toLowerCase(Locale.ROOT).contains("already exists")) {
                Log.debugf("Container %s is already connected to network %s (ignored): %s", containerId, networkName, msg);
            } else {
                Log.errorf(ex, "Failed to connect container %s to network %s: %s", containerId, networkName, msg);
                throw ex;
            }
        }
    }

    /**
     * Removes every network that was created exclusively for the given container. The configured
     * shared networks and docker's own predefined networks are kept, everything else is emptied of
     * its remaining participants and then deleted.
     *
     * @param containerId ID of the container whose dedicated networks should be removed
     */
    public void removeDedicatedNetwork(String containerId) {
        Log.infof("Removing dedicated networks for container %s", containerId);
        var inspect = dockerClient.inspectContainerCmd(containerId).exec();
        var networkSettings = inspect.getNetworkSettings();
        if (networkSettings == null || networkSettings.getNetworks() == null) {
            Log.debugf("Container %s has no networks attached, nothing to remove", containerId);
            return;
        }
        var dedicatedNetworks = new LinkedHashMap<>(networkSettings.getNetworks());
        // We need to skip the configured networks, they are meant for reuse
        for (Network network : findNetworksByConfiguredNames()) {
            dedicatedNetworks.remove(network.getName());
        }
        // ...and docker's predefined networks, which can neither be ours nor be removed
        dedicatedNetworks.keySet().removeIf(name -> PREDEFINED_NETWORKS.contains(name.toLowerCase(Locale.ROOT)));

        for (String networkName : dedicatedNetworks.keySet()) {
            Log.infof("Removing dedicated network %s from container %s", networkName, containerId);
            try {
                // Every participant we attached still holds an endpoint on the network and docker
                // refuses to remove a network with active endpoints, so disconnect them all first.
                disconnectAllContainers(networkName);
                dockerClient.removeNetworkCmd(networkName).exec();
            } catch (NotFoundException ex) {
                // Network is already gone — nothing to do.
                Log.warnf("Dedicated network %s not found (already removed) for container %s", networkName, containerId);
            } catch (DockerException ex) {
                Log.errorf(ex, "Failed to remove dedicated network %s from container %s: %s", networkName, containerId, ex.getMessage());
            }
        }
    }

    private void disconnectAllContainers(String networkName) {
        Map<String, com.github.dockerjava.api.model.Network.ContainerNetworkConfig> containers;
        try {
            containers = dockerClient.inspectNetworkCmd().withNetworkId(networkName).exec().getContainers();
        } catch (DockerException ex) {
            Log.warnf("Could not inspect network %s before removal: %s", networkName, ex.getMessage());
            return;
        }
        if (containers == null) {
            return;
        }
        for (String attachedContainerId : containers.keySet()) {
            try {
                dockerClient.disconnectFromNetworkCmd()
                        .withContainerId(attachedContainerId)
                        .withNetworkId(networkName)
                        .withForce(true)
                        .exec();
            } catch (DockerException ex) {
                Log.warnf("Failed to disconnect container %s from network %s: %s",
                        attachedContainerId, networkName, ex.getMessage());
            }
        }
    }

    /**
     * Fails fast when the requested access cannot be granted with the current configuration, so
     * that a container is never created just to be left without the network it needs.
     *
     * @param access the network capabilities the container is about to be started with
     */
    public void ensureAccessIsConfigured(ContainerNetworkAccess access) {
        requireLearningApiReachable();
        if (access.federatedLearning()) {
            resolveController();
        }
    }


    private void requireLearningApiReachable() {
        if (learningApi().isPresent()) {
            return;
        }
        if (hasConfiguredNetworks()) {
            Log.debug("'container.network.learning' is not configured; containers reach the learning API "
                    + "through the configured networks instead");
            return;
        }
        throw new IllegalStateException("Container requires learning api: neither "
                + "'container.network.learning' nor 'container.network.names' is configured, so a started "
                + "container would have no way to reach the learning API.");
    }

    private boolean hasConfiguredNetworks() {
        return containerConfig.network().names()
                .filter(names -> names.stream().anyMatch(StringUtils::isNotBlank))
                .isPresent();
    }

    private Optional<String> learningApi() {
        return containerConfig.network().learning()
                .filter(StringUtils::isNotBlank)
                .map(value -> resolveContainerName(value, "container.network.learning"));
    }

    /**
     * Connects a container to all configured Docker networks and creates a dedicated network.
     *
     * @param containerId ID of the container to connect
     * @param access      the network capabilities the container was started with
     */
    public void connectContainerToNetworks(String containerId, ContainerNetworkAccess access) {
        boolean internalOnly = access.internalOnly();
        Log.infof(
                "Connecting container %s to networks (internet=%s, host=%s, federatedLearning=%s, internalOnly=%s)",
                containerId, access.internet(), access.host(), access.federatedLearning(), internalOnly
        );
        if (access.hostAccessWidensNetwork()) {
            Log.warnf("Container %s asked for host access without internet access - its networks have to "
                            + "stay routable for host.docker.internal to resolve, which also exposes the internet",
                    containerId);
        }
        removeUnwantedNetworks(containerId);

        List<Network> networks = findNetworksByConfiguredNames();

        if (networks.isEmpty()) {
            Log.debug("No networks found to connect container to based on configured network names, continuing with dedicated network creation");
        }

        // Add networks based on the given networks configuration
        for (Network network : networks) {

            // Skip external networks when the container may not reach anything outside its bridge
            if (internalOnly && !Boolean.TRUE.equals(network.getInternal())) {
                if (!containerConfig.network().alwaysBridge()) {
                    Log.infof(
                            "Skipping network %s (%s) because it allows internet",
                            network.getName(),
                            network.getId()
                    );
                    continue;
                }
                Log.infof(
                        "Network %s (%s) allows internet but always-bridge is enabled, connecting anyway",
                        network.getName(),
                        network.getId()
                );
            }

            connectContainerToNetwork(containerId, network.getId());
        }

        // Create networks based on the given network participants configuration
        createDedicatedNetworkAndConnect(containerId, access);
    }


    public void createDedicatedNetworkAndConnect(String containerId, ContainerNetworkAccess access) {
        Set<String> participants = new LinkedHashSet<>();
        // Only when the API is named for the dedicated network; otherwise the container reaches it
        // over the configured networks and the dedicated one just isolates this container.
        learningApi().ifPresent(participants::add);
        if (access.federatedLearning()) {
            participants.add(resolveController());
        }
        boolean internalOnly = access.internalOnly();
        // Network names are capped at 128 chars, we cut to 125 chars
        // Note: Linux kernel has a limit of 15 chars for the network interface name,
        // Docker may use the 15 char prefix of the network name
        // This is why the UUID is realtively early in the network name
        String networkName = "fl-" + containerId + "-network-" + UUID.randomUUID();
        if (networkName.length() > 125) {
            networkName = networkName.substring(0, 125);
        }

        Log.infof("Creating dedicated network '%s' (internalOnly=%s)", networkName, internalOnly);

        CreateNetworkResponse created;
        try {
            created = dockerClient.createNetworkCmd()
                    .withName(networkName)
                    .withDriver("bridge")
                    .withAttachable(true)
                    .withInternal(internalOnly)
                    .withCheckDuplicate(true)
                    .exec();
        } catch (Exception ex) {
            Log.errorf(ex, "Failed to create dedicated network '%s'", networkName);
            throw ex;
        }

        String networkId = created.getId();
        Log.infof("Created dedicated network '%s' with id %s", networkName, networkId);
        connectContainerToNetwork(containerId, networkId);

        for (String participant : participants) {
            connectContainerToNetwork(participant, networkId);
        }
    }


    private String resolveController() {
        return containerConfig.network().controller()
                .filter(participant -> !participant.isBlank())
                .map(value -> resolveContainerName(value, "container.network.controller"))
                .orElseThrow(() -> new IllegalStateException("Container requires federated learning access but "
                        + "'container.network.controller' is not configured. "));
    }

    static String resolveContainerName(String endpoint, String property) {
        if (endpoint.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*:[0-9]{1,5}")) {
            int separator = endpoint.indexOf(':');
            int port = Integer.parseInt(endpoint.substring(separator + 1));
            if (port >= 1 && port <= 65535) {
                return endpoint.substring(0, separator);
            }
        }
        throw new IllegalStateException("Invalid container endpoint '" + endpoint + "' configured for " + property
                + ". Expected format: <container>:<port> with valid port number.");
    }

    private void removeUnwantedNetworks(String containerId) {
        var inspect = dockerClient.inspectContainerCmd(containerId).exec();
        var networkSettings = inspect.getNetworkSettings();
        var networks = networkSettings != null ? networkSettings.getNetworks() : null;

        if (networks != null) {
            for (String netName : networks.keySet()) {
                if ("none".equalsIgnoreCase(netName)) {
                    dockerClient.disconnectFromNetworkCmd()
                            .withContainerId(containerId)
                            .withNetworkId(netName)
                            .exec();
                }
            }
        }
    }

}
