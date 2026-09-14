import bio.cosy.featurecloud.orchestration.docker.DockerLabels;
import bio.cosy.featurecloud.orchestration.docker.LabelsHelper;
import bio.cosy.featurecloud.orchestration.service.DockerAppService;
import bio.cosy.featurecloud.orchestration.service.DockerCleanupService;
import bio.cosy.featurecloud.orchestration.service.DockerVolumeService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.HostConfig;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(LearningApiTestResource.class)
class DockerOwnershipTest {

    private static final long WORKFLOW = 700015L;
    private static final long NODE = 700003L;
    private static final String STEP = "ownership-regression";

    @Inject DockerClient docker;
    @Inject DockerAppService apps;
    @Inject DockerCleanupService cleanup;
    @Inject DockerVolumeService volumes;

    @Test
    void lookupsExcludeOtherSystemsAndUnlabelledContainers() {
        List<String> created = new ArrayList<>();
        try {
            String own = createContainer(labels(), false, created);
            String other = createContainer(foreignLabels(), false, created);
            Map<String, String> unlabelled = labels();
            unlabelled.remove(DockerLabels.SYSTEM_NAME.toString());
            createContainer(unlabelled, false, created);

            assertEquals(List.of(own), apps.getWorkflowContainers(WORKFLOW).stream().map(c -> c.getId()).toList());
            assertEquals(List.of(own), apps.getWorkflowNodeContainers(NODE).stream().map(c -> c.getId()).toList());
            assertEquals(own, apps.getCurrentWorkflowNode(WORKFLOW, NODE).orElseThrow().getId());
            assertEquals(own, apps.getCurrentContainer(NODE, WORKFLOW).orElseThrow().getId());
            assertEquals(own, apps.getCurrentPipelineContainer(WORKFLOW).orElseThrow().getId());
            assertEquals(List.of(own), apps.getByStepLabel(STEP).stream().map(c -> c.getId()).toList());
            String foreignName = docker.inspectContainerCmd(other).exec().getName().substring(1);
            assertTrue(apps.getByName(foreignName).isEmpty());
            assertTrue(apps.showRunning().stream().noneMatch(c -> c.getId().equals(other)));
        } finally {
            created.forEach(this::removeTestContainer);
        }
    }

    @Test
    void cleanupRemovesOwnContainerAndLeavesOtherClinicRunning() {
        List<String> created = new ArrayList<>();
        try {
            String own = createContainer(labels(), true, created);
            String other = createContainer(foreignLabels(), true, created);
            cleanup.cleanupWorkflow(WORKFLOW);
            assertThrows(NotFoundException.class, () -> docker.inspectContainerCmd(own).exec());
            assertTrue(docker.inspectContainerCmd(other).exec().getState().getRunning());

            // A direct ID must not bypass the ownership guard either.
            cleanup.cleanupContainer(other, true, true, true);
            assertTrue(docker.inspectContainerCmd(other).exec().getState().getRunning());
        } finally {
            created.forEach(this::removeTestContainer);
        }
    }

    @Test
    void volumeCleanupAndListingRespectSystemOwnership() {
        String own = "orch-own-" + UUID.randomUUID();
        String other = "orch-other-" + UUID.randomUUID();
        String unlabelled = "orch-unlabelled-" + UUID.randomUUID();
        try {
            docker.createVolumeCmd().withName(own).withLabels(labels()).exec();
            docker.createVolumeCmd().withName(other).withLabels(foreignLabels()).exec();
            Map<String, String> legacy = labels();
            legacy.remove(DockerLabels.SYSTEM_NAME.toString());
            docker.createVolumeCmd().withName(unlabelled).withLabels(legacy).exec();
            List<String> visible = volumes.listVolumes().stream().map(v -> v.getName()).toList();
            assertTrue(visible.contains(own));
            assertFalse(visible.contains(other));
            assertFalse(visible.contains(unlabelled));

            volumes.cleanupWorkflowVolumes(WORKFLOW);
            assertThrows(NotFoundException.class, () -> docker.inspectVolumeCmd(own).exec());
            volumes.removeVolume(other);
            volumes.removeVolume(unlabelled);
            assertNotNull(docker.inspectVolumeCmd(other).exec());
            assertNotNull(docker.inspectVolumeCmd(unlabelled).exec());
        } finally {
            for (String name : List.of(own, other, unlabelled)) {
                try {
                    docker.removeVolumeCmd(name).exec();
                } catch (NotFoundException ignored) {
                    // Already removed by the operation under test.
                }
            }
        }
    }

    private Map<String, String> labels() {
        Map<String, String> labels = LabelsHelper.createLabelsForWorkflowNode(WORKFLOW, NODE);
        labels.put(DockerLabels.FEATURE_CLOUD_APP_ID.toString(), Long.toString(NODE));
        labels.put(DockerLabels.FEATURE_CLOUD_GROUP_ID.toString(), Long.toString(WORKFLOW));
        labels.put(DockerLabels.FEATURE_CLOUD_PIPELINE.toString(), Long.toString(WORKFLOW));
        labels.put(DockerLabels.FEATURE_CLOUD_WORKFLOW_STEP.toString(), STEP);
        return labels;
    }

    private Map<String, String> foreignLabels() {
        Map<String, String> other = new HashMap<>(labels());
        other.put(DockerLabels.SYSTEM_NAME.toString(), "other-clinic-" + UUID.randomUUID());
        return other;
    }

    private String createContainer(Map<String, String> labels, boolean start, List<String> created) {
        String id = docker.createContainerCmd("busybox:1.37")
                .withLabels(labels).withCmd("sleep", "300")
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode("none"))
                .exec().getId();
        created.add(id);
        if (start) {
            docker.startContainerCmd(id).exec();
        }
        return id;
    }

    private void removeTestContainer(String id) {
        try {
            docker.removeContainerCmd(id).withForce(true).exec();
        } catch (NotFoundException ignored) {
            // Already removed by the operation under test.
        }
    }
}
