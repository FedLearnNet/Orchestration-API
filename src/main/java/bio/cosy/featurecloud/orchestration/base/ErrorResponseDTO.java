package bio.cosy.featurecloud.orchestration.base;

public record ErrorResponseDTO(
        int status,
        String error,
        String message
) {
}