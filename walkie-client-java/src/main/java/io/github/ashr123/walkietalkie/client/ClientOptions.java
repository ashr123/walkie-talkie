package io.github.ashr123.walkietalkie.client;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;

/// Parsed command-line options for the desktop client.
///
/// @param server       base HTTP URL of the walkie-talkie server (e.g. `http://localhost:8080`)
/// @param channel      channel to join (ignored for [ChannelMode#GLOBAL_PTT])
/// @param mode         conversation mode
/// @param display      display name shown to other members (also the only name the user supplies)
/// @param highFidelity encode with the Opus music profile (vs. the voice profile) for richer audio
/// @param inputDevice  capture from the mixer whose name contains this text; null uses the system default
/// @param key          passphrase for AES-256-GCM end-to-end audio encryption; null/blank disables it
public record ClientOptions(String server,
                            String channel,
                            ChannelMode mode,
                            String display,
                            boolean highFidelity,
                            String inputDevice,
                            String key
) {
}
