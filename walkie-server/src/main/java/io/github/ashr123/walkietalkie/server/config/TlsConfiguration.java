package io.github.ashr123.walkietalkie.server.config;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.TimeUnit;

/// Makes the embedded server speak **HTTPS (and therefore WSS) by default**, so all client↔server traffic —
/// control messages, the binary audio frames, the login, and the handshake `?token=` — is encrypted in
/// transit. It is active unless `walkie.tls.enabled=false` (the integration tests and the
/// "TLS-terminated-at-a-reverse-proxy" production model set that to run plain HTTP).
///
/// Certificate source:
/// - if `WALKIE_TLS_KEYSTORE` (+ `WALKIE_TLS_KEYSTORE_PASSWORD`) is set, that operator keystore is used (real
///   CA cert in production);
/// - otherwise a self-signed localhost dev certificate is auto-generated into `~/.walkie-talkie/` on first
///   use and **reused** across restarts (RSA-16384, so the slow keygen is a one-time cost); its public cert
///   is exported to `dev-cert.pem` so the Java client can trust it on localhost. Browsers show a one-time
///   warning. For a CA-issued cert, point `WALKIE_TLS_KEYSTORE` at your keystore.
@Configuration
@ConditionalOnProperty(name = "walkie.tls.enabled", havingValue = "true", matchIfMissing = true)
public class TlsConfiguration implements WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> {

	private static final Logger log = LoggerFactory.getLogger(TlsConfiguration.class);

	private static final int TLS_PORT = 8443;
	private static final String DEV_ALIAS = "walkie-dev";
	/// RSA-16384 — the maximum the JCA RSA provider supports. The keygen is slow (can take minutes), so the
	/// cert is generated once and **reused** across restarts (see [#ensureDevCertificate]) — a one-time cost,
	/// not per-boot.
	private static final int DEV_KEY_BITS = 16384;
	/// SHA-512 with RSA for the certificate signature (keytool would otherwise default to SHA384withRSA).
	private static final String DEV_SIG_ALG = "SHA512withRSA";
	private static final int DEV_CERT_VALIDITY_DAYS = 825;
	private static final int PASSWORD_BYTES = 24;                 // 192 bits of entropy, Base64url -> 32 chars
	private static final long KEYTOOL_TIMEOUT_SECONDS = 600;   // RSA-16384 keygen can take minutes; this only bounds a wedged keytool

	private static final Path DEV_TLS_DIR = Path.of(System.getProperty("user.home"), ".walkie-talkie");
	private static final Path DEV_KEYSTORE = DEV_TLS_DIR.resolve("dev-keystore.p12");
	private static final Path DEV_CERT_PEM = DEV_TLS_DIR.resolve("dev-cert.pem");
	/// The random keystore password, persisted so the (expensive) keystore can be reused across restarts. It
	/// lives in the owner-only [#DEV_TLS_DIR] and only guards a local, regenerable self-signed dev cert.
	private static final Path DEV_PASS_FILE = DEV_TLS_DIR.resolve("dev-keystore.pass");

	private final SecureRandom secureRandom;

	public TlsConfiguration(SecureRandom secureRandom) {
		this.secureRandom = secureRandom;
	}

	@Override
	public void customize(@NonNull ConfigurableServletWebServerFactory factory) {
		Ssl ssl = new Ssl();
		ssl.setEnabled(true);
		ssl.setKeyStoreType("PKCS12");
		ssl.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});   // TLS 1.2+ only, per policy

		String operatorKeystore = System.getenv("WALKIE_TLS_KEYSTORE");
		if (operatorKeystore != null && !operatorKeystore.isBlank()) {
			ssl.setKeyStore(operatorKeystore);
			ssl.setKeyStorePassword(System.getenv("WALKIE_TLS_KEYSTORE_PASSWORD"));
			log.info("TLS enabled on port {} with the operator-supplied keystore ({}).", TLS_PORT, operatorKeystore);
		} else {
			String password = ensureDevCertificate();
			ssl.setKeyStore("file:" + DEV_KEYSTORE);
			ssl.setKeyStorePassword(password);
			log.warn("TLS enabled on port {} with an AUTO-GENERATED self-signed dev certificate ({}). Browsers "
					+ "will warn; the Java client auto-trusts it on localhost. Set WALKIE_TLS_KEYSTORE for a real "
					+ "cert, or walkie.tls.enabled=false to serve plain HTTP (e.g. behind a TLS-terminating proxy).",
					TLS_PORT, DEV_CERT_PEM);
		}

		factory.setSsl(ssl);
		factory.setPort(TLS_PORT);
	}

	/// Returns the dev keystore password, reusing the previously-generated keystore when it is present, valid,
	/// and the current [#DEV_KEY_BITS] strength — otherwise generating a fresh one. Persisting and reusing
	/// makes the slow RSA-16384 keygen a one-time cost rather than a per-boot one.
	private String ensureDevCertificate() {
		try {
			createPrivateDirectory(DEV_TLS_DIR);
			if (Files.isReadable(DEV_KEYSTORE) && Files.isReadable(DEV_PASS_FILE) && Files.isReadable(DEV_CERT_PEM)) {
				String existing = Files.readString(DEV_PASS_FILE).strip();
				if (isReusable(existing)) {
					log.info("Reusing the existing self-signed dev certificate at {}.", DEV_KEYSTORE);
					return existing;
				}
			}
			return generateDevCertificate();
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			throw new IllegalStateException("Could not provision a dev TLS certificate. Set WALKIE_TLS_KEYSTORE "
					+ "to a keystore, or set walkie.tls.enabled=false to serve plain HTTP.", e);
		}
	}

	/// Whether the persisted keystore can be reused: openable with `password`, holding an unexpired RSA cert of
	/// the current [#DEV_KEY_BITS] strength. Any failure (wrong password, expired, wrong size, corrupt) returns
	/// false so a fresh cert is generated instead.
	private static boolean isReusable(String password) {
		try (InputStream in = Files.newInputStream(DEV_KEYSTORE)) {
			KeyStore store = KeyStore.getInstance("PKCS12");
			store.load(in, password.toCharArray());
			if (store.getCertificate(DEV_ALIAS) instanceof X509Certificate cert) {
				cert.checkValidity();   // throws if expired / not yet valid
				return cert.getPublicKey() instanceof RSAPublicKey rsa && rsa.getModulus().bitLength() == DEV_KEY_BITS;
			}
			return false;
		} catch (GeneralSecurityException | IOException _) {
			return false;
		}
	}

	/// Generates a fresh self-signed localhost keystore (RSA-16384, signed [#DEV_SIG_ALG]), exports its public
	/// cert to PEM, and persists the random keystore password — all under `~/.walkie-talkie/`. Uses the JDK's
	/// own `keytool` with a FIXED argument list (no external/user input reaches the process), per policy.
	private String generateDevCertificate() throws IOException, InterruptedException {
		byte[] entropy = new byte[PASSWORD_BYTES];
		secureRandom.nextBytes(entropy);
		String password = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);

		log.info("Generating a new RSA-{} self-signed dev certificate — a one-time cost that can take a while; "
				+ "it is reused on later starts.", DEV_KEY_BITS);
		Files.deleteIfExists(DEV_KEYSTORE);
		runKeytool(List.of(
				"-genkeypair", "-alias", DEV_ALIAS,
				"-keyalg", "RSA", "-keysize", Integer.toString(DEV_KEY_BITS),
				"-sigalg", DEV_SIG_ALG,
				"-validity", Integer.toString(DEV_CERT_VALIDITY_DAYS),
				"-storetype", "PKCS12",
				"-keystore", DEV_KEYSTORE.toString(), "-storepass", password,
				"-dname", "CN=localhost, OU=dev, O=walkie-talkie",
				"-ext", "SAN=dns:localhost,ip:127.0.0.1"));

		Files.deleteIfExists(DEV_CERT_PEM);
		runKeytool(List.of(
				"-exportcert", "-rfc", "-alias", DEV_ALIAS,
				"-keystore", DEV_KEYSTORE.toString(), "-storepass", password,
				"-file", DEV_CERT_PEM.toString()));

		Files.writeString(DEV_PASS_FILE, password);   // guarded by the owner-only directory
		return password;
	}

	// Process is Closeable only as of JDK 26; this module compiles --release 25, where it is NOT AutoCloseable
	// and so cannot go in a try-with-resources. inheritIO (below) creates no pipes, so there is nothing to
	// close anyway — the IDE's resource warning is a false-positive for this build target.
	@SuppressWarnings("resource")
	private static void runKeytool(Collection<String> arguments) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add(Path.of(
				System.getProperty("java.home"),
				"bin",
				// is windows
				System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
						? "keytool.exe"
						: "keytool"
				)
				.toString());
		command.addAll(arguments);   // all elements are app constants — no external/user input
		// inheritIO: keytool's output goes straight to our console, so NO stdout/stdin/stderr pipes are
		// created — there are no file descriptors to leak or close. (Process is Closeable only as of JDK 26;
		// we compile --release 25, where it is not AutoCloseable, so it cannot go in a try-with-resources.)
		Process process = new ProcessBuilder(command).inheritIO().start();
		if (!process.waitFor(KEYTOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IOException("keytool timed out after " + KEYTOOL_TIMEOUT_SECONDS + "s");
		}
		if (process.exitValue() != 0) {
			throw new IOException("keytool exited with status " + process.exitValue());
		}
	}

	/// Creates the dev-TLS directory owner-only (`rwx------`) so the keystore inside is private — applied
	/// atomically at creation via a [PosixFilePermissions] file attribute (POSIX only; a non-POSIX filesystem
	/// falls back to default permissions). An already-present directory is tightened to the same.
	private static void createPrivateDirectory(Path dir) throws IOException {
		boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
		if (Files.notExists(dir)) {
			if (posix) {
				Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)));
			} else {
				Files.createDirectories(dir);
			}
		} else if (posix) {
			Files.setPosixFilePermissions(dir, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
		}
	}
}
