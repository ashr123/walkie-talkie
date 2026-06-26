package io.github.ashr123.walkietalkie.client;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;

/// Parsed command-line options for the desktop client.
///
/// @param server        base URL of the walkie-talkie server (e.g. `https://localhost:8443`)
/// @param channel       channel to join (ignored for [ChannelMode#GLOBAL_PTT])
/// @param mode          conversation mode
/// @param display       display name shown to other members (also the only name the user supplies)
/// @param highFidelity  encode with the Opus music profile (vs. the voice profile) for richer audio
/// @param inputDevice   capture from the mixer whose name contains this text; null uses the system default
/// @param key           passphrase for AES-256-GCM end-to-end audio encryption; null/blank disables it
/// @param tlsTruststore path to a PEM certificate to additionally trust for TLS (besides the system CAs and,
///                      on localhost, the server's auto-generated dev cert); null relies on those defaults
public record ClientOptions(String server,
                            String channel,
                            ChannelMode mode,
                            String display,
                            boolean highFidelity,
                            String inputDevice,
                            String key,
                            String tlsTruststore
) {
}
