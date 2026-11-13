package bio.cosy.featurecloud.orchestration.events;

import lombok.AllArgsConstructor;
import lombok.Getter;

// A container of this system stopped; exitCode is null when Docker did not report one.
@Getter
@AllArgsConstructor
public class ContainerDiedEvent {
    private final String containerId;
    private final Integer exitCode;
    private final boolean pipeline;
}
