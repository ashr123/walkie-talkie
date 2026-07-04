package io.github.ashr123.walkietalkie.server.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The auto-generated dev certificate must stay valid for all loopback entry points the clients treat as
/// localhost, including IPv6 `::1`; otherwise a reused older cert would keep failing hostname verification.
class TlsConfigurationTest {

	private static final long KEYTOOL_TIMEOUT_SECONDS = 60;
	private static final String PASSWORD = "changeit-dev";
	private static final String DEV_ALIAS = "walkie-dev";
	private static final String DEV_DISTINGUISHED_NAME = "CN=localhost, OU=dev, O=walkie-talkie";

	@TempDir
	Path tempDir;

	private static void generateCertificate(Path keyStore, String subjectAlternativeName)
			throws IOException, InterruptedException {
		runKeytool(List.of(
				"-genkeypair", "-alias", DEV_ALIAS,
				"-keyalg", "EC", "-groupname", "secp384r1",
				"-sigalg", "SHA384withECDSA",
				"-validity", "1",
				"-storetype", "PKCS12",
				"-keystore", keyStore.toString(), "-storepass", PASSWORD,
				"-dname", DEV_DISTINGUISHED_NAME,
				"-ext", subjectAlternativeName));
	}

	// The tests intentionally exercise the same external JDK tool as production (`keytool`), so they verify the
	// exact SAN syntax (`ip:::1`) we ship instead of a hand-rolled certificate builder with different behavior.
	@SuppressWarnings("resource")
	private static void runKeytool(List<String> arguments) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add(Path.of(
				System.getProperty("java.home"),
				"bin",
				System.getProperty("os.name", "").toLowerCase().contains("win")
						? "keytool.exe"
						: "keytool"
		).toString());
		command.addAll(arguments);
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		if (!process.waitFor(KEYTOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IOException("keytool timed out after " + KEYTOOL_TIMEOUT_SECONDS + "s");
		}
		if (process.exitValue() != 0) {
			throw new IOException("keytool exited with status " + process.exitValue() + ": "
					+ new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	@Test
	void aDevCertificateWithIpv6LoopbackIsReusable() throws IOException, InterruptedException {
		Path keyStore = tempDir.resolve("dev-keystore.p12");
		generateCertificate(keyStore, TlsConfiguration.DEV_SUBJECT_ALT_NAME);
		assertTrue(TlsConfiguration.isReusable(keyStore, PASSWORD),
				"the current dev cert shape must be reused across restarts");
	}

	@Test
	void anIpv4OnlyLegacyDevCertificateIsNotReusable() throws IOException, InterruptedException {
		Path keyStore = tempDir.resolve("legacy-dev-keystore.p12");
		generateCertificate(keyStore, "SAN=dns:localhost,ip:127.0.0.1");
		assertFalse(TlsConfiguration.isReusable(keyStore, PASSWORD),
				"an older cert missing ::1 must be regenerated so IPv6 loopback works");
	}
}
