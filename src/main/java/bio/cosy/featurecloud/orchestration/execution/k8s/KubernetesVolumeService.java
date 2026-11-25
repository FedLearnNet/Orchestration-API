package bio.cosy.featurecloud.orchestration.execution.k8s;

import bio.cosy.featurecloud.orchestration.docker.DockerVolumeType;
import bio.cosy.featurecloud.orchestration.docker.LabelsHelper;
import bio.cosy.featurecloud.orchestration.execution.KubernetesBackend;
import bio.cosy.featurecloud.orchestration.execution.VolumeExecutionService;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Kubernetes-native {@link VolumeExecutionService}: a "volume" is a
 * {@code PersistentVolumeClaim}, created lazily the first time it's asked
 * for. See {@code docs/kubernetes-execution-backend.md} for what's not
 * covered (the copy-between-volumes helpers, {@code ReadWriteOnce} vs.
 * {@code ReadWriteMany}).
 */
@KubernetesBackend
@ApplicationScoped
public class KubernetesVolumeService implements VolumeExecutionService {

    @Inject
    KubernetesClient client;

    @ConfigProperty(name = "orch.kubernetes.namespace")
    Optional<String> configuredNamespace;

    @ConfigProperty(name = "orch.kubernetes.volume-size", defaultValue = "5Gi")
    String volumeSize;

    @ConfigProperty(name = "orch.kubernetes.storage-class")
    Optional<String> storageClassName;

    @ConfigProperty(name = "orch.kubernetes.image-pull-secret")
    Optional<String> imagePullSecret;

    /** How long a copy between volumes may take (image pull and disk attach included). */
    @ConfigProperty(name = "orch.kubernetes.transfer-timeout", defaultValue = "PT30M")
    java.time.Duration transferTimeout;

    @ConfigProperty(name = "container.file-transfer.grant-users")
    Optional<List<String>> grantUsers;

    @ConfigProperty(name = "container.file-transfer.grant-group")
    Optional<List<String>> grantGroups;

    String namespace() {
        return configuredNamespace.orElseGet(() -> client.getNamespace() != null ? client.getNamespace() : "default");
    }

    @Override
    public String ensureVolume(Long workflowId, DockerVolumeType type, String name) {
        String pvcName = K8sNames.pvcName(name);
        var pvcs = client.persistentVolumeClaims().inNamespace(namespace());
        PersistentVolumeClaim existing = pvcs.withName(pvcName).get();
        if (existing != null && existing.getMetadata().getDeletionTimestamp() != null) {
            // A hard cleanup just deleted it (rerunning the same node): the
            // pvc-protection finalizer keeps it until the old Pod is gone. Wait
            // that out and create it fresh, instead of handing a Terminating
            // claim to the new Job, whose Pod would then stay Pending.
            Log.infof("[k8s] PVC %s is being deleted, waiting before recreating it", pvcName);
            pvcs.withName(pvcName).waitUntilCondition(java.util.Objects::isNull, transferTimeout.toSeconds(), TimeUnit.SECONDS);
            existing = null;
        }
        if (existing != null) {
            if (!K8sLabels.isOwned(existing.getMetadata().getLabels())) {
                throw new jakarta.ws.rs.ForbiddenException("Volume " + pvcName + " exists and is not managed by this orch-api");
            }
            Log.infof("[k8s] PVC %s already exists", pvcName);
            return name;
        }

        var labels = LabelsHelper.createLabelsWorkflow(workflowId);
        labels.put(bio.cosy.featurecloud.orchestration.docker.DockerLabels.FEATURE_CLOUD_VOLUME_TYPE.toString(), type.toString());

        PersistentVolumeClaimBuilder builder = new PersistentVolumeClaimBuilder()
                .withNewMetadata()
                    .withName(pvcName)
                    .withLabels(K8sLabels.sanitize(labels))
                .endMetadata()
                .withNewSpec()
                    .withAccessModes("ReadWriteOnce")
                    .withNewResources()
                        .addToRequests("storage", new Quantity(volumeSize))
                    .endResources()
                .endSpec();
        storageClassName.ifPresent(sc -> builder.editSpec().withStorageClassName(sc).endSpec());

        PersistentVolumeClaim pvc = builder.build();
        pvcs.resource(pvc).create();
        Log.infof("[k8s] PVC %s created (%s)", pvcName, volumeSize);
        return name;
    }

    @Override
    public boolean volumeExists(String name) {
        return client.persistentVolumeClaims().inNamespace(namespace()).withName(K8sNames.pvcName(name)).get() != null;
    }

    // ------------------------------------------------------------------
    // Copying between volumes — Kubernetes port of DockerVolumeService's
    // moveFileToToAnotherContainer: a short-lived Job with both PVCs
    // mounted, running the same cp -a + chown/chmod shape. Unlike Docker, a
    // failed or timed-out copy throws: on Kubernetes an image pull plus a
    // disk attach can take minutes, and the next node must not start on an
    // empty input volume (WorkflowNodeBO turns the exception into a 400).
    // ------------------------------------------------------------------

    private static final String INPUT_MOUNT = "/mnt/input";
    private static final String OUTPUT_MOUNT = "/mnt/output";

    @Override
    public void copyBetweenVolumes(String sourceVolumeName, String sourceFileName, String destVolumeName, String destFileName) {
        if (sourceVolumeName == null || sourceVolumeName.isEmpty()) {
            throw new BadRequestException("sourceVolumeName is empty");
        }
        Log.infof("[k8s] Copying %s/%s -> %s/%s", sourceVolumeName, sourceFileName, destVolumeName, destFileName);
        requireOwned(K8sNames.pvcName(sourceVolumeName));
        requireOwned(K8sNames.pvcName(destVolumeName));

        String inputPath = INPUT_MOUNT + "/" + sourceFileName;
        String outputPath = OUTPUT_MOUNT + "/" + destFileName;
        String script = buildCopyWithChownScript(inputPath, outputPath);

        String jobName = ("copy-" + UUID.randomUUID()).substring(0, 20);

        var podSpecBuilder = new JobBuilder()
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
                            .addNewContainerLike(new io.fabric8.kubernetes.api.model.Container())
                                .withName("copy")
                                .withImage("alpine:latest")
                                .withCommand("sh", "-c")
                                .withArgs(script)
                                .withVolumeMounts(
                                        new VolumeMountBuilder().withName("input").withMountPath(INPUT_MOUNT).withReadOnly(true).build(),
                                        new VolumeMountBuilder().withName("output").withMountPath(OUTPUT_MOUNT).build()
                                )
                            .endContainer()
                            .withVolumes(
                                    new VolumeBuilder().withName("input").withPersistentVolumeClaim(new PersistentVolumeClaimVolumeSource(K8sNames.pvcName(sourceVolumeName), true)).build(),
                                    new VolumeBuilder().withName("output").withPersistentVolumeClaim(new PersistentVolumeClaimVolumeSource(K8sNames.pvcName(destVolumeName), false)).build()
                            )
                        .endSpec()
                    .endTemplate()
                .endSpec();

        imagePullSecret.ifPresent(secretName -> podSpecBuilder
                .editSpec()
                    .editTemplate()
                        .editSpec()
                            .withImagePullSecrets(new io.fabric8.kubernetes.api.model.LocalObjectReference(secretName))
                        .endSpec()
                    .endTemplate()
                .endSpec());

        Job job = podSpecBuilder.build();
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
                String logs = fetchLogsBestEffort(jobName);
                Log.errorf("[k8s] Copy Job %s did not succeed — logs: %s", jobName, logs);
                throw new IllegalStateException("Copy from " + sourceVolumeName + " to " + destVolumeName + " failed");
            }
            Log.infof("[k8s] Copy Job %s succeeded", jobName);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            Log.errorf("[k8s] Timed out or failed waiting for copy Job %s: %s", jobName, e.getMessage());
            throw new IllegalStateException("Copy from " + sourceVolumeName + " to " + destVolumeName
                    + " did not finish within " + transferTimeout, e);
        } finally {
            client.batch().v1().jobs().inNamespace(namespace()).withName(jobName).delete();
        }
    }

    private void requireOwned(String pvcName) {
        PersistentVolumeClaim pvc = client.persistentVolumeClaims().inNamespace(namespace()).withName(pvcName).get();
        if (pvc == null || !K8sLabels.isOwned(pvc.getMetadata().getLabels())) {
            // Not owned reads as not found: never copy into or out of a volume
            // orch-api didn't create.
            throw new jakarta.ws.rs.NotFoundException("Volume " + pvcName + " not found");
        }
    }

    private String fetchLogsBestEffort(String jobName) {
        try {
            PodList pods = client.pods().inNamespace(namespace()).withLabel("job-name", jobName).list();
            Pod pod = pods.getItems().stream().findFirst().orElse(null);
            if (pod == null) {
                return "(no pod found)";
            }
            return client.pods().inNamespace(namespace()).withName(pod.getMetadata().getName()).getLog();
        } catch (Exception e) {
            return "(failed to fetch logs: " + e.getMessage() + ")";
        }
    }

    private String buildCopyWithChownScript(String inputPath, String outputPath) {
        String in = inputPath.replace("\"", "\\\"");
        String out = outputPath.replace("\"", "\\\"");
        String chownCmd = buildChownCommand(outputPath).orElse(":");
        return "set -e; cp -a \"" + in + "\" \"" + out + "\"; " + chownCmd;
    }

    // Port of DockerVolumeService's getChownUserCommand — same
    // container.file-transfer.grant-users/grant-group config, same
    // pairwise user/group loop.
    private Optional<String> buildChownCommand(String path) {
        List<String> users = grantUsers
                .map(list -> list.stream().filter(s -> s != null && !s.isBlank()).toList())
                .orElse(List.of());
        List<String> groups = grantGroups
                .map(list -> list.stream().filter(s -> s != null && !s.isBlank()).toList())
                .orElse(List.of());

        if (users.isEmpty()) {
            return Optional.empty();
        }
        String defaultGroup = groups.isEmpty() ? null : groups.get(0);
        String target = "\"" + path.replace("\"", "\\\"") + "\"";

        List<String> parts = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            String u = users.get(i);
            String g = groups.isEmpty() ? null : (i < groups.size() ? groups.get(i) : defaultGroup);
            if (g == null || g.isBlank()) {
                parts.add("chown -R " + u + " " + target + " 2>/dev/null || true");
            } else {
                parts.add("chown -R " + u + ":" + g + " " + target + " 2>/dev/null || true");
            }
            parts.add("chmod -R 777 " + target + " 2>/dev/null || true");
        }
        return Optional.of(String.join("; ", parts));
    }
}
