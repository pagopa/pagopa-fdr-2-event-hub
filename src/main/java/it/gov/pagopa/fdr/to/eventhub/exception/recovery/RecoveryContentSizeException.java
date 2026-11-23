package it.gov.pagopa.fdr.to.eventhub.exception.recovery;

import java.io.IOException;
import java.util.Objects;

public final class RecoveryContentSizeException extends IOException {

  private static final long serialVersionUID = 1L;

  public enum Stage {
    COMPRESSED_BLOB,
    DECOMPRESSED_BLOB,
    INNER_XML
  }

  private final Stage stage;
  private final long limitBytes;
  private final long observedBytesAtLeast;

  public RecoveryContentSizeException(
      Stage stage,
      long limitBytes,
      long observedBytesAtLeast) {

    super(stage + " size limit exceeded: limitBytes=" + limitBytes
        + ", observedBytesAtLeast=" + observedBytesAtLeast);

    this.stage = Objects.requireNonNull(stage);
    this.limitBytes = limitBytes;
    this.observedBytesAtLeast = observedBytesAtLeast;
  }

  public Stage stage() {
    return stage;
  }

  public long limitBytes() {
    return limitBytes;
  }

  public long observedBytesAtLeast() {
    return observedBytesAtLeast;
  }
}