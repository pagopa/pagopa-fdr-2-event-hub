package it.gov.pagopa.fdr.to.eventhub.repository.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.http.rest.PagedIterable;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobItemProperties;
import com.azure.storage.blob.models.ListBlobsOptions;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryBlobSnapshot;
import it.gov.pagopa.fdr.to.eventhub.model.recovery.RecoveryFlowCandidate;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class RecoveryFdr1BlobRepositoryTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private static final RecoveryFlowCandidate CANDIDATE =
      new RecoveryFlowCandidate(
          1L, "flow-1", 2L, "PSP-A", "ORG-A",
          LocalDateTime.of(2026, 10, 1, 12, 0));

  private final BlobContainerClient container =
      mock(BlobContainerClient.class);

  @SuppressWarnings("unchecked")
  private final PagedIterable<BlobItem> listing =
      mock(PagedIterable.class);

  private RecoveryFdr1BlobRepository repository;

  @BeforeEach
  void setUp() {
    when(container.getBlobContainerName())
        .thenReturn("fdr1-flows");

    when(container.listBlobs(
            any(ListBlobsOptions.class), eq(TIMEOUT)))
        .thenReturn(listing);

    givenItems(List.of());

    repository = new RecoveryFdr1BlobRepository(container, 2);
  }

  private void givenItems(List<BlobItem> items) {
    when(listing.iterator()).thenReturn(items.iterator());
  }

  private BlobItem item(
      String name,
      Map<String, String> metadata) {

    BlobItem item = mock(BlobItem.class);
    BlobItemProperties properties = mock(BlobItemProperties.class);

    when(item.getName()).thenReturn(name);
    when(item.getMetadata()).thenReturn(metadata);
    when(item.getProperties()).thenReturn(properties);
    when(properties.getETag()).thenReturn("\"source-etag\"");
    when(properties.getContentLength()).thenReturn(42L);

    return item;
  }

  @Test
  void shouldUseFlowPrefixAndKeepBothElaborateValues() {
    givenItems(List.of(
        item("flow-1_a.xml.zip", Map.of("elaborate", "true")),
        item("flow-1_b.xml.zip", Map.of("elaborate", "false"))));

    List<RecoveryBlobSnapshot> result =
        repository.listCandidates(CANDIDATE);

    assertEquals(2, result.size());
    assertEquals("true", result.get(0).metadata().get("elaborate"));
    assertEquals("false", result.get(1).metadata().get("elaborate"));
    assertEquals("fdr1-flows", result.get(0).containerName());
    assertEquals(42L, result.get(0).contentLength());
    assertEquals("\"source-etag\"", result.get(0).etag());

    ArgumentCaptor<ListBlobsOptions> options =
        ArgumentCaptor.forClass(ListBlobsOptions.class);

    verify(container).listBlobs(options.capture(), eq(TIMEOUT));

    assertEquals("flow-1_", options.getValue().getPrefix());
    assertEquals(
        Integer.valueOf(3),
        options.getValue().getMaxResultsPerPage());
    assertTrue(options.getValue().getDetails().getRetrieveMetadata());
  }

  @Test
  void shouldReturnEmptyWhenNoCandidatesExist() {
    assertTrue(repository.listCandidates(CANDIDATE).isEmpty());
  }

  @Test
  void shouldRejectExcessCandidatesInsteadOfReturningPartialResults() {
    givenItems(List.of(
        item("flow-1_a.xml.zip", Map.of()),
        item("flow-1_b.xml.zip", Map.of()),
        item("flow-1_c.xml.zip", Map.of())));

    assertThrows(
        IllegalStateException.class,
        () -> repository.listCandidates(CANDIDATE));
  }

  @Test
  void shouldAcceptExactlyTheConfiguredLimit() {
    givenItems(List.of(
        item("flow-1_a.xml.zip", Map.of()),
        item("flow-1_b.xml.zip", Map.of())));

    assertEquals(2, repository.listCandidates(CANDIDATE).size());
  }

  @Test
  void shouldPreserveCandidatesWithoutMetadata() {
    givenItems(List.of(item("flow-1_a.xml.zip", null)));

    assertEquals(
        Map.of(),
        repository.listCandidates(CANDIDATE).get(0).metadata());
  }

  @Test
  void shouldPropagateListingFailures() {
    IllegalStateException failure =
        new IllegalStateException("Simulated listing failure");

    when(listing.iterator()).thenThrow(failure);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> repository.listCandidates(CANDIDATE));

    assertSame(failure, thrown);
  }

  @Test
  void shouldRejectMissingProperties() {
    BlobItem invalid = mock(BlobItem.class);
    givenItems(List.of(invalid));

    assertThrows(
        IllegalStateException.class,
        () -> repository.listCandidates(CANDIDATE));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 1001})
  void shouldRejectInvalidLimit(int limit) {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryFdr1BlobRepository(container, limit));
  }

  @Test
  void shouldRejectNullCandidate() {
    assertThrows(
        NullPointerException.class,
        () -> repository.listCandidates(null));
  }

  @Test
  void shouldRejectWrongContainer() {
    BlobContainerClient wrong = mock(BlobContainerClient.class);
    when(wrong.getBlobContainerName()).thenReturn("fdr3-flows");

    assertThrows(
        IllegalArgumentException.class,
        () -> new RecoveryFdr1BlobRepository(wrong, 2));
  }
}