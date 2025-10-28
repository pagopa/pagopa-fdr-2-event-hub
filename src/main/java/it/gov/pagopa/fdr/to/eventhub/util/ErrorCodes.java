package it.gov.pagopa.fdr.to.eventhub.util;

import lombok.Getter;

@Getter
public enum ErrorCodes {

  // Common Errors
  COMMON_E1("FDR-E1", "Error while sending to EventHub."),
  COMMON_E2("FDR-E2", "Error while process XML Blob."),
  // FDR1 Errors
  FDR1_E1("FDR1-E1", "Error processing Blob in processFDR1BlobFiles function"),
  FDR1_E1_1("FDR1-E1", "Error processing Blob in processFDR1BlobFiles function. Blob not in GZIP format"),
  // FDR3 Errors
  FDR3_E1("FDR3-E1", "Error processing Blob in processFDR3BlobFiles function"),
  FDR3_E1_1("FDR3-E1", "Error processing Blob in processFDR3BlobFiles function. Blob not in GZIP format");

  private final String code;
  private final String message;

  ErrorCodes(String code, String message) {
    this.code = code;
    this.message = message;
  }
}
