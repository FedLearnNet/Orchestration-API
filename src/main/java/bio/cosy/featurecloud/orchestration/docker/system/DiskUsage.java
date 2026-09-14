package bio.cosy.featurecloud.orchestration.docker.system;

import lombok.Data;

import java.util.List;

@Data
public class DiskUsage {
    private Long layersSize;
    private List<Object> images;
    private List<Object> containers;
    private List<Object> volumes;
    private List<Object> buildCache;

}
