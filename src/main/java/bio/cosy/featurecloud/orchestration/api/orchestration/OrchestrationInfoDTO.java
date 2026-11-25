package bio.cosy.featurecloud.orchestration.api.orchestration;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Backend-agnostic replacement for docker-java's {@code Info} in
 * {@code GET /docker/info}. Field names deliberately mirror docker-java's
 * {@code Info} wire format (same {@code @JsonProperty} names) because the
 * admin dashboard (frontend-shared's {@code InfoDTO} /
 * {@code admin-docker-kpis.component.ts}) binds to those exact keys — this
 * type has to stay wire-compatible with it, not just internally tidy.
 * <p>
 * Both backends populate whichever fields are meaningful for them.
 * Kubernetes genuinely has no equivalent for several of these (a single
 * daemon's storage driver, proxy config, root dir, registry/plugin config,
 * cgroup capability flags, goroutine/fd counts) — those are left null on
 * purpose, which the dashboard already renders as "—".
 */
public class OrchestrationInfoDTO {

    @JsonProperty("ServerVersion")
    public String serverVersion;
    @JsonProperty("OperatingSystem")
    public String operatingSystem;
    @JsonProperty("OSType")
    public String osType;
    @JsonProperty("KernelVersion")
    public String kernelVersion;
    @JsonProperty("Architecture")
    public String architecture;
    @JsonProperty("Name")
    public String name;
    @JsonProperty("SystemTime")
    public String systemTime;

    @JsonProperty("NCPU")
    public Integer ncpu;
    @JsonProperty("MemTotal")
    public Long memTotal;
    @JsonProperty("MemoryLimit")
    public Boolean memoryLimit;
    @JsonProperty("SwapLimit")
    public Boolean swapLimit;
    @JsonProperty("OomKillDisable")
    public Boolean oomKillDisable;
    @JsonProperty("NGoroutines")
    public Integer nGoroutines;
    @JsonProperty("NFd")
    public Integer nFd;

    @JsonProperty("ContainersRunning")
    public Integer containersRunning;
    @JsonProperty("ContainersPaused")
    public Integer containersPaused;
    @JsonProperty("ContainersStopped")
    public Integer containersStopped;
    @JsonProperty("Containers")
    public Integer containers;
    @JsonProperty("Images")
    public Integer images;

    @JsonProperty("SecurityOptions")
    public List<String> securityOptions;
    @JsonProperty("Isolation")
    public String isolation;
    @JsonProperty("Runtimes")
    public Map<String, Map<String, String>> runtimes;

    @JsonProperty("IPv4Forwarding")
    public Boolean ipv4Forwarding;
    @JsonProperty("HttpProxy")
    public String httpProxy;
    @JsonProperty("HttpsProxy")
    public String httpsProxy;
    @JsonProperty("NoProxy")
    public String noProxy;

    @JsonProperty("DockerRootDir")
    public String dockerRootDir;
    @JsonProperty("Driver")
    public String driver;
    @JsonProperty("LoggingDriver")
    public String loggingDriver;
    @JsonProperty("DriverStatus")
    public List<List<String>> driverStatus;

    @JsonProperty("IndexServerAddress")
    public String indexServerAddress;
    @JsonProperty("RegistryConfig")
    public RegistryConfig registryConfig;
    @JsonProperty("Plugins")
    public Plugins plugins;

    public static class RegistryConfig {
        @JsonProperty("InsecureRegistryCIDRs")
        public List<String> insecureRegistryCIDRs;
    }

    public static class Plugins {
        @JsonProperty("Authorization")
        public List<String> authorization;
        @JsonProperty("Volume")
        public List<String> volume;
        @JsonProperty("Log")
        public List<String> log;
        @JsonProperty("Network")
        public List<String> network;
    }
}
