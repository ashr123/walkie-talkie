#!/usr/bin/env bash
# Generate a STABLE self-signed PKCS12 keystore for LOCAL DEV TLS (HTTPS/WSS) on localhost.
#
# Optional: the server already AUTO-GENERATES a fresh self-signed cert at startup when no keystore is set, so
# you only need this for a *stable* cert (e.g. to avoid re-accepting it in the browser after each restart).
# Point the server at it with WALKIE_TLS_KEYSTORE (see the printed command below).
#
# It uses RSA-16384 (the JCA maximum) signed with SHA-512 — the strongest available — but generating a
# 16384-bit key can take a while (a one-time cost); lower -keysize below (e.g. 8192) if that's too slow.
#
# Development only — browsers warn that the cert is untrusted (click through on localhost). For production
# use a real CA-issued cert, ideally via the reverse proxy in deploy/ (see the README).
#
# The keystore contains a private key, so it is gitignored and must never be committed. The password is read
# from the environment (never hardcoded), and so is not stored anywhere by this script.
#
# Usage:
#   export WALKIE_TLS_KEYSTORE_PASSWORD=some-dev-password
#   scripts/gen-dev-cert.sh                     # writes ./dev-keystore.p12
#   scripts/gen-dev-cert.sh path/to/store.p12   # or a custom path
set -euo pipefail

KEYSTORE="${1:-dev-keystore.p12}"
PASS="${WALKIE_TLS_KEYSTORE_PASSWORD:-}"

if [[ -z "$PASS" ]]; then
	echo "error: set WALKIE_TLS_KEYSTORE_PASSWORD first, e.g.:" >&2
	echo "  export WALKIE_TLS_KEYSTORE_PASSWORD=changeit-dev" >&2
	exit 1
fi

keytool -genkeypair \
	-alias walkie-dev \
	-keyalg RSA -keysize 16384 \
	-sigalg SHA512withRSA \
	-validity 825 \
	-storetype PKCS12 \
	-keystore "$KEYSTORE" \
	-storepass "$PASS" \
	-dname "CN=localhost, OU=dev, O=walkie-talkie" \
	-ext "SAN=dns:localhost,ip:127.0.0.1"

# Export the public certificate (PEM) next to the keystore so the Java client can trust it via --tls-truststore.
CERT="${KEYSTORE%.p12}.pem"
keytool -exportcert -rfc -alias walkie-dev -keystore "$KEYSTORE" -storepass "$PASS" -file "$CERT"

ABS="$(cd "$(dirname "$KEYSTORE")" && pwd)/$(basename "$KEYSTORE")"
CERT_ABS="$(cd "$(dirname "$CERT")" && pwd)/$(basename "$CERT")"
echo
echo "Wrote dev keystore: $ABS"
echo "Wrote public cert:  $CERT_ABS"
echo
echo "Run the server with this cert (TLS is already on by default; HTTPS/WSS on https://localhost:8443):"
echo "  WALKIE_TLS_KEYSTORE=\"file:$ABS\" JAVA_OPTS= ./gradlew :walkie-server:bootRun"
echo "  (WALKIE_TLS_KEYSTORE_PASSWORD must be exported in that shell too.)"
echo
echo "Java client trusting this cert:  --tls-truststore \"$CERT_ABS\""
echo "Then open https://localhost:8443 and accept the self-signed certificate warning."
