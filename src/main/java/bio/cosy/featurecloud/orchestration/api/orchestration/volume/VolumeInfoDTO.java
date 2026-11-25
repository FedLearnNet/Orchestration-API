package bio.cosy.featurecloud.orchestration.api.orchestration.volume;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Backend-agnostic replacement for docker-java's {@code InspectVolumeResponse}
 * in the admin {@code /volume/*} REST contract — that type is Docker-specific
 * and can't represent a Kubernetes PersistentVolumeClaim. Both
 * {@code DockerVolumeAdminAdapter} and {@code KubernetesVolumeAdminService}
 * populate this from whichever backend is active.
 *
 * <p>Serialized in Docker's own wire shape ({@code Name}, {@code Driver},
 * {@code Mountpoint}, {@code Labels}, {@code Options}), so the Docker path answers exactly as
 * before. The Kubernetes backend adds {@code Backend}, {@code Status} (the
 * PVC phase) and {@code SizeBytes}; fields that are null are left out.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VolumeInfoDTO {

    @JsonProperty("Name")
    public String name;

    @JsonProperty("Driver")
    public String driver;

    @JsonProperty("Mountpoint")
    public String mountpoint;

    @JsonProperty("Labels")
    public Map<String, String> labels;

    @JsonProperty("Options")
    public Map<String, String> options;

    @JsonProperty("Backend")
    public String backend; // "docker" or "kubernetes"

    @JsonProperty("Status")
    public String status; // PVC phase (Bound/Pending) on Kubernetes

    @JsonProperty("SizeBytes")
    public Long sizeBytes; // null when unknown/not cheaply computable

    public VolumeInfoDTO() {
    }

    public VolumeInfoDTO(String name, String backend, String status, Long sizeBytes, Map<String, String> labels) {
        this.name = name;
        this.backend = backend;
        this.status = status;
        this.sizeBytes = sizeBytes;
        this.labels = labels;
    }

    /** Docker's own fields, as {@code InspectVolumeResponse} returned them. */
    public static VolumeInfoDTO docker(String name, String driver, String mountpoint, Map<String, String> labels,
                                       Map<String, String> options) {
        VolumeInfoDTO dto = new VolumeInfoDTO(name, "docker", null, null, labels);
        dto.driver = driver;
        dto.mountpoint = mountpoint;
        dto.options = options;
        return dto;
    }
}
