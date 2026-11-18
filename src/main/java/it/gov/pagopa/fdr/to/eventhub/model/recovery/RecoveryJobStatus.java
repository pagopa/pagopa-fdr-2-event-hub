package it.gov.pagopa.fdr.to.eventhub.model.recovery;

public enum RecoveryJobStatus {
  PENDING,
  RUNNING,
  PAUSING,
  PAUSED,
  COMPLETED,
  FAILED;

  public boolean canTransitionTo(RecoveryJobStatus target) {
    if (target == null) {
      return false;
    }

    return switch (this) {
      case PENDING -> target == RUNNING || target == PAUSED || target == FAILED;
      case RUNNING ->
          target == PAUSING || target == COMPLETED || target == FAILED;
      case PAUSING -> target == PAUSED || target == FAILED;
      case PAUSED -> target == PENDING;
      case COMPLETED, FAILED -> false;
    };
  }

  public RecoveryJobStatus transitionTo(RecoveryJobStatus target) {
    if (!canTransitionTo(target)) {
      throw new IllegalStateException(
          "Invalid recovery job status transition: " + this + " -> " + target);
    }

    return target;
  }
}