package bio.cosy.featurecloud.orchestration.execution.k8s;

import bio.cosy.featurecloud.orchestration.helper.NamingService;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Uploading into a volume, against fabric8's in-memory API server. The copy
 * itself (exec + tar) needs a real cluster; what is checked here is the PVC.
 */
@EnableKubernetesMockClient(crud = true)
class KubernetesVolumeAdminServiceTest {

    private static final String NS = "clinic";
    private static final String VOLUME = "feddb-clinic-fc-w1-n1_volume_input";

    KubernetesClient client;

    private KubernetesVolumeAdminService service;

    @BeforeEach
    void setUp() throws Exception {
        // The service reads the ownership labels (and with them the system
        // name) when the class loads.
        Field systemName = NamingService.class.getDeclaredField("systemName");
        systemName.setAccessible(true);
        systemName.set(null, "clinic1");
        service = new KubernetesVolumeAdminService();
        service.client = client;
        service.configuredNamespace = Optional.of(NS);
        service.volumeSize = "1Gi";
        service.storageClassName = Optional.empty();
        service.imagePullSecret = Optional.empty();
        service.transferTimeout = java.time.Duration.ofSeconds(30);
    }

    @Test
    void uploadCreatesTheVolumeWhenTheRunHasNotStartedYet() throws Exception {
        // local-learning-api 1.2 stages a run's inputs before it starts the
        // run, so nothing has created the input volume yet (Docker would
        // create it on first use).
        String pvc = K8sNames.pvcName(VOLUME);
        assertNull(client.persistentVolumeClaims().inNamespace(NS).withName(pvc).get());

        uploadIgnoringTheCopy();

        PersistentVolumeClaim created = client.persistentVolumeClaims().inNamespace(NS).withName(pvc).get();
        assertNotNull(created, "upload must create the missing PVC instead of failing with 'not found'");
    }

    @Test
    void uploadKeepsAnExistingVolume() throws Exception {
        service.createVolume(VOLUME);
        String uid = client.persistentVolumeClaims().inNamespace(NS)
                .withName(K8sNames.pvcName(VOLUME)).get().getMetadata().getUid();

        uploadIgnoringTheCopy();

        assertEquals(uid, client.persistentVolumeClaims().inNamespace(NS)
                .withName(K8sNames.pvcName(VOLUME)).get().getMetadata().getUid());
    }

    @Test
    void volumesOrchApiDidNotCreateCannotBeReadChangedOrDeleted() {
        // e.g. the clinic's database volume in the same namespace
        String foreign = "data-local-learning-db-0";
        client.persistentVolumeClaims().inNamespace(NS).resource(new io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder()
                .withNewMetadata().withName(foreign).endMetadata().build()).create();
        service.createVolume("mine");

        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.NotFoundException.class, () -> service.getVolume(foreign));
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.NotFoundException.class, () -> service.downloadToLocalDir(foreign));
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.ForbiddenException.class, () -> service.createVolume(foreign));
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.ForbiddenException.class, () -> service.removeVolume(foreign));
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.NotFoundException.class,
                () -> service.moveDataFromInputToOutput(foreign, "mine"));
        service.removeAllVolumes();

        assertNotNull(client.persistentVolumeClaims().inNamespace(NS).withName(foreign).get(), "the foreign volume is untouched");
        assertEquals(0, service.listVolumes().size());
    }

    /**
     * Runs the upload while playing the kubelet (marks the transfer Pod
     * Running, so it doesn't wait 60 s). There is no exec client against the
     * mock server, so the copy itself fails after the PVC and Pod exist.
     */
    private void uploadIgnoringTheCopy() throws Exception {
        Path file = Files.createTempFile("data", ".csv");
        AtomicBoolean done = new AtomicBoolean();
        Thread kubelet = new Thread(() -> {
            while (!done.get()) {
                for (Pod pod : client.pods().inNamespace(NS).list().getItems()) {
                    if (pod.getStatus() == null || !"Running".equals(pod.getStatus().getPhase())) {
                        pod.setStatus(new PodStatusBuilder().withPhase("Running").build());
                        try {
                            client.pods().inNamespace(NS).resource(pod).updateStatus();
                        } catch (RuntimeException ignored) {
                            // deleted meanwhile
                        }
                    }
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        kubelet.setDaemon(true);
        kubelet.start();
        try {
            service.uploadFile(VOLUME, file, "data.csv");
        } catch (RuntimeException expectedWithoutExec) {
            // see javadoc
        } finally {
            done.set(true);
            kubelet.join();
            Files.deleteIfExists(file);
        }
    }
}
