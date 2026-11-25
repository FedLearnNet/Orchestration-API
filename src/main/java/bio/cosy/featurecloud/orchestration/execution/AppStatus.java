package bio.cosy.featurecloud.orchestration.execution;

/**
 * Backend-neutral run status. Deliberately coarser than either Docker's
 * container states or Kubernetes' Job/Pod phases — both backends map onto
 * this, not the other way around.
 */
public enum AppStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNKNOWN
}
