package bio.cosy.featurecloud.orchestration.execution.k8s;

import bio.cosy.featurecloud.orchestration.api.orchestration.OrchestrationInfoDTO;
import bio.cosy.featurecloud.orchestration.execution.KubernetesBackend;
import bio.cosy.featurecloud.orchestration.execution.OrchestrationInfoService;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Kubernetes-native {@link OrchestrationInfoService}. Populates every field
 * of {@link OrchestrationInfoDTO} that has a real Kubernetes equivalent —
 * cluster-wide via the Nodes API (System overview, most of Hardware &
 * limits) and namespace-scoped via Pods (Containers & images) — and leaves
 * genuinely Docker-daemon-only concepts null: a single daemon's storage
 * driver, proxy config, root dir, and registry/plugin config don't exist at
 * the cluster level, and per-container cgroup capability flags
 * (MemoryLimit/SwapLimit/OomKillDisable) and daemon-process counters
 * (NGoroutines/NFd) don't correspond to anything meaningful across a
 * multi-node cluster.
 */
@KubernetesBackend
@ApplicationScoped
public class KubernetesOrchestrationInfoService implements OrchestrationInfoService {

    @Inject
    KubernetesClient client;

    @ConfigProperty(name = "orch.kubernetes.namespace")
    Optional<String> configuredNamespace;

    private String namespace() {
        return configuredNamespace.orElseGet(() -> client.getNamespace() != null ? client.getNamespace() : "default");
    }

    @Override
    public OrchestrationInfoDTO getInfo() {
        OrchestrationInfoDTO dto = new OrchestrationInfoDTO();
        dto.systemTime = Instant.now().toString();

        try {
            var v = client.getKubernetesVersion();
            dto.serverVersion = v != null ? v.getGitVersion() : null;
        } catch (Exception e) {
            Log.warnf("Could not read Kubernetes server version: %s", e.getMessage());
        }

        try {
            NodeList nodes = client.nodes().list();
            populateFromNodes(dto, nodes.getItems());
        } catch (Exception e) {
            Log.warnf("Could not read cluster nodes for orchestration info: %s", e.getMessage());
        }

        try {
            List<Pod> pods = client.pods().inNamespace(namespace()).list().getItems();
            populateFromPods(dto, pods);
        } catch (Exception e) {
            Log.warnf("Could not read pods for orchestration info: %s", e.getMessage());
        }

        return dto;
    }

    private void populateFromNodes(OrchestrationInfoDTO dto, List<Node> nodes) {
        if (nodes.isEmpty()) {
            return;
        }

        dto.name = nodes.stream().map(n -> n.getMetadata().getName())
                .collect(Collectors.joining(", "));

        var firstInfo = nodes.get(0).getStatus() != null ? nodes.get(0).getStatus().getNodeInfo() : null;
        if (firstInfo != null) {
            dto.operatingSystem = firstInfo.getOsImage();
            dto.osType = firstInfo.getOperatingSystem();
            dto.kernelVersion = firstInfo.getKernelVersion();
            dto.architecture = firstInfo.getArchitecture();

            String runtime = firstInfo.getContainerRuntimeVersion();
            if (runtime != null) {
                String[] parts = runtime.split("://", 2);
                dto.runtimes = Map.of(parts[0], Map.of("path", parts.length > 1 ? parts[1] : ""));
            }
        }

        long totalMillicores = 0;
        long totalMemoryBytes = 0;
        for (Node node : nodes) {
            if (node.getStatus() == null || node.getStatus().getCapacity() == null) {
                continue;
            }
            Map<String, Quantity> capacity = node.getStatus().getCapacity();
            Quantity cpu = capacity.get("cpu");
            if (cpu != null) {
                totalMillicores += parseCpuMillicores(cpu.getAmount());
            }
            Quantity memory = capacity.get("memory");
            if (memory != null) {
                totalMemoryBytes += Quantity.getAmountInBytes(memory).longValue();
            }
        }
        dto.ncpu = (int) (totalMillicores / 1000);
        dto.memTotal = totalMemoryBytes;
    }

    // CPU quantities use Kubernetes' decimal SI suffixes (e.g. "4" or "4000m"
    // for 4 cores), not Quantity#getAmountInBytes' binary/byte suffixes — that
    // method would silently mis-parse "m" (milli) as a byte unit.
    private static long parseCpuMillicores(String amount) {
        if (amount.endsWith("m")) {
            return Long.parseLong(amount.substring(0, amount.length() - 1));
        }
        return Math.round(Double.parseDouble(amount) * 1000);
    }

    private void populateFromPods(OrchestrationInfoDTO dto, List<Pod> pods) {
        int running = 0, stopped = 0;
        Set<String> images = new java.util.HashSet<>();
        for (Pod pod : pods) {
            String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : null;
            if ("Running".equals(phase) || "Pending".equals(phase)) {
                running++;
            } else if ("Succeeded".equals(phase) || "Failed".equals(phase)) {
                stopped++;
            }
            if (pod.getSpec() != null && pod.getSpec().getContainers() != null) {
                pod.getSpec().getContainers().forEach(c -> images.add(c.getImage()));
            }
        }
        dto.containersRunning = running;
        dto.containersStopped = stopped;
        dto.containersPaused = 0; // no equivalent concept for Pods
        dto.containers = pods.size();
        dto.images = images.size();
    }
}
