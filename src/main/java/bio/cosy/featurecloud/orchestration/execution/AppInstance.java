package bio.cosy.featurecloud.orchestration.execution;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Backend-neutral replacement for docker-java's {@code Container} — a
 * snapshot of one run's state. {@code volumeNames} replaces Docker's
 * {@code Mount} list: callers only ever used it to read back the volume
 * *names* they'd created (e.g. {@code ContainerAppBO.stopContainer}'s hard
 * cleanup, which deletes each mounted volume), never mount-specific detail
 * like the mount type, so the name list is all this needs to carry.
 *
 * <p>{@code address} is the host name other workloads reach the running app
 * on. With Docker that is the container's own name, resolvable on the
 * shared network. A Kubernetes Pod has no such name, so the Kubernetes
 * backend puts a Service in front of each run and reports that Service's
 * name here. It is kept apart from {@code name}, which stays the
 * backend's own identifier for the run.
 *
 * <p>Serialized in Docker's wire shape ({@code Id}, {@code Names},
 * {@code Image}, {@code Labels}, {@code State}, {@code Status},
 * {@code Mounts}), because that is what the clients read:
 * local-learning-api's {@code ContainerDTO} maps exactly those keys, and
 * its {@code BaseOrchestrator.getAppUrl} builds
 * {@code http://<Names[0]>:9000} to talk to the app — so {@code Names}
 * carries {@code address}, not {@code name}.
 */
public record AppInstance(
        @JsonProperty("Id") String id,
        @JsonIgnore String name,
        @JsonProperty("Image") String image,
        @JsonProperty("Labels") Map<String, String> labels,
        @JsonIgnore AppStatus status,
        @JsonIgnore List<String> volumeNames,
        @JsonIgnore String address
) {

    @JsonProperty("Names")
    public List<String> names() {
        return List.of("/" + (address != null ? address : name));
    }

    /** Docker's machine-readable state, e.g. {@code running}. */
    @JsonProperty("State")
    public String state() {
        return switch (status == null ? AppStatus.UNKNOWN : status) {
            case PENDING -> "created";
            case RUNNING -> "running";
            case SUCCEEDED, FAILED -> "exited";
            case UNKNOWN -> "unknown";
        };
    }

    /** Docker's human-readable status, e.g. {@code Exited (1)}. */
    @JsonProperty("Status")
    public String statusText() {
        return switch (status == null ? AppStatus.UNKNOWN : status) {
            case PENDING -> "Created";
            case RUNNING -> "Up";
            case SUCCEEDED -> "Exited (0)";
            case FAILED -> "Exited (1)";
            case UNKNOWN -> "Unknown";
        };
    }

    @JsonProperty("Mounts")
    public List<Map<String, String>> mounts() {
        return volumeNames == null ? List.of()
                : volumeNames.stream().map(v -> Map.of("Name", v, "Type", "volume")).toList();
    }
}
