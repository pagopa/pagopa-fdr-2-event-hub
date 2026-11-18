package it.gov.pagopa.fdr.to.eventhub.model.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class RecoveryJobStatusTest {

  private static final Set<String> ALLOWED_TRANSITIONS =
      Set.of(
          "PENDING->RUNNING",
          "PENDING->PAUSED",
          "PENDING->FAILED",
          "RUNNING->PAUSING",
          "RUNNING->COMPLETED",
          "RUNNING->FAILED",
          "PAUSING->PAUSED",
          "PAUSING->FAILED",
          "PAUSED->PENDING");

  static Stream<Arguments> transitions() {
    return Arrays.stream(RecoveryJobStatus.values())
        .flatMap(
            source ->
                Arrays.stream(RecoveryJobStatus.values())
                    .map(
                        target ->
                            Arguments.of(
                                source,
                                target,
                                ALLOWED_TRANSITIONS.contains(
                                    source + "->" + target))));
  }

  @ParameterizedTest(name = "{0} -> {1}: allowed={2}")
  @MethodSource("transitions")
  void shouldEnforceAllowedTransitions(
      RecoveryJobStatus source,
      RecoveryJobStatus target,
      boolean allowed) {

    assertEquals(allowed, source.canTransitionTo(target));

    if (allowed) {
      assertEquals(target, source.transitionTo(target));
    } else {
      assertThrows(
          IllegalStateException.class,
          () -> source.transitionTo(target));
    }
  }

  @ParameterizedTest
  @EnumSource(RecoveryJobStatus.class)
  void shouldRejectNullTarget(RecoveryJobStatus source) {
    assertFalse(source.canTransitionTo(null));

    assertThrows(
        IllegalStateException.class,
        () -> source.transitionTo(null));
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryJobStatus.class,
      names = {"COMPLETED", "FAILED"})
  void shouldPreventRestartingTerminalJobs(RecoveryJobStatus source) {
    assertFalse(source.canTransitionTo(RecoveryJobStatus.PENDING));
    assertFalse(source.canTransitionTo(RecoveryJobStatus.RUNNING));
  }

  @ParameterizedTest
  @EnumSource(
      value = RecoveryJobStatus.class,
      names = {"PENDING", "RUNNING"})
  void shouldRequireDifferentPausePaths(RecoveryJobStatus source) {
    if (source == RecoveryJobStatus.PENDING) {
      assertTrue(source.canTransitionTo(RecoveryJobStatus.PAUSED));
    } else {
      assertTrue(source.canTransitionTo(RecoveryJobStatus.PAUSING));
      assertFalse(source.canTransitionTo(RecoveryJobStatus.PAUSED));
    }
  }
}