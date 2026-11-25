package bio.cosy.featurecloud.orchestration.execution;

import bio.cosy.featurecloud.orchestration.execution.k8s.KubernetesExecutionService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the JSON contract orch-api's clients depend on. local-learning-api
 * reads start responses as {@code CreateContainerResponseDTO} and container
 * lookups as {@code ContainerDTO}, both mapped with Docker's capitalized
 * keys. The consumer classes below copy those annotations from
 * FedLearnNet/Learning-APIs (core-learning-api, services/orch/dto) so this
 * test reads our output the way the real client does.
 *
 * <p>Plain JUnit, no Quarkus or Docker needed.
 */
class DockerWireContractTest {

    // Same as Quarkus' default: unknown properties are ignored, not fatal.
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static class CreateContainerResponseDTO {
        @JsonProperty("Id") public String id;
        @JsonProperty("Warnings") public String[] warnings;
    }

    static class ContainerDTO {
        @JsonProperty("Id") public String id;
        @JsonProperty("Image") public String image;
        @JsonProperty("Names") public String[] names;
        @JsonProperty("Labels") public Map<String, String> labels;
        @JsonProperty("Status") public String status;
        @JsonProperty("State") public String state;
    }

    @Test
    void startResponseIdSurvivesTheRoundTrip() throws Exception {
        String json = mapper.writeValueAsString(new AppRun("23066b0878f77e41-fc-g7-id42"));
        CreateContainerResponseDTO read = mapper.readValue(json, CreateContainerResponseDTO.class);

        assertEquals("23066b0878f77e41-fc-g7-id42", read.id, json);
        assertEquals(0, read.warnings.length, json);
    }

    @Test
    void containerLookupCarriesTheReachableAddressInNames() throws Exception {
        AppInstance instance = new AppInstance(
                "23066b0878f77e41-fc-g7-id42",
                "23066b0878f77e41-fc-g7-id42",
                "ghcr.io/fedlearnnet/some-tool:1",
                Map.of("featurecloud.app.id", "42"),
                AppStatus.RUNNING,
                List.of("vol-in"),
                "app-23066b0878f77e41-fc-g7-id42");

        String json = mapper.writeValueAsString(instance);
        ContainerDTO read = mapper.readValue(json, ContainerDTO.class);

        assertEquals("23066b0878f77e41-fc-g7-id42", read.id, json);
        assertEquals("ghcr.io/fedlearnnet/some-tool:1", read.image, json);
        assertEquals("42", read.labels.get("featurecloud.app.id"), json);
        assertEquals("running", read.state, json);
        assertEquals("Up", read.status, json);
        // BaseOrchestrator.getAppUrl strips the leading slash and calls
        // http://<name>:9000 — so this must be the Service name.
        assertEquals("/app-23066b0878f77e41-fc-g7-id42", read.names[0], json);
        // No lowercase leftovers that a stricter client might trip over.
        assertTrue(!json.contains("\"id\"") && !json.contains("\"name\""), json);
    }

    @Test
    void finishedRunsLookLikeExitedDockerContainers() throws Exception {
        for (var c : List.of(
                Map.entry(AppStatus.SUCCEEDED, "Exited (0)"),
                Map.entry(AppStatus.FAILED, "Exited (1)"))) {
            AppInstance i = new AppInstance("x", "x", "img", Map.of(), c.getKey(), List.of(), "x");
            ContainerDTO read = mapper.readValue(mapper.writeValueAsString(i), ContainerDTO.class);
            assertEquals("exited", read.state);
            assertEquals(c.getValue(), read.status);
        }
    }

    @Test
    void serviceNamesAreValidDns1035Labels() {
        String dns1035 = "[a-z]([-a-z0-9]*[a-z0-9])?";
        for (String job : List.of(
                "23066b0878f77e41-fc-g7-id42",
                "workflow-9-node-3",
                "23066b0878f77e41-fc-" + "g".repeat(40) + "-id4200000000000")) {
            String svc = KubernetesExecutionService.serviceNameFor(job);
            assertTrue(svc.matches(dns1035), svc);
            assertTrue(svc.length() <= 63, svc);
        }
        // Distinct long names must not collapse onto the same Service.
        String a = KubernetesExecutionService.serviceNameFor("x".repeat(70) + "a");
        String b = KubernetesExecutionService.serviceNameFor("x".repeat(70) + "b");
        assertTrue(!a.equals(b), a + " vs " + b);
    }

    @Test
    void volumeResponsesKeepDockersFieldNames() throws Exception {
        // The /volume API returned docker-java's InspectVolumeResponse before
        // the backend split; its JSON must not change for Docker callers.
        var inspect = mapper.readValue(
                "{\"Name\":\"v1\",\"Driver\":\"local\",\"Mountpoint\":\"/var/lib/docker/volumes/v1/_data\","
                        + "\"Labels\":{\"a\":\"b\"},\"Options\":{\"o\":\"p\"}}",
                com.github.dockerjava.api.command.InspectVolumeResponse.class);
        var dockerJson = mapper.readTree(mapper.writeValueAsString(inspect));
        var dto = bio.cosy.featurecloud.orchestration.api.orchestration.volume.VolumeInfoDTO.docker(
                inspect.getName(), inspect.getDriver(), inspect.getMountpoint(), inspect.getLabels(), inspect.getOptions());
        var ourJson = mapper.readTree(mapper.writeValueAsString(dto));

        for (String field : new String[]{"Name", "Driver", "Mountpoint", "Labels", "Options"}) {
            org.junit.jupiter.api.Assertions.assertEquals(dockerJson.get(field), ourJson.get(field), field);
        }
        org.junit.jupiter.api.Assertions.assertNull(ourJson.get("name"), "no lowercase duplicate of Name");
    }
}
