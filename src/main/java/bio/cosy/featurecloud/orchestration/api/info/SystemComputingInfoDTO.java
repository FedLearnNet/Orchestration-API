package bio.cosy.featurecloud.orchestration.api.info;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SystemComputingInfoDTO {
    private Boolean gpuAvailable;
    CpuComputingInfoDTO cpuDetails;
    RamComputingInfoDTO ramDetails;
    private List<GPUComputingInfoDTO> gpuHardwareInfo;

    public void setGPU(List<GPUComputingInfoDTO> gpuHardwareInfo) {
        if (gpuHardwareInfo == null || gpuHardwareInfo.isEmpty()) {
            this.gpuAvailable = false;
            this.gpuHardwareInfo = new ArrayList<>();
        } else {
            this.gpuAvailable = true;
            this.gpuHardwareInfo = gpuHardwareInfo;
        }
    }

}
