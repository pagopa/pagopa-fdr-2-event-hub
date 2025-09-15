package it.gov.pagopa.fdr.to.eventhub;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import it.gov.pagopa.fdr.to.eventhub.model.AppInfo;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Azure Functions with Azure Http trigger. */
public class Info {

  private static final String ENVIRONMENT =
      System.getenv().getOrDefault("APP_ENVIRONMENT", "azure-fn");

  private final Logger logger = LoggerFactory.getLogger(Info.class);

  @FunctionName("Info")
  public HttpResponseMessage run(
      @HttpTrigger(
              name = "InfoTrigger",
              methods = {HttpMethod.GET},
              route = "info",
              authLevel = AuthorizationLevel.ANONYMOUS)
          HttpRequestMessage<Optional<String>> request,
      final ExecutionContext context) {

    return request
        .createResponseBuilder(HttpStatus.OK)
        .header("Content-Type", "application/json")
        .body(getInfo())
        .build();
  }

  public synchronized AppInfo getInfo() {
    String version = null;
    String name = null;
    try (InputStream inputStream =
        this.getClass().getClassLoader().getResourceAsStream("application.properties")) {
      Properties properties = new Properties();
      if (inputStream != null) {
        properties.load(inputStream);
        version = properties.getProperty("version", null);
        name = properties.getProperty("name", null);
      }
    } catch (Exception e) {
      logger.error("Impossible to retrieve information from pom.properties file.", e);
    }
    return AppInfo.builder().version(version).environment(ENVIRONMENT).name(name).build();
  }
}
