package bio.cosy.featurecloud.orchestration.execution.k8s;

import bio.cosy.featurecloud.orchestration.api.orchestration.volume.VolumeInfoDTO;
import bio.cosy.featurecloud.orchestration.docker.LabelsHelper;
import bio.cosy.featurecloud.orchestration.execution.KubernetesBackend;
import bio.cosy.featurecloud.orchestration.execution.VolumeAdminService;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.okhttp.OkHttpClientFactory;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Kubernetes-native {@link VolumeAdminService}: a "volume" is a
 * PersistentVolumeClaim, same as {@link KubernetesVolumeService} (the
 * execution-flow equivalent) — this covers the wider admin surface that one
 * deliberately doesn't (list/create/remove-all/upload/download/cleanup),
 * closing the two gaps docs/kubernetes-execution-backend.md calls out under
 * "Still not covered": #5 (volume deletion on hard cleanup) and #6
 * (upload/download).
 *
 * Upload/download work by mounting the target PVC into a short-lived
 * "file-transfer" Pod and using fabric8's exec-based file copy
 * ({@code file(path).upload/read}) — there was no existing precedent for
 * this in the codebase (unlike copyBetweenVolumes's Job pattern), so this
 * is new, not a mechanical port.
 */
@KubernetesBackend
@ApplicationScoped
public class KubernetesVolumeAdminService implements VolumeAdminService {

    @Inject
    KubernetesClient client;

    // quarkus-kubernetes-client's injected client is Vert.x-backed, which — confirmed
    // live — fails WebSocket-based subresources (exec/attach) with a bare "WebSocket
    // upgrade failure: 403" that looks like an RBAC error but isn't one (kubectl exec
    // with the exact same identity succeeds). file(path).upload()/dir(path).copy() go
    // through exec+tar under the hood, so they need this separate OkHttp-backed client
    // built from the same in-cluster config; every other call stays on the injected one.
    private KubernetesClient execClient;

    @PostConstruct
    void initExecClient() {
        execClient = new KubernetesClientBuilder()
                .withConfig(client.getConfiguration())
                .withHttpClientFactory(new OkHttpClientFactory())
                .build();
    }

    @PreDestroy
    void closeExecClient() {
        if (execClient != null) {
            execClient.close();
        }
    }

    @ConfigProperty(name = "orch.kubernetes.namespace")
    Optional<String> configuredNamespace;

    @ConfigProperty(name = "orch.kubernetes.volume-size", defaultValue = "5Gi")
    String volumeSize;

    @ConfigProperty(name = "orch.kubernetes.storage-class")
    Optional<String> storageClassName;

    @ConfigProperty(name = "orch.kubernetes.image-pull-secret")
    Optional<String> imagePullSecret;

    /** How long a file transfer may take: waiting for its Pod, then the copy itself. */
    @ConfigProperty(name = "orch.kubernetes.transfer-timeout", defaultValue = "PT30M")
    java.time.Duration transferTimeout;

    private static final String MOUNT_PATH = "/data";

    private String namespace() {
        return configuredNamespace.orElseGet(() -> client.getNamespace() != null ? client.getNamespace() : "default");
    }

    private VolumeInfoDTO toDTO(PersistentVolumeClaim pvc) {
        String status = pvc.getStatus() != null ? pvc.getStatus().getPhase() : null;
        Long bytes = null;
        if (pvc.getStatus() != null && pvc.getStatus().getCapacity() != null) {
            Quantity q = pvc.getStatus().getCapacity().get("storage");
            if (q != null) {
                try {
                    bytes = Quantity.getAmountInBytes(q).longValue();
                } catch (Exception ignored) {
                    // best-effort only, see VolumeAdminService#volumeSize javadoc
                }
            }
        }
        return new VolumeInfoDTO(pvc.getMetadata().getName(), "kubernetes", status, bytes, pvc.getMetadata().getLabels());
    }

    @Override
    public List<VolumeInfoDTO> listVolumes() {
        return client.persistentVolumeClaims().inNamespace(namespace())
                .withLabels(K8sLabels.owned())
                .list().getItems().stream().map(this::toDTO).toList();
    }

    @Override
    public VolumeInfoDTO getVolume(String volumeName) {
        return toDTO(requireOwned(K8sNames.pvcName(volumeName)));
    }

    /**
     * The PVC if this orch-api created it. Every operation that takes a volume
     * name goes through here (or {@link #ownedOrNull}), so a caller can never
     * read, write or delete a volume orch-api doesn't own, such as a
     * database's PVC in the same namespace. Not owned reads as not found.
     */
    private PersistentVolumeClaim requireOwned(String pvcName) {
        PersistentVolumeClaim pvc = ownedOrNull(pvcName);
        if (pvc == null) {
            throw new NotFoundException("Volume " + pvcName + " not found");
        }
        return pvc;
    }

    private PersistentVolumeClaim ownedOrNull(String pvcName) {
        PersistentVolumeClaim pvc = client.persistentVolumeClaims().inNamespace(namespace()).withName(pvcName).get();
        return pvc != null && K8sLabels.isOwned(pvc.getMetadata().getLabels()) ? pvc : null;
    }

    @Override
    public VolumeInfoDTO createVolume(String volumeName) {
        String name = K8sNames.pvcName(volumeName);
        var pvcs = client.persistentVolumeClaims().inNamespace(namespace());
        PersistentVolumeClaim existing = pvcs.withName(name).get();
        if (existing != null) {
            if (!K8sLabels.isOwned(existing.getMetadata().getLabels())) {
                throw new ForbiddenException("Volume " + name + " exists and is not managed by this orch-api");
            }
            return toDTO(existing);
        }

        PersistentVolumeClaimBuilder builder = new PersistentVolumeClaimBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withLabels(K8sLabels.owned())
                .endMetadata()
                .withNewSpec()
                    .withAccessModes("ReadWriteOnce")
                    .withNewResources()
                        .addToRequests("storage", new Quantity(volumeSize))
                    .endResources()
                .endSpec();
        storageClassName.ifPresent(sc -> builder.editSpec().withStorageClassName(sc).endSpec());

        PersistentVolumeClaim created = pvcs.resource(builder.build()).create();
        Log.infof("[k8s] Admin-created PVC %s (%s)", name, volumeSize);
        return toDTO(created);
    }

    @Override
    public void uploadFile(String volumeName, Path localFile, String targetFileName) {
        // An upload may come before the app that mounts the volume exists:
        // local-learning-api stages a run's inputs, then starts it. Docker
        // creates a named volume on first use, so create the PVC here the
        // same way (no-op if it exists); the Job's ensurePvc reuses it.
        createVolume(volumeName);
        withFileTransferPod(volumeName, podName -> {
            boolean ok = execClient.pods().inNamespace(namespace()).withName(podName)
                    .file(MOUNT_PATH + "/" + targetFileName)
                    .upload(localFile);
            if (!ok) {
                throw new InternalServerErrorException("Upload of " + targetFileName + " into volume " + volumeName + " failed");
            }
        });
    }

    @Override
    public Path downloadToLocalDir(String volumeName) {
        Path tmpDir;
        try {
            tmpDir = Files.createTempDirectory("vol-download-" + volumeName + "-");
        } catch (Exception e) {
            throw new InternalServerErrorException("Could not create temp dir: " + e.getMessage(), e);
        }
        withFileTransferPod(volumeName, podName -> {
            boolean ok = execClient.pods().inNamespace(namespace()).withName(podName)
                    .dir(MOUNT_PATH)
                    .copy(tmpDir);
            if (!ok) {
                throw new InternalServerErrorException("Download from volume " + volumeName + " failed");
            }
        });
        return tmpDir;
    }

    /**
     * Creates a short-lived Pod with {@code volumeName}'s PVC mounted at
     * {@link #MOUNT_PATH}, waits for it to be Running, runs {@code action},
     * then always deletes the Pod (best-effort) — new pattern, see class
     * javadoc.
     */
    private void withFileTransferPod(String dockerVolumeName, java.util.function.Consumer<String> action) {
        String volumeName = K8sNames.pvcName(dockerVolumeName);
        requireOwned(volumeName);

        String podName = ("filetransfer-" + UUID.randomUUID()).substring(0, 30);
        PodBuilder podBuilder = new PodBuilder()
                .withNewMetadata()
                    .withName(podName)
                    .withLabels(K8sLabels.owned())
                .endMetadata()
                .withNewSpec()
                    .withRestartPolicy("Never")
                    .addNewContainer()
                        .withName("filetransfer")
                        .withImage("alpine:latest")
                        // Outlives the transfer (deleted right after it): a large
                        // upload over exec + tar must not be cut off by the Pod.
                        .withCommand("sleep", String.valueOf(transferTimeout.toSeconds() + 60))
                        .addNewVolumeMount()
                            .withName("data")
                            .withMountPath(MOUNT_PATH)
                        .endVolumeMount()
                    .endContainer()
                    .addNewVolume()
                        .withName("data")
                        .withNewPersistentVolumeClaim()
                            .withClaimName(volumeName)
                        .endPersistentVolumeClaim()
                    .endVolume()
                .endSpec();
        imagePullSecret.ifPresent(secretName -> podBuilder
                .editSpec()
                    .withImagePullSecrets(new LocalObjectReference(secretName))
                .endSpec());

        Pod pod = podBuilder.build();
        client.pods().inNamespace(namespace()).resource(pod).create();
        try {
            client.pods().inNamespace(namespace()).withName(podName)
                    .waitUntilCondition(p -> p != null && p.getStatus() != null
                                    && "Running".equals(p.getStatus().getPhase()),
                            transferTimeout.toSeconds(), TimeUnit.SECONDS);
            action.accept(podName);
        } catch (Exception e) {
            throw new InternalServerErrorException("File transfer against volume " + volumeName + " failed: " + e.getMessage(), e);
        } finally {
            client.pods().inNamespace(namespace()).withName(podName).delete();
        }
    }

    @Override
    public void moveDataFromInputToOutput(String inputVolumeName, String outputVolumeName) {
        requireOwned(K8sNames.pvcName(inputVolumeName));
        requireOwned(K8sNames.pvcName(outputVolumeName));
        String jobName = ("cp-io-" + UUID.randomUUID()).substring(0, 20);
        JobBuilder jobBuilder = new JobBuilder()
                .withNewMetadata()
                    .withName(jobName)
                    .withLabels(K8sLabels.owned())
                .endMetadata()
                .withNewSpec()
                    .withBackoffLimit(0)
                    .withTtlSecondsAfterFinished(300)
                    .withNewTemplate()
                        .withNewSpec()
                            .withRestartPolicy("Never")
                            .addNewContainer()
                                .withName("copy")
                                .withImage("alpine:latest")
                                .withCommand("sh", "-c")
                                .withArgs("cp -a /mnt/input/. /mnt/output/")
                                .addNewVolumeMount()
                                    .withName("input")
                                    .withMountPath("/mnt/input")
                                    .withReadOnly(true)
                                .endVolumeMount()
                                .addNewVolumeMount()
                                    .withName("output")
                                    .withMountPath("/mnt/output")
                                .endVolumeMount()
                            .endContainer()
                            .addNewVolume()
                                .withName("input")
                                .withNewPersistentVolumeClaim()
                                    .withClaimName(K8sNames.pvcName(inputVolumeName))
                                    .withReadOnly(true)
                                .endPersistentVolumeClaim()
                            .endVolume()
                            .addNewVolume()
                                .withName("output")
                                .withNewPersistentVolumeClaim()
                                    .withClaimName(K8sNames.pvcName(outputVolumeName))
                                .endPersistentVolumeClaim()
                            .endVolume()
                        .endSpec()
                    .endTemplate()
                .endSpec();
        imagePullSecret.ifPresent(secretName -> jobBuilder
                .editSpec()
                    .editTemplate()
                        .editSpec()
                            .withImagePullSecrets(new LocalObjectReference(secretName))
                        .endSpec()
                    .endTemplate()
                .endSpec());

        Job job = jobBuilder.build();
        client.batch().v1().jobs().inNamespace(namespace()).resource(job).create();
        try {
            Job finished = client.batch().v1().jobs().inNamespace(namespace()).withName(jobName)
                    .waitUntilCondition(j -> j != null && j.getStatus() != null
                                    && ((j.getStatus().getSucceeded() != null && j.getStatus().getSucceeded() > 0)
                                        || (j.getStatus().getFailed() != null && j.getStatus().getFailed() > 0)),
                            transferTimeout.toSeconds(), TimeUnit.SECONDS);
            boolean succeeded = finished != null && finished.getStatus() != null
                    && finished.getStatus().getSucceeded() != null && finished.getStatus().getSucceeded() > 0;
            if (!succeeded) {
                Log.errorf("[k8s] Input->output copy Job %s did not succeed", jobName);
                throw new RuntimeException("Error moving files from input to output volume");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Log.errorf("[k8s] Timed out or failed waiting for copy Job %s: %s", jobName, e.getMessage());
            throw new RuntimeException("Error moving files from input to output volume", e);
        } finally {
            client.batch().v1().jobs().inNamespace(namespace()).withName(jobName).delete();
        }
    }

    @Override
    public Long volumeSize(String name) {
        // Matches VolumeServiceImpl.volumeSize's existing (pre-this-change)
        // Docker-path behavior of returning null unconditionally — the
        // requested capacity is already surfaced via listVolumes/getVolume's
        // sizeBytes field, which is a more meaningful number for a PVC than
        // actual bytes used (which Kubernetes doesn't expose cheaply either).
        return null;
    }

    @Override
    public void removeVolume(String name) {
        String pvcName = K8sNames.pvcName(name);
        PersistentVolumeClaim pvc = client.persistentVolumeClaims().inNamespace(namespace()).withName(pvcName).get();
        if (pvc == null) {
            return;
        }
        if (!K8sLabels.isOwned(pvc.getMetadata().getLabels())) {
            throw new ForbiddenException("Volume " + pvcName + " is not managed by this orch-api");
        }
        client.persistentVolumeClaims().inNamespace(namespace()).withName(pvcName).delete();
    }

    @Override
    public void removeAllVolumes() {
        client.persistentVolumeClaims().inNamespace(namespace())
                .withLabels(K8sLabels.owned())
                .delete();
    }

    @Override
    public void cleanupWorkflowVolumes(Long workflowId) {
        client.persistentVolumeClaims().inNamespace(namespace())
                .withLabels(K8sLabels.sanitize(LabelsHelper.createLabelsWorkflow(workflowId)))
                .delete();
    }
}
