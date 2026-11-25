package bio.cosy.featurecloud.orchestration.execution.docker;

import bio.cosy.featurecloud.orchestration.api.orchestration.OrchestrationInfoDTO;
import bio.cosy.featurecloud.orchestration.execution.DockerBackend;
import bio.cosy.featurecloud.orchestration.execution.OrchestrationInfoService;
import bio.cosy.featurecloud.orchestration.service.DockerOrchestration;

import com.github.dockerjava.api.model.Info;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Wraps the existing, unmodified {@link DockerOrchestration} to satisfy
 * {@link OrchestrationInfoService}. Maps every field docker-java's
 * {@link Info} exposes onto {@link OrchestrationInfoDTO} 1:1 — that DTO's
 * field names mirror {@code Info}'s wire format exactly for this reason,
 * so the admin dashboard (built against Docker's raw Engine API shape)
 * keeps working unchanged for real, Docker-backed clinic nodes.
 */
@DockerBackend
@ApplicationScoped
public class DockerOrchestrationInfoAdapter implements OrchestrationInfoService {

    @Inject
    DockerOrchestration dockerOrchestration;

    @Override
    public OrchestrationInfoDTO getInfo() {
        Info info = dockerOrchestration.getInfo();
        OrchestrationInfoDTO dto = new OrchestrationInfoDTO();

        dto.serverVersion = info.getServerVersion();
        dto.operatingSystem = info.getOperatingSystem();
        dto.osType = info.getOsType();
        dto.kernelVersion = info.getKernelVersion();
        dto.architecture = info.getArchitecture();
        dto.name = info.getName();
        dto.systemTime = info.getSystemTime();

        dto.ncpu = info.getNCPU();
        dto.memTotal = info.getMemTotal();
        dto.memoryLimit = info.getMemoryLimit();
        dto.swapLimit = info.getSwapLimit();
        dto.oomKillDisable = info.getOomKillDisable();
        dto.nGoroutines = info.getNGoroutines();
        dto.nFd = info.getNFd();

        dto.containersRunning = info.getContainersRunning();
        dto.containersPaused = info.getContainersPaused();
        dto.containersStopped = info.getContainersStopped();
        dto.containers = info.getContainers();
        dto.images = info.getImages();

        dto.securityOptions = info.getSecurityOptions();
        dto.isolation = info.getIsolation();
        if (info.getRuntimes() != null) {
            dto.runtimes = new java.util.LinkedHashMap<>();
            info.getRuntimes().forEach((name, runtimeInfo) ->
                    dto.runtimes.put(name, java.util.Map.of("path", String.valueOf(runtimeInfo.getPath()))));
        }

        dto.ipv4Forwarding = info.getIPv4Forwarding();
        dto.httpProxy = info.getHttpProxy();
        dto.httpsProxy = info.getHttpsProxy();
        dto.noProxy = info.getNoProxy();

        dto.dockerRootDir = info.getDockerRootDir();
        dto.driver = info.getDriver();
        dto.loggingDriver = info.getLoggingDriver();
        dto.driverStatus = info.getDriverStatuses();

        dto.indexServerAddress = info.getIndexServerAddress();
        if (info.getRegistryConfig() != null) {
            dto.registryConfig = new OrchestrationInfoDTO.RegistryConfig();
            dto.registryConfig.insecureRegistryCIDRs = info.getRegistryConfig().getInsecureRegistryCIDRs();
        }
        if (info.getPlugins() != null) {
            dto.plugins = new OrchestrationInfoDTO.Plugins();
            dto.plugins.authorization = info.getPlugins().get("Authorization");
            dto.plugins.volume = info.getPlugins().get("Volume");
            dto.plugins.log = info.getPlugins().get("Log");
            dto.plugins.network = info.getPlugins().get("Network");
        }

        return dto;
    }
}
