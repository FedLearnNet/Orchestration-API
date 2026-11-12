package bio.cosy.featurecloud.orchestration.api.info;

import lombok.Data;

@Data
public class CpuComputingInfoDTO {
    private String modelName;
    private int logicalCores; // Total number of threads
    private String architecture;
    private double usedCores; // 1.0 = one fully busy core
    private double freeCores;
    private double usagePercent; // 0-100 across all cores
}