package it.gov.pagopa.fdr.to.eventhub.wrapper;

import com.azure.storage.blob.BlobContainerClient;

// Interface needed for junit test (see:
// https://github.com/Azure/azure-sdk-for-java/issues/5017#:~:text=As%20a%20consumer,of%20final%20classes.)
public interface BlobServiceClientWrapper {
  BlobContainerClient getBlobContainerClient(String storageEnvVar, String containerName);
}
