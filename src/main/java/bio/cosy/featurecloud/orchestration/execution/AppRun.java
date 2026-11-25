package bio.cosy.featurecloud.orchestration.execution;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Backend-neutral replacement for docker-java's {@code CreateContainerResponse}
 * — the result of asking {@link AppExecutionService} to start a run. Callers
 * that only need the id (the overwhelming majority — see
 * {@code ContainerAppBO}) can use this directly instead of a Docker-specific
 * type.
 *
 * <p>Serialized in Docker's own wire shape ({@code Id}, {@code Warnings}),
 * not as a plain record. local-learning-api reads this response as its
 * {@code CreateContainerResponseDTO}, whose fields are
 * {@code @JsonProperty("Id")}/{@code ("Warnings")}; a lowercase {@code id}
 * deserializes there as null, and it then refuses the run with
 * "Failed to start container". Both backends answer in this one shape.
 */
public record AppRun(@JsonProperty("Id") String id) {

    @JsonProperty("Warnings")
    public List<String> warnings() {
        return List.of();
    }
}
