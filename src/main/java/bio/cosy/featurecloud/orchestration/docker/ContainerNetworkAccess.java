package bio.cosy.featurecloud.orchestration.docker;

/**
 * The network capabilities a started container asked for. Mirrors the {@code needs*Access} flags of
 * the start DTOs, with {@code null} treated as "not requested".
 */
public record ContainerNetworkAccess(boolean internet, boolean host, boolean federatedLearning) {

    public static ContainerNetworkAccess of(Boolean internet, Boolean host, Boolean federatedLearning) {
        return new ContainerNetworkAccess(
                Boolean.TRUE.equals(internet),
                Boolean.TRUE.equals(host),
                Boolean.TRUE.equals(federatedLearning));
    }

    /**
     * A docker network flagged {@code internal} has no route off its own bridge, which also cuts
     * off the {@code host-gateway} address behind {@code host.docker.internal}. Host access
     * therefore needs a routable network just like internet access does.
     */
    public boolean internalOnly() {
        return !internet && !host;
    }

    /**
     * True when the container only asked for host access - it then gets a routable network which
     * implies internet access as well, so it is worth logging.
     */
    public boolean hostAccessWidensNetwork() {
        return host && !internet;
    }
}
