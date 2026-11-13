package bio.cosy.featurecloud.orchestration.api.info;

import lombok.Data;

@Data
public class RamComputingInfoDTO {
    private double totalMemoryGb;
    private double usedMemoryGb;
    private double availableMemoryGb;
}