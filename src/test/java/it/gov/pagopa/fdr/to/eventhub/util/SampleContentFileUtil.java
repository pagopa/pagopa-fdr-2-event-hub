package it.gov.pagopa.fdr.to.eventhub.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.GZIPOutputStream;

import lombok.experimental.UtilityClass;

@UtilityClass
public class SampleContentFileUtil {

  public static String getSampleXml(String filename) throws Exception {
    Path path = Paths.get(ClassLoader.getSystemResource(filename).toURI());
    return Files.readString(path);
  }

  public static InputStream getSamplePomProperties() throws Exception {
    Path path = Paths.get(ClassLoader.getSystemResource("pom.properties").toURI());
    return new ByteArrayInputStream(Files.readString(path).getBytes(StandardCharsets.UTF_8));
  }

	public static byte[] createGzipCompressedData(String input)
			throws Exception {
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(
				byteArrayOutputStream)) {
			gzipOutputStream.write(input.getBytes(StandardCharsets.UTF_8));
		}
		return byteArrayOutputStream.toByteArray();
	}
}
