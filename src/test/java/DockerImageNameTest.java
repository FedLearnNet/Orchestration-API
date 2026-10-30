import bio.cosy.featurecloud.orchestration.service.DockerPullService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DockerImageNameTest {

    @Test
    void putsImagesWithoutRegistryOnDockerHub() {
        assertEquals("docker.io/library/nginx:alpine", DockerPullService.qualified("nginx:alpine"));
        assertEquals("docker.io/fl-net/code/transformer-1:abc", DockerPullService.qualified("fl-net/code/transformer-1:abc"));
    }

    @Test
    void keepsTheRegistryOfAnImage() {
        assertEquals("ghcr.io/fedlearnnet/x:latest", DockerPullService.qualified("ghcr.io/fedlearnnet/x:latest"));
        assertEquals("localhost:5000/x", DockerPullService.qualified("localhost:5000/x"));
        assertEquals("localhost/x", DockerPullService.qualified("localhost/x"));
    }
}
