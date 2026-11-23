package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.azure.core.credential.TokenCredential;
import com.microsoft.azure.kusto.data.Client;
import com.microsoft.azure.kusto.data.auth.ConnectionStringBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RecoveryAdxClientFactoryTest {

  private static final String CLUSTER =
      "https://recovery-test.westeurope.kusto.windows.net";

  private final Client client = mock(Client.class);

  private final TokenCredential credential =
      mock(TokenCredential.class);

  @SuppressWarnings("unchecked")
  private final Supplier<TokenCredential> credentialFactory =
      mock(Supplier.class);

  @SuppressWarnings("unchecked")
  private final Function<ConnectionStringBuilder, Client> sdkFactory =
      mock(Function.class);

  @BeforeEach
  void setUp() {
    when(credentialFactory.get()).thenReturn(credential);
    when(sdkFactory.apply(any(ConnectionStringBuilder.class)))
        .thenReturn(client);
  }

  private RecoveryAdxClientFactory factory(
      RecoveryAdxConfiguration.AuthMode mode) {

    RecoveryAdxConfiguration configuration =
        new RecoveryAdxConfiguration(CLUSTER, "recovery-test", mode, 60);

    return new RecoveryAdxClientFactory(
        configuration, credentialFactory, sdkFactory);
  }

  @Test
  void shouldDeferClientAndCredentialCreation() {
    factory(RecoveryAdxConfiguration.AuthMode.WORKLOAD_IDENTITY);

    verifyNoInteractions(credentialFactory, sdkFactory, client);
  }

  @Test
  void shouldCreateAzureCliClientOnceWithoutWorkloadCredential() {
    RecoveryAdxClientFactory resources =
        factory(RecoveryAdxConfiguration.AuthMode.AZURE_CLI);

    assertSame(client, resources.client());
    assertSame(client, resources.client());

    ArgumentCaptor<ConnectionStringBuilder> captor =
        ArgumentCaptor.forClass(ConnectionStringBuilder.class);

    verify(sdkFactory, times(1)).apply(captor.capture());
    verifyNoInteractions(credentialFactory);

    ConnectionStringBuilder connection = captor.getValue();

    assertEquals(CLUSTER, connection.getClusterUrl());
    assertNull(connection.getCustomTokenCredential());
  }

  @Test
  void shouldCreateWorkloadIdentityClientOnce() {
    RecoveryAdxClientFactory resources =
        factory(RecoveryAdxConfiguration.AuthMode.WORKLOAD_IDENTITY);

    assertSame(client, resources.client());
    assertSame(client, resources.client());

    ArgumentCaptor<ConnectionStringBuilder> captor =
        ArgumentCaptor.forClass(ConnectionStringBuilder.class);

    verify(sdkFactory, times(1)).apply(captor.capture());
    verify(credentialFactory, times(1)).get();

    ConnectionStringBuilder connection = captor.getValue();

    assertEquals(CLUSTER, connection.getClusterUrl());
    assertSame(credential, connection.getCustomTokenCredential());
  }

  @Test
  void shouldAllowNewAttemptAfterInitializationFailure() {
    RecoveryAdxClientFactory resources =
        factory(RecoveryAdxConfiguration.AuthMode.AZURE_CLI);

    IllegalStateException failure =
        new IllegalStateException("Simulated initialization failure");

    when(sdkFactory.apply(any(ConnectionStringBuilder.class)))
        .thenThrow(failure)
        .thenReturn(client);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            resources::client);

    assertSame(failure, thrown);
    assertSame(client, resources.client());

    verify(sdkFactory, times(2))
        .apply(any(ConnectionStringBuilder.class));
  }

  @Test
  void shouldRejectNullClientFromFactory() {
    RecoveryAdxClientFactory resources =
        factory(RecoveryAdxConfiguration.AuthMode.AZURE_CLI);

    when(sdkFactory.apply(any(ConnectionStringBuilder.class)))
        .thenReturn(null);

    assertThrows(
        NullPointerException.class,
        resources::client);
  }

  @Test
  void shouldCreateOnlyOneClientForConcurrentAccess() throws Exception {
    RecoveryAdxClientFactory resources =
        factory(RecoveryAdxConfiguration.AuthMode.WORKLOAD_IDENTITY);

    ExecutorService executor = Executors.newFixedThreadPool(4);

    try {
      List<Callable<Client>> calls = new ArrayList<>();

      for (int i = 0; i < 20; i++) {
        calls.add(resources::client);
      }

      List<Future<Client>> results = executor.invokeAll(calls);

      for (Future<Client> result : results) {
        assertSame(client, result.get());
      }

      verify(sdkFactory, times(1))
          .apply(any(ConnectionStringBuilder.class));

      verify(credentialFactory, times(1)).get();
    } finally {
      executor.shutdownNow();
    }
  }
}