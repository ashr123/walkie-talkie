package io.github.ashr123.walkietalkie.client;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/// Builds the [SSLContext] the client uses for HTTPS + WSS. It trusts the JVM's system CA certificates and,
/// in addition, any certificate from `--tls-truststore` plus — only when the server host is localhost — the
/// server's auto-generated dev certificate at `~/.walkie-talkie/dev-cert.pem`.
///
/// Verification is **never** disabled (org policy): a server certificate must chain to a system CA *or* to
/// one of these explicitly-trusted certificates, via the composite trust manager below. There is no
/// trust-all manager and no hostname-verification bypass.
final class TlsTrust {

	/// Where [TlsConfiguration] on the server exports the dev cert's public certificate.
	private static final Path DEV_CERT_PEM =
			Path.of(System.getProperty("user.home"), ".walkie-talkie", "dev-cert.pem");

	private TlsTrust() {
	}

	/// The system-default context when no extra certificates apply, otherwise a context trusting the system
	/// CAs plus the extra certificate(s) — still fully verifying.
	static SSLContext forServer(String serverUrl, String trustStorePath) throws GeneralSecurityException, IOException {
		Collection<X509Certificate> extra = new ArrayList<>();
		if (trustStorePath != null && !trustStorePath.isBlank()) {
			extra.addAll(loadPemCertificates(Path.of(trustStorePath)));
		}
		if (isLocalhost(serverUrl) && Files.isReadable(DEV_CERT_PEM)) {
			extra.addAll(loadPemCertificates(DEV_CERT_PEM));
		}
		if (extra.isEmpty()) {
			return SSLContext.getDefault();   // system CAs only — the normal path for a real HTTPS server
		}
		X509TrustManager system = systemTrustManager();
		X509TrustManager extraTrust = trustManagerFor(extra);
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(null, new TrustManager[]{new CompositeTrustManager(system, extraTrust)}, null);
		return context;
	}

	private static boolean isLocalhost(String serverUrl) {
		try {
			String host = URI.create(serverUrl).getHost();
			if (host == null) {
				return false;
			}
			String lower = host.toLowerCase(Locale.ROOT);
			return "localhost".equals(lower) || "127.0.0.1".equals(lower) || "::1".equals(lower) || "[::1]".equals(lower);
		} catch (RuntimeException _) {
			return false;
		}
	}

	private static List<X509Certificate> loadPemCertificates(Path path) throws GeneralSecurityException, IOException {
		try (InputStream in = Files.newInputStream(path)) {
			Collection<? extends Certificate> certificates = CertificateFactory.getInstance("X.509").generateCertificates(in);
			List<X509Certificate> result = new ArrayList<>(certificates.size());
			for (Certificate certificate : certificates) {
				if (certificate instanceof X509Certificate x509) {
					result.add(x509);
				}
			}
			if (result.isEmpty()) {
				throw new CertificateException("No X.509 certificates found in " + path);
			}
			return result;
		}
	}

	private static X509TrustManager systemTrustManager() throws GeneralSecurityException {
		return firstX509(trustManagerFactory(null));
	}

	private static X509TrustManager trustManagerFor(Iterable<? extends X509Certificate> certificates) throws GeneralSecurityException, IOException {
		KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
		store.load(null, null);
		int index = 0;
		for (X509Certificate certificate : certificates) {
			store.setCertificateEntry("extra-" + index++, certificate);
		}
		return firstX509(trustManagerFactory(store));
	}

	private static TrustManagerFactory trustManagerFactory(KeyStore store) throws GeneralSecurityException {
		TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		factory.init(store);   // null -> the JVM's default CA trust store
		return factory;
	}

	private static X509TrustManager firstX509(TrustManagerFactory factory) throws GeneralSecurityException {
		for (TrustManager manager : factory.getTrustManagers()) {
			if (manager instanceof X509TrustManager x509) {
				return x509;
			}
		}
		throw new GeneralSecurityException("No X509TrustManager available from the default algorithm");
	}

	/// Accepts a server certificate if EITHER the system CAs or the extra (dev/custom) certificates accept it,
	/// so verification stays on while also trusting the otherwise-untrusted self-signed dev cert.
	private record CompositeTrustManager(X509TrustManager system, X509TrustManager extra) implements X509TrustManager {

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			try {
				system.checkServerTrusted(chain, authType);
			} catch (CertificateException bySystem) {
				try {
					extra.checkServerTrusted(chain, authType);
				} catch (CertificateException byExtra) {
					bySystem.addSuppressed(byExtra);
					throw bySystem;
				}
			}
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			system.checkClientTrusted(chain, authType);   // client auth is unused here
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			X509Certificate[] fromSystem = system.getAcceptedIssuers();
			X509Certificate[] fromExtra = extra.getAcceptedIssuers();
			X509Certificate[] all = new X509Certificate[fromSystem.length + fromExtra.length];
			System.arraycopy(fromSystem, 0, all, 0, fromSystem.length);
			System.arraycopy(fromExtra, 0, all, fromSystem.length, fromExtra.length);
			return all;
		}
	}
}
