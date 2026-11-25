package bio.cosy.featurecloud.orchestration.execution.k8s;

import bio.cosy.featurecloud.orchestration.api.orchestration.container.StartAppDTO;
import bio.cosy.featurecloud.orchestration.api.orchestration.workflow.StartWorkflowNodeDTO;
import bio.cosy.featurecloud.orchestration.config.ContainerConfig;
import bio.cosy.featurecloud.orchestration.docker.ContainerNetworkAccess;
import bio.cosy.featurecloud.orchestration.docker.DockerLabels;
import bio.cosy.featurecloud.orchestration.docker.DockerVolumeType;
import bio.cosy.featurecloud.orchestration.docker.LabelsHelper;
import bio.cosy.featurecloud.orchestration.execution.AppExecutionService;
import bio.cosy.featurecloud.orchestration.execution.AppInstance;
import bio.cosy.featurecloud.orchestration.execution.AppRun;
import bio.cosy.featurecloud.orchestration.execution.AppStatus;
import bio.cosy.featurecloud.orchestration.execution.KubernetesBackend;
import bio.cosy.featurecloud.orchestration.helper.NamingService;
import bio.cosy.featurecloud.orchestration.service.DockerVolumeService;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobList;
import io.fabric8.kubernetes.api.model.networking.v1.IPBlockBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRule;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyIngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyIngressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeerBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.apache.commons.lang3.StringUtils;
import java.time.Duration;
import java.util.Objects;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;

/**
 * Kubernetes-native {@link AppExecutionService}: runs FL/ETL Tool
 * containers as {@code batch/v1 Job}s instead of talking to a Docker
 * daemon. See {@code docs/kubernetes-execution-backend.md} for the full
 * design and what's explicitly out of scope here (Docker-in-Docker
 * pipelines, host access, volume-copy helpers). Per-run network isolation
 * (a NetworkPolicy per Job) is implemented, not out of scope — see
 * {@link #applyNetworkIsolation}.
 *
 * <p>A Job's name is used as the {@code AppRun}/{@code AppInstance} id. It
 * comes from {@code BaseName}, which builds it from {@code container.name}
 * (the system name) and only replaces underscores; it doesn't lowercase. So
 * {@link #validateSystemName} checks at startup that {@code container.name}
 * is a lowercase DNS label, which makes every Job name valid.
 */
@KubernetesBackend
@ApplicationScoped
public class KubernetesExecutionService implements AppExecutionService {

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

    @ConfigProperty(name = "container.memory.limit")
    Optional<Long> memoryLimitBytes;

    // See application.properties' comment: without this, Kubernetes defaults
    // the request to the limit above, which is too large to ever schedule.
    @ConfigProperty(name = "container.memory.request")
    Optional<Long> memoryRequestBytes;

    // Deliberately its own property, not derived from container.cpu.shares
    // (Docker's relative-weighting concept, not a portable absolute
    // quantity) — see application.properties' comment. Request only, no
    // limit, by design.
    @ConfigProperty(name = "container.cpu.request")
    Optional<String> cpuRequest;

    @ConfigProperty(name = "container.auto-stop")
    Optional<Boolean> autoStopTtl;

    @ConfigProperty(name = "orch.kubernetes.network-isolation.enabled", defaultValue = "true")
    boolean networkIsolationEnabled;

    @ConfigProperty(name = "orch.kubernetes.internal-cidrs")
    Optional<List<String>> internalCidrs;

    @ConfigProperty(name = "orch.execution-backend", defaultValue = "docker")
    String executionBackend;

    private static final java.util.regex.Pattern DNS_LABEL = java.util.regex.Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");

    /**
     * Fails startup on the Kubernetes backend when {@code container.name} is
     * not a lowercase DNS label: Job, Service and PVC names are built from
     * it, and Kubernetes would reject them only at the first run.
     */
    void validateSystemName(@Observes StartupEvent event) {
        if (!"kubernetes".equals(executionBackend)) {
            return;
        }
        String name = NamingService.getSystemName();
        if (name == null || name.length() > 40 || !DNS_LABEL.matcher(name).matches()) {
            throw new IllegalStateException("container.name is '" + name + "': on the Kubernetes backend it must be a "
                    + "lowercase DNS label of at most 40 characters (a-z, 0-9, '-'), because Job, Service and PVC "
                    + "names are built from it.");
        }
    }

    // The port a running app serves on. local-learning-api's
    // BaseOrchestrator.getAppUrl hardcodes http://<Names[0]>:9000, so the
    // per-run Service exposes exactly this.
    @ConfigProperty(name = "orch.kubernetes.app-port", defaultValue = "9000")
    int appPort;

    // The peers a run may talk to: container.network.learning and
    // container.network.controller, the same <host>:<port> settings the
    // Docker backend uses. Here the host names a Service; its pod selector
    // and target port go into the run's NetworkPolicy.
    @Inject
    ContainerConfig containerConfig;

    private String namespace() {
        return configuredNamespace.orElseGet(() -> client.getNamespace() != null ? client.getNamespace() : "default");
    }

    // ------------------------------------------------------------------
    // Starting runs
    // ------------------------------------------------------------------

    @Override
    public AppRun startApplication(StartWorkflowNodeDTO dto, String inputVolumeName, String outputVolumeName, boolean autokill) {
        Log.infof("[k8s] Creating Job for node %d in workflow %d", dto.getWorkflowNodeId(), dto.getWorkflowId());

        List<Volume> volumes = new ArrayList<>();
        List<VolumeMount> mounts = new ArrayList<>();

        if (StringUtils.isNotBlank(inputVolumeName)) {
            ensurePvc(inputVolumeName);
            volumes.add(new VolumeBuilder()
                    .withName("input")
                    .withPersistentVolumeClaim(new PersistentVolumeClaimVolumeSource(K8sNames.pvcName(inputVolumeName), false))
                    .build());
            mounts.add(new VolumeMountBuilder()
                    .withName("input")
                    .withMountPath(DockerVolumeService.VOLUME_PATH.get(DockerVolumeType.INPUT))
                    .build());
        }
        if (StringUtils.isNotBlank(outputVolumeName)) {
            ensurePvc(outputVolumeName);
            volumes.add(new VolumeBuilder()
                    .withName("output")
                    .withPersistentVolumeClaim(new PersistentVolumeClaimVolumeSource(K8sNames.pvcName(outputVolumeName), false))
                    .build());
            mounts.add(new VolumeMountBuilder()
                    .withName("output")
                    .withMountPath(DockerVolumeService.VOLUME_PATH.get(DockerVolumeType.OUTPUT))
                    .build());
        }

        Map<String, String> labels = LabelsHelper.createLabelsForWorkflowNode(dto.getWorkflowId(), dto.getWorkflowNodeId());
        dto.getEnvironments().stream()
                .filter(env -> env.startsWith("APP_ID="))
                .findFirst()
                .ifPresent(env -> labels.put(
                        bio.cosy.featurecloud.orchestration.docker.DockerLabels.FEATURE_CLOUD_WORKFLOW_STEP.toString(),
                        env.substring("APP_ID=".length())
                ));

        if (Boolean.TRUE.equals(dto.getNeedsHostAccess())) {
            Log.warnf("[k8s] Workflow node %d requested host access, which the Kubernetes backend doesn't implement yet (see docs/kubernetes-execution-backend.md) — ignoring.",
                    dto.getWorkflowNodeId());
        }

        boolean needsInternetAccess = Boolean.TRUE.equals(dto.getNeedsInternetAccess());
        boolean needsFederatedLearningAccess = Boolean.TRUE.equals(dto.getNeedsFederatedLearningAccess());
        return createJob(dto.getContainerName(), dto.getAppImage(), dto.getEnvironments(), labels, volumes, mounts, autokill,
                needsInternetAccess, needsFederatedLearningAccess);
    }

    @Override
    public AppRun startApplication(StartAppDTO dto) {
        Log.infof("[k8s] Creating Job for id %d in group %d", dto.getIdentifier(), dto.getGroupId());

        if (Boolean.TRUE.equals(dto.getNeedsHostAccess())) {
            Log.warnf("[k8s] id %d requested host access, which the Kubernetes backend doesn't implement yet — ignoring.", dto.getIdentifier());
        }

        Map<String, String> labels = LabelsHelper.createGroupLabels(dto.getIdentifier(), dto.getGroupId());
        boolean needsInternetAccess = Boolean.TRUE.equals(dto.getNeedsInternetAccess());
        // Single-app runs (connector/ETL tools) never take part in federated
        // learning, so they never get the controller as a peer.
        return createJob(dto.getContainerName(), dto.getAppImage(), dto.getEnvironments(), labels, List.of(), List.of(), true,
                needsInternetAccess, false);
    }

    private AppRun createJob(String name, String image, List<String> environments, Map<String, String> labels,
                              List<Volume> volumes, List<VolumeMount> mounts, boolean autokill, boolean needsInternetAccess,
                              boolean needsFederatedLearningAccess) {
        List<EnvVar> envVars = environments.stream()
                .map(this::toEnvVar)
                .filter(java.util.Objects::nonNull)
                .toList();

        ResourceRequirementsBuilder resources = new ResourceRequirementsBuilder();
        memoryLimitBytes.ifPresent(bytes -> resources.addToLimits("memory", new Quantity(bytes + "")));
        memoryRequestBytes.ifPresent(bytes -> resources.addToRequests("memory", new Quantity(bytes + "")));
        cpuRequest.ifPresent(cpu -> resources.addToRequests("cpu", new Quantity(cpu)));

        var podSpecBuilder = new JobBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withLabels(K8sLabels.sanitize(labels))
                .endMetadata()
                .withNewSpec()
                    // Created suspended, so no Pod runs before its Service and
                    // NetworkPolicy exist; released below.
                    .withSuspend(true)
                    .withBackoffLimit(0)
                    .withTtlSecondsAfterFinished(autokill ? 300 : null)
                    .withNewTemplate()
                        .withNewMetadata()
                            .withLabels(K8sLabels.sanitize(labels))
                        .endMetadata()
                        .withNewSpec()
                            .withRestartPolicy("Never")
                            .addNewContainerLike(new io.fabric8.kubernetes.api.model.Container())
                                .withName("tool")
                                .withImage(image)
                                .withEnv(envVars)
                                .withVolumeMounts(mounts)
                                .withResources(resources.build())
                            .endContainer()
                            .withVolumes(volumes)
                        .endSpec()
                    .endTemplate()
                .endSpec();

        imagePullSecret.ifPresent(secretName -> podSpecBuilder
                .editSpec()
                    .editTemplate()
                        .editSpec()
                            .withImagePullSecrets(new LocalObjectReference(secretName))
                        .endSpec()
                    .endTemplate()
                .endSpec());

        Job job = podSpecBuilder.build();

        Job created = client.batch().v1().jobs().inNamespace(namespace()).resource(job).create();
        String jobName = created.getMetadata().getName();
        Log.infof("[k8s] Job %s created (suspended)", jobName);

        // Fail closed: the Job stays suspended (no Pod) until its Service and
        // NetworkPolicy exist. If either fails, the Job is deleted and the run
        // never starts, instead of running without isolation. The two still
        // name the Job as owner, so they are garbage-collected with it.
        try {
            createAppService(created);
            if (networkIsolationEnabled) {
                applyNetworkIsolation(created, labels, needsInternetAccess, needsFederatedLearningAccess);
            }
            client.batch().v1().jobs().inNamespace(namespace()).withName(jobName).edit(j -> new JobBuilder(j)
                    .editSpec().withSuspend(false).endSpec()
                    .build());
            Log.infof("[k8s] Job %s released", jobName);
        } catch (RuntimeException e) {
            Log.errorf("[k8s] Could not isolate Job %s, deleting it instead of running it unisolated: %s", jobName, e.getMessage());
            removeApplication(jobName);
            throw e;
        }

        return new AppRun(jobName);
    }

    // Docker reaches a running app by its container name on a shared network.
    // A Pod has no such name, so each run gets a ClusterIP Service in front of
    // it, selected by the job-name label the Job controller puts on its Pods.
    // AppInstance.address reports this Service's name, which is what
    // local-learning-api then calls (http://<Names[0]>:9000).
    //
    // Owned by the Job, like the NetworkPolicy below, so it is garbage-collected
    // with it, including when ttlSecondsAfterFinished removes the Job.
    private void createAppService(Job job) {
        String jobName = job.getMetadata().getName();
        Service service = new ServiceBuilder()
                .withNewMetadata()
                    .withName(serviceNameFor(jobName))
                    .withLabels(job.getMetadata().getLabels())
                    .withOwnerReferences(ownedBy(job))
                .endMetadata()
                .withNewSpec()
                    .withSelector(Map.of(JOB_NAME_LABEL, jobName))
                    .addNewPort()
                        .withName("app")
                        .withProtocol("TCP")
                        .withPort(appPort)
                        .withTargetPort(new IntOrString(appPort))
                    .endPort()
                .endSpec()
                .build();
        client.services().inNamespace(namespace()).resource(service).create();
        Log.infof("[k8s] Service %s created for Job %s (port %d)", service.getMetadata().getName(), jobName, appPort);
    }

    /** Link-local range holding the cloud instance metadata service (169.254.169.254). */
    static final String LINK_LOCAL_CIDR = "169.254.0.0/16";

    // Set on every Pod by the Job controller (Kubernetes 1.27+).
    static final String JOB_NAME_LABEL = "batch.kubernetes.io/job-name";

    /**
     * Service names must be DNS-1035 labels: start with a letter, at most 63
     * characters. Job names come from BaseName and often start with a digit
     * (a hash prefix), so a fixed prefix is added; names that would overflow
     * are shortened and disambiguated with a hash of the full Job name.
     */
    /**
     * How local-learning-api reaches a run: the short Service name when it
     * runs in the runs' namespace, {@code <service>.<namespace>} when
     * container.network.learning names another namespace
     * ({@code <service>.<namespace>:<port>}), where the short name wouldn't
     * resolve.
     */
    String addressFor(String jobName) {
        String service = serviceNameFor(jobName);
        if (containerConfig == null) {
            return service;
        }
        String learningNamespace = containerConfig.network().learning()
                .map(address -> address.contains(":") ? address.substring(0, address.lastIndexOf(':')) : address)
                .map(host -> host.split("\\."))
                .filter(parts -> parts.length > 1)
                .map(parts -> parts[1])
                .orElse(namespace());
        return learningNamespace.equals(namespace()) ? service : service + "." + namespace();
    }

    public static String serviceNameFor(String jobName) {
        String candidate = "app-" + jobName;
        if (candidate.length() <= 63) {
            return candidate;
        }
        String hash = Integer.toHexString(jobName.hashCode());
        String head = candidate.substring(0, 63 - hash.length() - 1).replaceAll("-+$", "");
        return head + "-" + hash;
    }

    private static OwnerReference ownedBy(Job job) {
        return new OwnerReferenceBuilder()
                .withApiVersion("batch/v1")
                .withKind("Job")
                .withName(job.getMetadata().getName())
                .withUid(job.getMetadata().getUid())
                .withController(true)
                .withBlockOwnerDeletion(true)
                .build();
    }

    // The Docker backend gives every run its own network containing just the
    // run and its peers: the learning API always, the controller as well for
    // federated learning (DockerNetworkService.createDedicatedNetworkAndConnect).
    // This is the Kubernetes equivalent — a NetworkPolicy scoped to this run's own
    // labels that allows traffic with exactly those peers, both ways, and
    // nothing else in the namespace: not other runs, not orch-api, not the
    // databases. Traffic must flow both ways — local-learning-api calls the
    // app on port 9000, and the app calls back into local-learning-api (and,
    // for FL, the controller's app-communication port).
    //
    // Owned by the Job, so Kubernetes garbage-collects it the same way, also
    // when ttlSecondsAfterFinished removes the Job without going through this
    // class.
    private void applyNetworkIsolation(Job job, Map<String, String> labels, boolean needsInternetAccess,
                                       boolean needsFederatedLearningAccess) {
        List<Peer> peers = new ArrayList<>();
        peers.add(learningApiPeer());
        if (needsFederatedLearningAccess) {
            peers.add(controllerPeer());
        }

        List<NetworkPolicyIngressRule> ingress = new ArrayList<>();
        List<NetworkPolicyEgressRule> egress = new ArrayList<>();
        for (Peer peer : peers) {
            ingress.add(new NetworkPolicyIngressRuleBuilder()
                    .withFrom(peer.asPolicyPeer())
                    .build());
            NetworkPolicyEgressRuleBuilder out = new NetworkPolicyEgressRuleBuilder()
                    .withTo(peer.asPolicyPeer());
            if (peer.port() != null) {
                out.addNewPort().withProtocol("TCP").withPort(peer.port()).endPort();
            }
            egress.add(out.build());
        }

        // DNS is always allowed — an otherwise-correct policy that also blocks
        // DNS tends to fail in confusing, indirect ways.
        egress.add(new NetworkPolicyEgressRuleBuilder()
                .addNewPort().withProtocol("UDP").withNewPort(53).endPort()
                .addNewPort().withProtocol("TCP").withNewPort(53).endPort()
                .build());

        if (needsInternetAccess) {
            // The cloud instance metadata service (169.254.169.254 on AKS, GKE
            // and EKS) is outside the pod, service and node ranges but hands
            // out node credentials, so it is always excluded.
            List<String> except = new ArrayList<>(List.of(LINK_LOCAL_CIDR));
            if (internalCidrs.isPresent() && !internalCidrs.get().isEmpty()) {
                except.addAll(internalCidrs.get());
            } else {
                Log.warnf("[k8s] Job %s needs internet access but orch.kubernetes.internal-cidrs isn't configured — it may reach other pods/services as well as the internet (but not the cloud metadata service; ingress into it is still limited to its peers).",
                        job.getMetadata().getName());
            }
            var ipBlock = new IPBlockBuilder()
                    .withCidr("0.0.0.0/0")
                    .withExcept(except)
                    .build();
            egress.add(new NetworkPolicyEgressRuleBuilder()
                    .addNewTo().withIpBlock(ipBlock).endTo()
                    .build());
        }

        NetworkPolicy policy = new NetworkPolicyBuilder()
                .withNewMetadata()
                    .withName(job.getMetadata().getName())
                    .withLabels(K8sLabels.sanitize(labels))
                    .withOwnerReferences(ownedBy(job))
                .endMetadata()
                .withNewSpec()
                    .withNewPodSelector()
                        .withMatchLabels(K8sLabels.sanitize(labels))
                    .endPodSelector()
                    .withPolicyTypes("Ingress", "Egress")
                    .withIngress(ingress)
                    .withEgress(egress)
                .endSpec()
                .build();

        client.network().v1().networkPolicies().inNamespace(namespace()).resource(policy).create();
        Log.infof("[k8s] NetworkPolicy %s created (peers=%s, needsInternetAccess=%s)",
                policy.getMetadata().getName(), peers.stream().map(Peer::address).toList(), needsInternetAccess);
    }

    /**
     * A workload a run may talk to, resolved from a {@code <host>:<port>}
     * setting. {@code host} is a Service name, optionally
     * {@code <service>.<namespace>[.svc...]}; the policy allows that
     * Service's pods, on the target port behind {@code port}.
     */
    record Peer(String address, String namespace, Map<String, String> podSelector, IntOrString port,
                boolean sameNamespace) {

        NetworkPolicyPeer asPolicyPeer() {
            NetworkPolicyPeerBuilder peer = new NetworkPolicyPeerBuilder()
                    .withNewPodSelector().withMatchLabels(podSelector).endPodSelector();
            if (!sameNamespace) {
                peer.withNewNamespaceSelector()
                        .withMatchLabels(Map.of("kubernetes.io/metadata.name", namespace))
                        .endNamespaceSelector();
            }
            return peer.build();
        }
    }

    private Peer learningApiPeer() {
        return resolvePeer(containerConfig.network().learning()
                .filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new IllegalStateException("Container requires learning api: "
                        + "'container.network.learning' is not configured, so the run's NetworkPolicy "
                        + "could not let it reach the learning API.")), "container.network.learning");
    }

    private Peer controllerPeer() {
        return resolvePeer(containerConfig.network().controller()
                .filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new IllegalStateException("Container requires federated learning access, "
                        + "but 'container.network.controller' is not configured.")), "container.network.controller");
    }

    Peer resolvePeer(String address, String property) {
        String host = address;
        Integer port = null;
        int colon = address.lastIndexOf(':');
        if (colon > 0) {
            host = address.substring(0, colon);
            try {
                port = Integer.parseInt(address.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("'" + property + "' must be <host>:<port>, got '" + address + "'");
            }
        }
        String[] parts = host.split("\\.");
        String serviceName = parts[0];
        String peerNamespace = parts.length > 1 ? parts[1] : namespace();

        Service service = client.services().inNamespace(peerNamespace).withName(serviceName).get();
        if (service == null || service.getSpec() == null
                || service.getSpec().getSelector() == null || service.getSpec().getSelector().isEmpty()) {
            throw new IllegalStateException("'" + property + "' is '" + address + "', but namespace "
                    + peerNamespace + " has no Service '" + serviceName + "' with a pod selector to allow traffic to.");
        }
        IntOrString targetPort = null;
        if (port != null) {
            int wanted = port;
            targetPort = service.getSpec().getPorts().stream()
                    .filter(p -> p.getPort() != null && p.getPort() == wanted)
                    .findFirst()
                    .map(p -> p.getTargetPort() != null ? p.getTargetPort() : new IntOrString(wanted))
                    .orElseThrow(() -> new IllegalStateException("'" + property + "' is '" + address
                            + "', but Service " + serviceName + " has no port " + wanted + "."));
        }
        return new Peer(address, peerNamespace, service.getSpec().getSelector(), targetPort,
                peerNamespace.equals(namespace()));
    }

    @Override
    public void ensureNetworkAccessConfigured(ContainerNetworkAccess access) {
        if (!networkIsolationEnabled) {
            return;
        }
        learningApiPeer();
        if (access.federatedLearning()) {
            controllerPeer();
        }
    }

    private EnvVar toEnvVar(String raw) {
        int idx = raw.indexOf('=');
        if (idx < 0) {
            Log.warnf("[k8s] Ignoring malformed environment entry (expected KEY=VALUE): %s", raw);
            return null;
        }
        return new EnvVar(raw.substring(0, idx), raw.substring(idx + 1), null);
    }

    // Deliberately separate from KubernetesVolumeService's ensureVolume: this
    // is a defensive fallback for callers (e.g. the StartAppDTO path) that
    // never went through the volume-creation step at all, so there's no
    // workflowId/DockerVolumeType to label it with here.
    private void ensurePvc(String dockerVolumeName) {
        String volumeName = K8sNames.pvcName(dockerVolumeName);
        var pvcs = client.persistentVolumeClaims().inNamespace(namespace());
        PersistentVolumeClaim existing = pvcs.withName(volumeName).get();
        if (existing != null) {
            if (!K8sLabels.isOwned(existing.getMetadata().getLabels())) {
                // Never mount someone else's volume (e.g. a database's) into a run.
                throw new jakarta.ws.rs.ForbiddenException("Volume " + volumeName + " is not managed by this orch-api");
            }
            return;
        }
        PersistentVolumeClaimBuilder builder = new PersistentVolumeClaimBuilder()
                .withNewMetadata()
                    .withName(volumeName)
                    .withLabels(K8sLabels.owned())
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
        Log.infof("[k8s] PVC %s created (%s)", volumeName, volumeSize);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    private void stopContainer(String id) {
        Log.infof("[k8s] Suspending Job: %s", id);
        try {
            client.batch().v1().jobs().inNamespace(namespace()).withName(id).edit(j -> new JobBuilder(j)
                    .editSpec().withSuspend(true).endSpec()
                    .build());
        } catch (Exception e) {
            Log.warnf("[k8s] Failed to suspend Job %s: %s", id, e.getMessage());
        }
    }

    private boolean removeApplication(String id) {
        Log.infof("[k8s] Deleting Job: %s", id);
        try {
            List<io.fabric8.kubernetes.api.model.StatusDetails> result =
                    client.batch().v1().jobs().inNamespace(namespace()).withName(id)
                            .withPropagationPolicy(DeletionPropagation.BACKGROUND)
                            .delete();
            return !result.isEmpty();
        } catch (Exception e) {
            Log.warnf("[k8s] Failed to delete Job %s: %s", id, e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    @Override
    public List<AppInstance> showRunning() {
        return jobsByLabels(LabelsHelper.createLabels());
    }

    @Override
    public List<AppInstance> showFeatureCloudContainers() {
        return jobsByLabels(LabelsHelper.createLabels());
    }

    @Override
    public AppInstance getById(String id) {
        Job job = ownedJob(id);
        if (job == null) {
            throw new NotFoundException("Container with ID " + id + " not found");
        }
        return toAppInstance(job);
    }

    /** The Job by name if it exists and this orch-api created it; null otherwise (not owned reads as not found). */
    private Job ownedJob(String name) {
        Job job = client.batch().v1().jobs().inNamespace(namespace()).withName(name).get();
        return job != null && K8sLabels.isOwned(job.getMetadata().getLabels()) ? job : null;
    }

    @Override
    public List<AppInstance> getByStepLabel(String step) {
        return jobsByLabels(LabelsHelper.createLabelsForStepSearch(step));
    }

    // ------------------------------------------------------------------
    // Cleanup — mirrors DockerCleanupService
    // ------------------------------------------------------------------

    @Override
    public void cleanupContainer(String id, boolean removeContainer, boolean removeVolumes, boolean removeDedicatedNetwork) {
        if (removeVolumes && !removeContainer) {
            Log.warn("[k8s] Removing volumes without removing the Job may fail: a Pod can still hold them.");
        }
        Job job = client.batch().v1().jobs().inNamespace(namespace()).withName(id).get();
        if (job == null) {
            Log.warnf("[k8s] Skipping cleanup: Job %s system_name=%s is already removed", id, NamingService.getSystemName());
            if (removeDedicatedNetwork) {
                removeNetworkPolicy(id);
            }
            return;
        }
        Map<String, String> labels = job.getMetadata().getLabels();
        if (!K8sLabels.isOwned(labels)) {
            Log.warnf("[k8s] Refusing cleanup: Job %s expectedSystem=%s actualSystem=%s", id,
                    NamingService.getSystemName(), labels == null ? null : labels.get(DockerLabels.SYSTEM_NAME.toString()));
            return;
        }
        Log.infof("[k8s] Cleaning up Job %s (removeContainer=%s, removeVolumes=%s, removeDedicatedNetwork=%s)",
                id, removeContainer, removeVolumes, removeDedicatedNetwork);

        // Always stopped, as on Docker. A suspended Job keeps its record and
        // logs; deleting it is what removeContainer means.
        stopContainer(id);

        if (removeDedicatedNetwork) {
            removeNetworkPolicy(id);
        }
        if (removeContainer && !removeApplication(id)) {
            throw new IllegalStateException("Failed to remove Job " + id
                    + " for system_name=" + NamingService.getSystemName() + "; retaining its volumes");
        }
        if (removeVolumes) {
            for (String claim : claimNames(job)) {
                Log.infof("[k8s] Removing volume: %s", claim);
                client.persistentVolumeClaims().inNamespace(namespace()).withName(claim).delete();
            }
        }
    }

    @Override
    public void cleanupWorkflow(Long workflowId) {
        Map<String, String> labels = LabelsHelper.createLabelsWorkflow(workflowId);
        for (Job job : jobsWithLabels(labels)) {
            cleanupContainer(job.getMetadata().getName(), true, true, true);
        }
        // Unlike Docker's, this also removes volumes no Job mounts any more,
        // e.g. from a run that failed to start after its volumes were
        // created. On Kubernetes each one is a billed disk.
        client.persistentVolumeClaims().inNamespace(namespace())
                .withLabels(K8sLabels.sanitize(labels))
                .delete();
    }

    @Override
    public void cleanupWorkflowNode(Long workflowNodeId) {
        for (Job job : jobsWithLabels(LabelsHelper.createLabelsForWorkflowNode(workflowNodeId))) {
            cleanupContainer(job.getMetadata().getName(), true, false, true);
        }
    }

    private void removeNetworkPolicy(String jobName) {
        // Named after the Job (see applyNetworkIsolation) and owned by it, so
        // deleting the Job removes it too; this covers keeping the Job.
        client.network().v1().networkPolicies().inNamespace(namespace()).withName(jobName).delete();
    }

    private static List<String> claimNames(Job job) {
        var volumes = job.getSpec().getTemplate().getSpec().getVolumes();
        return volumes == null ? List.of() : volumes.stream()
                .map(v -> v.getPersistentVolumeClaim() != null ? v.getPersistentVolumeClaim().getClaimName() : null)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public Optional<AppInstance> getCurrentContainer(Long id, Long groupId) {
        return firstOf(jobsByLabels(LabelsHelper.createGroupLabels(id, groupId)));
    }

    @Override
    public Optional<AppInstance> getCurrentWorkflowNode(Long workflowId, Long workflowNodeId) {
        return firstOf(jobsByLabels(LabelsHelper.createLabelsForWorkflowNode(workflowId, workflowNodeId)));
    }

    @Override
    public Optional<AppInstance> getByName(String name) {
        return Optional.ofNullable(ownedJob(name)).map(this::toAppInstance);
    }

    private List<AppInstance> jobsByLabels(Map<String, String> labels) {
        JobList list = client.batch().v1().jobs().inNamespace(namespace()).withLabels(K8sLabels.sanitize(labels)).list();
        return list.getItems().stream().map(this::toAppInstance).toList();
    }

    private List<Job> jobsWithLabels(Map<String, String> labels) {
        return client.batch().v1().jobs().inNamespace(namespace()).withLabels(K8sLabels.sanitize(labels)).list().getItems();
    }

    private Optional<AppInstance> firstOf(List<AppInstance> instances) {
        return instances.isEmpty() ? Optional.empty() : Optional.of(instances.get(0));
    }

    private AppInstance toAppInstance(Job job) {
        String name = job.getMetadata().getName();
        var status = job.getStatus();
        AppStatus appStatus = AppStatus.PENDING;
        if (status != null) {
            if (status.getSucceeded() != null && status.getSucceeded() > 0) {
                appStatus = AppStatus.SUCCEEDED;
            } else if (status.getFailed() != null && status.getFailed() > 0) {
                appStatus = AppStatus.FAILED;
            } else if (status.getActive() != null && status.getActive() > 0) {
                appStatus = AppStatus.RUNNING;
            }
        }
        String image = job.getSpec().getTemplate().getSpec().getContainers().isEmpty()
                ? null
                : job.getSpec().getTemplate().getSpec().getContainers().get(0).getImage();
        List<String> volumeNames = job.getSpec().getTemplate().getSpec().getVolumes() == null
                ? List.of()
                : job.getSpec().getTemplate().getSpec().getVolumes().stream()
                        .map(v -> v.getPersistentVolumeClaim() != null ? v.getPersistentVolumeClaim().getClaimName() : null)
                        .filter(java.util.Objects::nonNull)
                        .toList();
        Map<String, String> labels = job.getMetadata().getLabels() == null ? Map.of() : new HashMap<>(job.getMetadata().getLabels());
        return new AppInstance(name, name, image, labels, appStatus, volumeNames, addressFor(name));
    }

    // ------------------------------------------------------------------
    // Logs
    // ------------------------------------------------------------------

    @Override
    public Multi<String> getLogById(String id) {
        Log.infof("[k8s] Streaming logs for Job: %s", id);
        return Multi.createFrom().emitter(emitter -> {
            if (ownedJob(id) == null) {
                emitter.fail(new NotFoundException("Container with ID " + id + " not found"));
                return;
            }
            // Both waits below exist because attaching to logs on Kubernetes is
            // racy in a way it is not on Docker. `docker logs` blocks until the
            // container exists; the Kubernetes API answers immediately with
            //   {"kind":"Status","status":"Failure",
            //    "message":"container \"tool\" ... is waiting to start:
            //     ContainerCreating","code":400}
            // which arrives as ordinary stream content, the reader then hits
            // EOF, and the stream completes empty. Nothing throws, so the
            // caller cannot tell a silent container from a lost one.
            //
            // Without this, a run whose image was still being pulled lost
            // its entire output.
            Optional<String> podName = awaitPodName(id);
            if (podName.isEmpty()) {
                emitter.fail(new NotFoundException("No Pod found for Job " + id));
                return;
            }
            if (!awaitContainerStartedOrFinished(podName.get())) {
                emitter.fail(new IllegalStateException(
                        "Container for Job " + id + " did not start within " + LOG_ATTACH_TIMEOUT));
                return;
            }
            try (LogWatch watch = client.pods().inNamespace(namespace()).withName(podName.get()).tailingLines(200).watchLog()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(watch.getOutput(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        emitter.emit(line);
                    }
                }
                emitter.complete();
            } catch (Exception e) {
                Log.errorf("[k8s] Error streaming logs for Job %s: %s", id, e.getMessage());
                emitter.fail(e);
            }
        });
    }

    /**
     * How long to wait for the Job's Pod to appear and its container to leave
     * the waiting state before giving up on logs. Generous on purpose: the
     * first run of a tool pulls its image, and a cold pull of a large image on
     * a slow link legitimately takes minutes. Failing early here would
     * reintroduce exactly the bug this guards against.
     */
    private static final Duration LOG_ATTACH_TIMEOUT = Duration.ofMinutes(5);

    private static final Duration LOG_ATTACH_POLL = Duration.ofMillis(500);

    /**
     * The Pod is created by the Job controller, not by us, so immediately after
     * createJob there may be no Pod to find yet.
     */
    private Optional<String> awaitPodName(String jobName) {
        long deadline = System.nanoTime() + LOG_ATTACH_TIMEOUT.toNanos();
        while (true) {
            Optional<String> name = findPodName(jobName);
            if (name.isPresent()) {
                return name;
            }
            if (System.nanoTime() >= deadline || !sleepPoll()) {
                return Optional.empty();
            }
        }
    }

    /**
     * Waits until the container is running or has terminated. Terminated counts:
     * a fast container may finish before we attach, and the Kubernetes API still
     * serves the logs of a completed Pod.
     */
    private boolean awaitContainerStartedOrFinished(String podName) {
        long deadline = System.nanoTime() + LOG_ATTACH_TIMEOUT.toNanos();
        while (true) {
            Pod pod = client.pods().inNamespace(namespace()).withName(podName).get();
            if (pod == null) {
                return false;
            }
            if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
                boolean ready = pod.getStatus().getContainerStatuses().stream()
                        .map(ContainerStatus::getState)
                        .filter(Objects::nonNull)
                        .anyMatch(state -> state.getRunning() != null || state.getTerminated() != null);
                if (ready) {
                    return true;
                }
            }
            if (System.nanoTime() >= deadline || !sleepPoll()) {
                Log.warnf("[k8s] Container in Pod %s still waiting after %s; giving up on logs",
                        podName, LOG_ATTACH_TIMEOUT);
                return false;
            }
        }
    }

    private boolean sleepPoll() {
        try {
            Thread.sleep(LOG_ATTACH_POLL.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Optional<String> findPodName(String jobName) {
        PodList pods = client.pods().inNamespace(namespace()).withLabel("job-name", jobName).list();
        return pods.getItems().stream().findFirst().map(Pod::getMetadata).map(io.fabric8.kubernetes.api.model.ObjectMeta::getName);
    }
}
