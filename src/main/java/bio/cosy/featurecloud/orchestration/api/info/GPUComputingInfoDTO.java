package bio.cosy.featurecloud.orchestration.api.info;

import lombok.Data;

@Data
public class GPUComputingInfoDTO {
    private int index;
    private String name;
    private String driverVersion;
    private String totalMemory;
    private String usedMemory;
    private String computeUtilization;
    private String memoryUtilization;
}
