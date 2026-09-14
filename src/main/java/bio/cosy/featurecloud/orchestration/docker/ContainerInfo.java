package bio.cosy.featurecloud.orchestration.docker;

import lombok.Data;

@Data
public class ContainerInfo {
    private String containerId;
    private String image;
    private String name;
    private String ipAddress;
}
