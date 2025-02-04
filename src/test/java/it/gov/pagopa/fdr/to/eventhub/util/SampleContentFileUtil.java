package it.gov.pagopa.fdr.to.eventhub.util;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.experimental.UtilityClass;

@UtilityClass
public class SampleContentFileUtil {

  public static String getSampleXml() throws Exception {
    Path path = Paths.get(ClassLoader.getSystemResource("sample.xml").toURI());
    return Files.readString(path);
  }

  public static InputStream getSamplePomProperties() throws Exception {
    Path path = Paths.get(ClassLoader.getSystemResource("pom.properties").toURI());
    return new ByteArrayInputStream(Files.readString(path).getBytes(StandardCharsets.UTF_8));
  }
}
