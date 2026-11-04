package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RecoveryDryRunRuntimeProviderTest {

  private final RecoveryDryRunRuntime runtime =
      mock(RecoveryDryRunRuntime.class);

  @SuppressWarnings("unchecked")
  private final Supplier<RecoveryDryRunRuntime> factory =
      mock(Supplier.class);

  @SuppressWarnings("unchecked")
  private final Consumer<Runnable> registrar =
      mock(Consumer.class);

  private final RecoveryDryRunRuntimeProvider provider =
      new RecoveryDryRunRuntimeProvider(factory, registrar);

  @Test
  void shouldNotCreateRuntimeDuringConstruction() {
    verifyNoInteractions(factory, registrar, runtime);
  }

  @Test
  void shouldCreateRuntimeOnceAndReuseIt() {
    when(factory.get()).thenReturn(runtime);

    assertSame(runtime, provider.get());
    assertSame(runtime, provider.get());

    verify(factory).get();
    verify(registrar).accept(any(Runnable.class));
  }

  @Test
  void shouldCloseInitializedRuntimeOnlyOnce() {
    when(factory.get()).thenReturn(runtime);

    provider.get();
    provider.close();
    provider.close();

    verify(runtime).close();
  }

  @Test
  void shouldCloseUnusedProviderWithoutCreatingRuntime() {
    provider.close();

    verifyNoInteractions(factory, registrar, runtime);
  }

  @Test
  void shouldRejectAccessAfterClose() {
    when(factory.get()).thenReturn(runtime);

    provider.get();
    provider.close();

    assertThrows(
        IllegalStateException.class,
        provider::get);

    verify(factory).get();
  }

  @Test
  void shouldAllowRetryAfterFactoryFailure() {
    IllegalStateException failure =
        new IllegalStateException("Simulated initialization failure");

    when(factory.get())
        .thenThrow(failure)
        .thenReturn(runtime);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            provider::get);

    assertSame(failure, thrown);
    assertSame(runtime, provider.get());

    verify(registrar).accept(any(Runnable.class));
  }

  @Test
  void shouldCloseRuntimeWhenHookRegistrationFailsAndAllowRetry() {
    RecoveryDryRunRuntime replacement =
        mock(RecoveryDryRunRuntime.class);

    IllegalStateException failure =
        new IllegalStateException("Simulated hook registration failure");

    when(factory.get()).thenReturn(runtime, replacement);

    doThrow(failure)
        .doNothing()
        .when(registrar)
        .accept(any(Runnable.class));

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            provider::get);

    assertSame(failure, thrown);
    verify(runtime).close();

    assertSame(replacement, provider.get());
  }

  @Test
  void shouldCloseProviderThroughRegisteredShutdownAction() {
    when(factory.get()).thenReturn(runtime);

    provider.get();

    ArgumentCaptor<Runnable> captor =
        ArgumentCaptor.forClass(Runnable.class);

    verify(registrar).accept(captor.capture());

    captor.getValue().run();

    verify(runtime).close();

    assertThrows(
        IllegalStateException.class,
        provider::get);
  }
}