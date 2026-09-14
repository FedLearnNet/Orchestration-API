import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

/** Provides a real Docker network participant without requiring a deployed learning API. */
public class LearningApiTestResource implements QuarkusTestResourceLifecycleManager {

    private Network network;
    private GenericContainer<?> learningApi;

    @Override
    public Map<String, String> start() {
        network = Network.newNetwork();
        learningApi = new GenericContainer<>(DockerImageName.parse("busybox:1.37"))
                .withNetwork(network)
                .withCommand("httpd", "-f", "-p", "8080")
                .withExposedPorts(8080);
        try {
            learningApi.start();
            var docker = DockerClientFactory.instance();
            String networkName = docker.client().inspectNetworkCmd()
                    .withNetworkId(network.getId()).exec().getName();
            return Map.of(
                    "container.network.learning", learningApi.getContainerInfo().getName().substring(1) + ":8080",
                    "container.network.names", networkName,
                    "container.network.always-bridge", "false",
                    "orch.docker.docker-host", docker.getTransportConfig().getDockerHost().toString());
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    @Override
    public void stop() {
        try {
            if (learningApi != null) {
                learningApi.stop();
            }
        } finally {
            if (network != null) {
                network.close();
            }
        }
    }
}
