package bio.cosy.featurecloud.orchestration.api.info;

import bio.cosy.featurecloud.orchestration.service.DockerOrchestration;
import com.github.dockerjava.api.model.Info;
import com.sun.management.OperatingSystemMXBean;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@ApplicationScoped
public class SystemComputingInfoBO {

    @Inject
    DockerOrchestration dockerOrchestration;

    public SystemComputingInfoDTO get() {
        SystemComputingInfoDTO result = new SystemComputingInfoDTO();

        Info dockerInfo = null;
        try {
            dockerInfo = dockerOrchestration.getInfo();
        } catch (Exception e) {
            Log.warn("Could not retrieve Docker info, falling back entirely to native container metrics.", e);
        }

        result.setCpuDetails(getCpuDetails(dockerInfo));
        result.setRamDetails(getRamDetails(dockerInfo));

        result.setGPU(getGpuDetails());

        return result;
    }

    public CpuComputingInfoDTO getCpuDetails(Info dockerInfo) {
        CpuComputingInfoDTO dto = new CpuComputingInfoDTO();
        String os = System.getProperty("os.name").toLowerCase();
        dto.setArchitecture(System.getProperty("os.arch"));

        int nativeCoreCount = Runtime.getRuntime().availableProcessors();

        try {
            if (os.contains("mac")) {
                Process process = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").start();
                process.waitFor();
                dto.setModelName(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
            } else if (os.contains("win")) {
                Process process = new ProcessBuilder("wmic", "cpu", "get", "name").start();
                process.waitFor();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                String[] lines = output.split("\n");
                dto.setModelName(lines.length > 1 ? lines[1].trim() : output);
            } else {
                int linuxCoreCount = 0;
                String modelName = "Unknown CPU";
                try (Stream<String> stream = Files.lines(Paths.get("/proc/cpuinfo"))) {
                    for (String line : stream.toList()) {
                        if (line.startsWith("processor")) {
                            linuxCoreCount++;
                        } else if (line.startsWith("model name") || line.startsWith("Hardware") || line.startsWith("Processor")) {
                            modelName = line.substring(line.indexOf(":") + 1).trim();
                        }
                    }
                }
                dto.setModelName(modelName);
                if (linuxCoreCount > 0) {
                    nativeCoreCount = linuxCoreCount;
                }
            }
        } catch (Exception e) {
            dto.setModelName("Failed to get CPU: " + e.getMessage());
        }

        if (dockerInfo != null && dockerInfo.getNCPU() != null) {
            dto.setLogicalCores(dockerInfo.getNCPU());
        } else {
            dto.setLogicalCores(nativeCoreCount);
        }

        return dto;
    }

    public RamComputingInfoDTO getRamDetails(Info dockerInfo) {
        RamComputingInfoDTO dto = new RamComputingInfoDTO();
        String os = System.getProperty("os.name").toLowerCase();

        double nativeTotalGb = 0;
        double nativeAvailableGb = 0;

        if (os.contains("mac") || os.contains("win")) {
            OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
            nativeTotalGb = bytesToGb(osBean.getTotalMemorySize());
            nativeAvailableGb = bytesToGb(osBean.getFreeMemorySize());
        } else {
            long totalKb = 0;
            long availableKb = 0;
            try (Stream<String> stream = Files.lines(Paths.get("/proc/meminfo"))) {
                for (String line : stream.toList()) {
                    if (line.startsWith("MemTotal:")) {
                        totalKb = parseKb(line);
                    } else if (line.startsWith("MemAvailable:")) {
                        availableKb = parseKb(line);
                    }
                }
            } catch (Exception ignored) {
            }
            nativeTotalGb = kbToGb(totalKb);
            nativeAvailableGb = kbToGb(availableKb);
        }

        double finalTotalGb = nativeTotalGb;
        if (dockerInfo != null && dockerInfo.getMemTotal() != null) {
            finalTotalGb = bytesToGb(dockerInfo.getMemTotal());
        }

        double finalUsedGb = finalTotalGb - nativeAvailableGb;
        if (finalUsedGb < 0) finalUsedGb = 0;

        dto.setTotalMemoryGb(roundToTwoDecimals(finalTotalGb));
        dto.setAvailableMemoryGb(roundToTwoDecimals(nativeAvailableGb));
        dto.setUsedMemoryGb(roundToTwoDecimals(finalUsedGb));

        return dto;
    }

    public List<GPUComputingInfoDTO> getGpuDetails() {
        List<GPUComputingInfoDTO> gpus = new ArrayList<>();
        try {
            String query = "index,name,driver_version,memory.total,memory.used,utilization.gpu,utilization.memory";
            Process process = new ProcessBuilder(
                    "nvidia-smi",
                    "--query-gpu=" + query,
                    "--format=csv,noheader"
            ).start();
            process.waitFor();

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (output.isEmpty() || output.startsWith("NVIDIA-SMI has failed")) {
                return gpus; // Returning empty array safely triggers your boolean logic
            }

            String[] lines = output.split("\n");
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length >= 7) {
                    GPUComputingInfoDTO dto = new GPUComputingInfoDTO();
                    dto.setIndex(Integer.parseInt(parts[0].trim()));
                    dto.setName(parts[1].trim());
                    dto.setDriverVersion(parts[2].trim());
                    dto.setTotalMemory(parts[3].trim());
                    dto.setUsedMemory(parts[4].trim());
                    dto.setComputeUtilization(parts[5].trim());
                    dto.setMemoryUtilization(parts[6].trim());
                    gpus.add(dto);
                }
            }
        } catch (Exception e) {
            Log.error("Could not execute nvidia-smi: " + e.getMessage());
        }
        return gpus;
    }

    private long parseKb(String line) {
        String numberOnly = line.replaceAll("[^0-9]", "");
        return numberOnly.isEmpty() ? 0 : Long.parseLong(numberOnly);
    }

    private double bytesToGb(long bytes) {
        return bytes / (1024.0 * 1024.0 * 1024.0);
    }

    private double kbToGb(long kb) {
        return kb / (1024.0 * 1024.0);
    }

    private double roundToTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}