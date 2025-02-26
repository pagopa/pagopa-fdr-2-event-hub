package it.gov.pagopa.fdr.to.eventhub.wrapper;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

public class BlobServiceClientWrapperImpl implements BlobServiceClientWrapper {
  @Override
  public BlobContainerClient getBlobContainerClient(String storageEnvVar, String containerName) {
    BlobServiceClient blobServiceClient =
        new BlobServiceClientBuilder().connectionString(System.getenv(storageEnvVar)).buildClient();
    return blobServiceClient.getBlobContainerClient(containerName);
  }
}
