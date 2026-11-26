package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.data.tables.TableClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryAdxRepository;
import it.gov.pagopa.fdr.to.eventhub.repository.recovery.RecoveryFlowRepository;
import org.junit.jupiter.api.Test;

class RecoveryPublicationComponentsFactoryTest {
  @Test
  void shouldConstructDiscoveryFor500CandidatesWithoutNetworkCalls() {
    var tables = mock(RecoveryTableClients.class);
    when(tables.jobs()).thenReturn(mock(TableClient.class));
    when(tables.items()).thenReturn(mock(TableClient.class));
    var storage = mock(BlobServiceClient.class);
    when(storage.getBlobContainerClient(anyString())).thenAnswer(invocation -> {
      var container = mock(BlobContainerClient.class);
      when(container.getBlobContainerName()).thenReturn(invocation.getArgument(0));
      return container;
    });
    var components = RecoveryPublicationComponentsFactory.createComponents(
        tables, storage, mock(RecoveryFlowRepository.class), mock(RecoveryAdxRepository.class),
        new RecoveryPublicationConfiguration(1048576, 8388608, 8388608, 20), 500);
    assertNotNull(components.persisted());
    assertNotNull(components.discovery());
  }

  @Test
  void shouldRejectPageAbove500() {
    assertThrows(IllegalArgumentException.class,
        () -> RecoveryPublicationComponentsFactory.createComponents(
            mock(RecoveryTableClients.class), mock(BlobServiceClient.class),
            mock(RecoveryFlowRepository.class), mock(RecoveryAdxRepository.class),
            new RecoveryPublicationConfiguration(1048576, 8388608, 8388608, 20), 501));
  }
}
