package io.github.ashr123.walkietalkie.client;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;

/// Parsed command-line options for the desktop client.
///
/// @param server       base HTTP URL of the walkie-talkie server (e.g. `http://localhost:8080`)
/// @param user         username used to obtain a bearer token
/// @param channel      channel to join (ignored for [ChannelMode#GLOBAL_PTT])
/// @param mode         conversation mode
/// @param display      display name shown to other members
/// @param highFidelity encode with the Opus music profile (vs. the voice profile) for richer audio
/// @param inputDevice  capture from the mixer whose name contains this text; null uses the system default
public record ClientOptions(String server,
                            String user,
                            String channel,
                            ChannelMode mode,
                            String display,
                            boolean highFidelity,
                            String inputDevice
) {
}
