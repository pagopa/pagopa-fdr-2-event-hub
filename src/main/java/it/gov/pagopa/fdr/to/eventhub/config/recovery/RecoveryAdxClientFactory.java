package it.gov.pagopa.fdr.to.eventhub.config.recovery;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.WorkloadIdentityCredentialBuilder;
import com.microsoft.azure.kusto.data.Client;
import com.microsoft.azure.kusto.data.ClientFactory;
import com.microsoft.azure.kusto.data.auth.ConnectionStringBuilder;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

public final class RecoveryAdxClientFactory {

  private final RecoveryAdxConfiguration configuration;
  private final Supplier<TokenCredential> workloadCredentialFactory;
  private final Function<ConnectionStringBuilder, Client> clientFactory;

  private Client client;

  public RecoveryAdxClientFactory(
      RecoveryAdxConfiguration configuration) {

    this(
        configuration,
        () -> new WorkloadIdentityCredentialBuilder().build(),
        RecoveryAdxClientFactory::createSdkClient);
  }

  RecoveryAdxClientFactory(
      RecoveryAdxConfiguration configuration,
      Supplier<TokenCredential> workloadCredentialFactory,
      Function<ConnectionStringBuilder, Client> clientFactory) {

    this.configuration =
        Objects.requireNonNull(configuration, "configuration is required");

    this.workloadCredentialFactory =
        Objects.requireNonNull(
            workloadCredentialFactory,
            "workloadCredentialFactory is required");

    this.clientFactory =
        Objects.requireNonNull(clientFactory, "clientFactory is required");
  }

  public synchronized Client client() {
    if (client == null) {
      ConnectionStringBuilder connection =
          switch (configuration.authMode()) {
            case AZURE_CLI ->
                ConnectionStringBuilder.createWithAzureCli(
                    configuration.clusterUrl());

            case WORKLOAD_IDENTITY ->
                ConnectionStringBuilder.createWithTokenCredential(
                    configuration.clusterUrl(),
                    Objects.requireNonNull(
                        workloadCredentialFactory.get(),
                        "workloadCredentialFactory must return a credential"));
          };

      client =
          Objects.requireNonNull(
              clientFactory.apply(connection),
              "clientFactory must return a client");
    }

    return client;
  }

  private static Client createSdkClient(
      ConnectionStringBuilder connection) {

    try {
      return ClientFactory.createClient(connection);
    } catch (URISyntaxException exception) {
      throw new IllegalStateException(
          "Unable to initialize the recovery ADX client",
          exception);
    }
  }
}