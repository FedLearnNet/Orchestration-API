package bio.cosy.featurecloud.orchestration.api.info;

import lombok.Data;

@Data
public class CpuComputingInfoDTO {
    private String modelName;
    private int logicalCores; // Total number of threads
    private String architecture;
}