package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import com.azure.storage.blob.BlobContainerClient;

import it.gov.pagopa.fdr.to.eventhub.exception.recovery.RecoveryContentSizeException;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;

public final class RecoveryFdr3BlobContentRepository {

  private final RecoveryBlobContentRepository delegate;

  public RecoveryFdr3BlobContentRepository(
      BlobContainerClient container,
      int maxCompressedBytes) {

    delegate =
        new RecoveryBlobContentRepository(
            container,
            "fdr3-flows",
            maxCompressedBytes);
  }

  public byte[] download(RecoveryBlobSnapshot snapshot) throws RecoveryContentSizeException {
    return delegate.download(snapshot);
  }
}