package io.github.ashr123.walkietalkie.client;

import io.github.ashr123.walkietalkie.shared.protocol.*;
import io.github.jaredmdobson.concentus.OpusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import javax.sound.sampled.LineUnavailableException;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/// Console walkie-talkie client over the WebSocket-relay transport (the only one available to a pure-Java
/// client; WebRTC is browser-to-browser). It orchestrates login, the relay WebSocket connection, the
/// interactive console and server-message handling, and — when a `--key` is given — per-frame AES-256-GCM
/// encryption.
///
/// Microphone capture, Opus (de)coding and speaker playback all live in [AudioEngine]; this class only
/// encrypts captured frames before sending them and decrypts received frames before handing them back to
/// the engine for playback. All loops run on Java 25 virtual threads.
///
/// It is [AutoCloseable]: [#WalkieClient] does the work and blocks on the console; the caller closes the client
/// (ideally via try-with-resources) to tear the session down.
public final class WalkieClient implements AutoCloseable {

	private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(
			"yyyy-MM-dd HH:mm:ss,SSS",
			Locale.getDefault(Locale.Category.FORMAT)
	);
	// Stateless, thread-safe infrastructure with no per-connection input — shared by every client instance.
	// Unknown enum values (an ErrorCode minted by a NEWER server than this client) deserialize to the enum's
	// @JsonEnumDefaultValue constant (ErrorCode.UNKNOWN) instead of failing the whole message — the forward-
	// compatibility contract documented on ErrorCode. (Jackson 3 hosts this on EnumFeature, not
	// DeserializationFeature as in Jackson 2.)
	private static final ObjectMapper JSON_MAPPER = JsonMapper.builder()
			.enable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
			.build();
	/// Sentinel owner id the server stamps on the server-managed "global" room (mirrors
	/// `ConnectionService.GLOBAL_CHANNEL_OWNER`); that channel has no participant owner.
	private static final String SERVER_OWNER = "server";
	/// Display-name charset, mirrored from the server's validation so the `n` command can reject a bad name
	/// locally before the round-trip (the server validates authoritatively too).
	private static final Pattern DISPLAY_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,32}");
	/// Channel-name charset, mirrored from the server (and the browser client's CHANNEL_NAME) so the `c` command
	/// and the initial `--channel` are rejected locally before the round-trip. Note `.` is allowed in a display
	/// name but NOT a channel name.
	private static final Pattern CHANNEL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
	/// First byte of an end-to-end-encrypted frame (mirrors FrameCrypto's scheme marker); lets the receive path
	/// distinguish encrypted audio from a plaintext peer's `[codec tag][payload]` when we hold no key.
	private static final int E2EE_SCHEME = 0xE2;
	/// Number of leading id characters shown in a member's `(#…)` tag (see [#name]); matches the browser client's
	/// `ID_PREFIX_LENGTH` so both render the same short id. Callers clamp with `Math.min` because [String#substring]
	/// throws when the id is shorter than this (the browser's `slice` clamps on its own, so it needs no guard).
	private static final int ID_PREFIX_LENGTH = 8;
	/// Upper bound on how long [#close] waits for the HttpClient — and the WebSocket close handshake riding on
	/// it — to drain gracefully before forcing termination, so quitting can never hang on a slow or vanished
	/// server. Two seconds is ample for a localhost/LAN close handshake while still feeling instant to a user.
	private static final Duration HTTP_SHUTDOWN_GRACE = Duration.ofSeconds(2);
	/// Fixed width of the help box's horizontal rule — a cosmetic frame (some command lines run longer than this).
	private static final int HELP_RULE_WIDTH = 98;
	private static final String HELP_RULE = "-".repeat(HELP_RULE_WIDTH);
	/// The commands available to everyone, owner or not. A non-owner additionally sees [#MEMBER_PASSPHRASE_COMMAND];
	/// the owner instead sees [#OWNER_COMMANDS]. (`p` is role-split: a member ADOPTS a shared passphrase, an owner
	/// CHANGES it.)
	private static final String COMMON_COMMANDS = """
			Commands:  t = talk/stop
			           w = who's here
			           c <channel> [mode] [key] = switch channel
			           n <name> = rename
			           f = hi-fi on/off
			           q = quit
			           h = help""";
	/// The owner-only command block — shown in the help ONLY to the current channel owner, and announced verbatim
	/// the instant a member is promoted (see the [ServerMessage.OwnerChanged] handler) so it learns the abilities it
	/// just gained. One source of truth, so the help and the promotion notice can't drift; the server also rejects
	/// these from a non-owner, so hiding them is UI honesty, not the security boundary.
	private static final String OWNER_COMMANDS = """
			Owner:     m <ptt|global|duplex> = change the mode for everyone ('m global' switches YOU to the global room)
			           p [passphrase] = change the passphrase (blank = turn encryption off; members auto-adopt)
			           p! [passphrase] = change the passphrase WITHOUT auto-sharing (members must re-enter it)
			           o <#id> = give ownership to another member
			           mute <#id|all> / unmute <#id|all> = mute or unmute members
			           lock / unlock = lock or unlock the channel to new members""";
	/// The one passphrase command a NON-owner has: adopt a rotation the owner announced but didn't auto-share (an
	/// owner instead changes the passphrase with `p`/`p!` — see [#OWNER_COMMANDS]). The 11 leading spaces align its
	/// `p` under the command column of [#COMMON_COMMANDS] / [#OWNER_COMMANDS] after their text-block indent is
	/// stripped (their " Commands:" / " Owner:" label lines set that margin one space in from the frame).
	private static final String MEMBER_PASSPHRASE_COMMAND =
			"           p [passphrase] = adopt the owner's new passphrase (only needed if you weren't auto-updated)";
	private final ClientOptions options;
	// Per-instance: its SSLContext trusts the system CAs plus (on localhost) the server's dev cert or a
	// --tls-truststore, so HTTPS + WSS verify against the target server — verification is never disabled.
	private final HttpClient httpClient;
	private final AtomicBoolean running = new AtomicBoolean(true);
	private final AtomicBoolean closed = new AtomicBoolean();   // guards close() so it is idempotent
	// True only while an intentional channel-affinity reconnect tears down the old socket and opens a new one, so
	// the old socket's onClose is not mistaken for a lost connection (which would exit the process). See reconnect().
	private final AtomicBoolean reconnecting = new AtomicBoolean();
	private final BlockingQueue<Outbound> sendQueue = new LinkedBlockingQueue<>();
	private final Map<String, String> memberNames = new ConcurrentHashMap<>(); // session id -> display name
	// Session ids the owner has muted (server-authoritative — the server also DROPS their relay audio). Read on the
	// capture thread's frame sink (a lock-free concurrent set), mutated on the single listener thread from MemberMuted.
	private final Set<String> mutedMembers = ConcurrentHashMap.newKeySet();
	private final AudioEngine audio;
	// The live relay socket. Volatile (not final) because a CHANNEL_ROUTING_MISMATCH reconnect swaps it for a new
	// socket bound to the target channel's instance; onOpen publishes each new socket here before it queues its Join.
	private volatile WebSocket webSocket;
	// AES-256-GCM E2EE for the current channel, or null when no passphrase. Volatile + reassigned on an in-place
	// channel switch (the `c` command), since the capture/playback loops read it from other threads.
	private volatile FrameCrypto crypto;
	private volatile String selfId = "";
	private volatile String ownerId;
	private volatile ChannelMode currentMode;
	private volatile String currentChannel;     // server-confirmed current channel (updated on Joined)
	// The channel/mode this socket (re)connects and joins as: the ?channel= routing key at the handshake AND the
	// Join sent from onOpen. Distinct from currentChannel/currentMode (server-CONFIRMED) — switchTo advances these
	// to the target OPTIMISTICALLY so a CHANNEL_ROUTING_MISMATCH reconnect rebuilds against the channel we asked for.
	private volatile String connectChannel;
	private volatile ChannelMode connectMode;
	private volatile String currentPassphrase;   // passphrase backing the current channel's key (for switch defaults)
	private volatile String currentChannelKeyCheck;   // the channel's currently-announced key-check (null = unencrypted); the yardstick a member re-keys against
	private volatile boolean rekeyInFlight;      // true between sending our own ChangePassphrase (owner) and its echoed PassphraseChanged

	// --- HTTP login + WebSocket -------------------------------------------------------------------
	private volatile String pendingPassphrase;   // the new passphrase we (as owner) submitted, applied when the echo arrives
	private volatile boolean warnedDecrypt; // listener thread only today, but volatile so the warn-once intent survives a threading refactor
	private volatile boolean warnedEncryptedNoKey; // warn-once (like warnedDecrypt): encrypted audio arrived while we hold no key
	private volatile boolean welcomeShown;  // print the role-aware help once, after the first Joined reveals our role (same listener-thread-only-but-volatile rationale as warnedDecrypt)
	private volatile boolean channelLocked; // whether the owner has locked the channel to new members (from Joined/ChannelLocked); volatile — set on the listener thread, read on the console thread for 'w'

	public WalkieClient(ClientOptions options) throws IOException, InterruptedException, GeneralSecurityException, LineUnavailableException, OpusException {
		this.options = options;
		// Validate the startup identity/channel locally before opening audio or a socket — the same checks the `n`
		// and `c` commands apply, and the browser client applies on connect. The server validates authoritatively
		// too, but failing fast here avoids a connected-but-not-joined dead-end on a bad --display/--channel.
		if (!DISPLAY_NAME.matcher(options.display()).matches()) {
			throw new IllegalArgumentException("--display must be 1-32 chars of letters, digits, _ . or - (got: \"" + options.display() + "\").");
		}
		// Global forces the channel to "global" server-side, so the --channel name only matters for the other modes.
		if (options.mode() != ChannelMode.GLOBAL_PTT && !CHANNEL_NAME.matcher(options.channel()).matches()) {
			throw new IllegalArgumentException("--channel must be 1-64 chars of letters, digits, _ or - (got: \"" + options.channel() + "\").");
		}
		this.httpClient = HttpClient.newBuilder()
				.sslContext(TlsTrust.forServer(options.server(), options.tlsTruststore()))
				.build();
		this.currentMode = options.mode();
		this.currentChannel = options.channel();
		this.connectChannel = options.channel();
		this.connectMode = options.mode();
		this.currentPassphrase = options.key();
		this.audio = new AudioEngine(options, this::sendAudioFrame);
		System.out.println("Connecting to " + options.server() + " as '" + options.display() + "' ...");
		String token = login();
		crypto = deriveCrypto(options.key(), options.mode(), options.channel());
		currentChannelKeyCheck = crypto == null ? null : crypto.keyCheck();   // baseline the channel's key-check from our own join key
		audio.start();
		System.out.println("Audio: " + audio.description()
				+ (crypto == null ? "" : ", end-to-end encrypted (AES-256-GCM)"));

		webSocket = connect(token);
		// Start the sender only after webSocket is assigned, so it is published to the sender thread (Thread.start()
		// happens-after the write, and the field is volatile). onOpen republishes each socket it opens — including a
		// reconnect's — into webSocket before queueing that socket's Join, so a Join is never sent on a stale socket.
		Thread.ofVirtual().name("ptt-sender").start(this::senderLoop);

		// Blocks until the user quits or stdin closes; the caller then closes us (try-with-resources).
		consoleLoop();
	}

	/// Prints a status line prefixed with the local timestamp (`yyyy-MM-dd HH:mm:ss,SSS`).
	private static void log(String message) {
		System.out.println(LocalDateTime.now().format(DATE_TIME_FORMATTER) + " " + message);
	}

	private static String modeHint(ChannelMode mode, boolean micLive) {
		return mode == ChannelMode.FULL_DUPLEX
				? "Full-duplex: mic is " + (micLive ? "live" : "muted") + " — type 't' to mute/unmute."
				: "Push-to-talk: type 't' to grab/release the floor.";
	}

	/// Builds the AES-256-GCM frame cipher from `--key` (or the WALKIE_KEY env var), or null to disable
	/// E2EE. Salted with the effective channel (the server forces "global" for global mode), so every
	/// client in the channel derives the same key.
	private static FrameCrypto deriveCrypto(String passphrase, ChannelMode mode, String channel) throws GeneralSecurityException {
		if (passphrase == null || passphrase.isBlank()) {
			return null;
		}
		if (mode == ChannelMode.GLOBAL_PTT) {
			// Global is the server-managed, always-unencrypted broadcast room — the server rejects an
			// encrypted global join, so drop the key here (and warn) rather than fail the join.
			log("[warn] global mode uses the server's unencrypted broadcast channel — ignoring the passphrase");
			return null;
		}
		return FrameCrypto.fromPassphrase(passphrase, channel);
	}

	/// Decides what to send for a captured frame, given the key we currently hold and the channel's announced
	/// key-check: returns the bytes to put on the wire, or `null` to **drop** (stay silent). Pure (no field
	/// access) so the invariant below is unit-testable without a live socket.
	///
	/// Invariant — **only ever put on the wire what the channel's CURRENT key-check matches:**
	/// - channel unencrypted (`announcedKeyCheck == null`) → send the frame in the clear;
	/// - we hold the matching key (`key.keyCheck().equals(announcedKeyCheck)`) → send ciphertext;
	/// - otherwise → **drop** (stay silent). This covers both a member with NO key (the plaintext→encrypted
	///   *enable* — never leak plaintext) AND a member still holding a STALE key after a rotation it hasn't
	///   adopted (don't emit audio the rekeyed channel can't decode, and don't desync — a straggler is muted until
	///   it adopts the new key, so the experience is symmetric for everyone).
	static byte[] outboundFrame(byte[] frame, FrameCrypto key, String announcedKeyCheck) throws GeneralSecurityException {
		return announcedKeyCheck == null ?
				frame :
				key != null && announcedKeyCheck.equals(key.keyCheck()) ?
						key.encrypt(frame) :
						null;
	}

	/// Pure decision behind the full-duplex mic auto-open: open only when the mode is full-duplex, the user did not
	/// pass `--muted`, and the owner has not muted us (`selfMuted`). The mute term keeps a muted member's mic closed
	/// — a member re-joining its current channel re-snapshots itself as muted, and a switch to full-duplex must not
	/// open a muted member's mic — mirroring the browser's `beginTransmit` guard. Frames would be dropped by
	/// [#sendAudioFrame] anyway, but this keeps the local transmit state and the "mic is live" hint honest.
	/// Extracted static (like [#outboundFrame]) so this policy is unit-testable without a live socket.
	static boolean shouldAutoOpenMic(ChannelMode mode, boolean startMuted, boolean selfMuted) {
		return mode == ChannelMode.FULL_DUPLEX && !startMuted && !selfMuted;
	}

	static RekeyAction rekeyAction(String announcedKeyCheck, FrameCrypto candidate) {
		return announcedKeyCheck == null ?
				RekeyAction.DISABLE :
				candidate != null && announcedKeyCheck.equals(candidate.keyCheck()) ?
						RekeyAction.APPLY :
						RekeyAction.KEEP;
	}

	private static ChannelMode parseMode(String arg, ChannelMode fallback) {
		return switch (arg.toLowerCase(Locale.ROOT)) {
			case "ptt", "multi" -> ChannelMode.MULTI_CHANNEL_PTT;
			case "global" -> ChannelMode.GLOBAL_PTT;
			case "duplex", "full" -> ChannelMode.FULL_DUPLEX;
			default -> fallback;
		};
	}

	/// Prints the command help for the caller's CURRENT role: the [#COMMON_COMMANDS] everyone has, then either the
	/// non-owner's [#MEMBER_PASSPHRASE_COMMAND] or — when our session id currently owns the channel — the full
	/// [#OWNER_COMMANDS]. The role is read live (not cached at connect), so pressing `h` right after being promoted
	/// shows the owner commands; the sentinel-owned `global` room has no participant owner, so no one there is shown
	/// the owner set.
	private void printHelp() {
		System.out.println(HELP_RULE + System.lineSeparator()
				+ COMMON_COMMANDS + System.lineSeparator()
				+ (selfId.equals(ownerId) ? OWNER_COMMANDS : MEMBER_PASSPHRASE_COMMAND) + System.lineSeparator()
				+ HELP_RULE);
	}

	// --- Server messages --------------------------------------------------------------------------

	private String login() throws IOException, InterruptedException {
		// Login takes no input: it just mints a signed, short-lived token. The token is an opaque string.
		HttpResponse<String> response = httpClient.send(
				HttpRequest.newBuilder(URI.create(options.server() + "/api/auth/login"))
						.POST(HttpRequest.BodyPublishers.noBody())
						.build(),
				HttpResponse.BodyHandlers.ofString()
		);
		if (response.statusCode() != 200) {
			throw new IOException("Login failed: HTTP " + response.statusCode() + " " + response.body());
		}
		return JSON_MAPPER.readValue(response.body(), LoginResponse.class).token();
	}

	private void senderLoop() {
		while (running.get()) {
			try {
				(switch (sendQueue.take()) {
					case Outbound.Text(String json) -> webSocket.sendText(json, true);
					case Outbound.Binary(byte[] data) -> webSocket.sendBinary(ByteBuffer.wrap(data), true);
				})
						.join();
			} catch (InterruptedException _) {
				Thread.currentThread().interrupt();
				return;
			} catch (RuntimeException _) {
				// This one send failed — the socket is closing, or was swapped mid-reconnect (this frame targeted the
				// old socket). Drop the frame and keep draining on the current socket; a genuine disconnect is caught
				// by the listener (onClose/onError -> onConnectionLost), which is what actually ends the session.
			}
		}
	}

	/// Frame sink for [AudioEngine] (runs on its capture thread): decide what (if anything) to put on the wire for
	/// the captured `[tag][opus]` frame, then queue it for the sender loop. Reads both volatiles **once** as the
	/// arguments to [#outboundFrame]; the rotation writer ([#handlePassphraseChanged]) publishes
	/// `currentChannelKeyCheck` *before* `crypto`, so the no-plaintext gate engages the instant encryption is
	/// announced — it does not depend on us having derived the new key yet.
	private void sendAudioFrame(byte[] frame) {
		if (mutedMembers.contains(selfId)) {
			// Owner-muted: drop before the wire. We also stop the mic on MemberMuted, so this only closes the brief
			// window where a frame captured just before the mute lands here — the server drops it anyway, this is the
			// authoritative local stop. A lock-free concurrent-set read, cheap on the per-frame path.
			return;
		}
		try {
			byte[] out = outboundFrame(frame, crypto, currentChannelKeyCheck);
			if (out != null) {
				sendQueue.offer(new Outbound.Binary(out));
			}
		} catch (GeneralSecurityException _) {
			// drop this frame; keep going
		}
	}

	private void handleServerMessage(String json) {
		switch (JSON_MAPPER.readValue(json, ServerMessage.class)) {
			case ServerMessage.Joined(String selfId,
									  String channel,
									  ChannelMode mode,
									  String ownerId,
									  boolean locked,
									  List<MemberInfo> members) -> {
				boolean channelChanged = !channel.equals(this.currentChannel);
				this.selfId = selfId;
				this.ownerId = ownerId;
				this.currentMode = mode;
				this.currentChannel = channel;
				this.channelLocked = locked;   // adopt the channel's lock state from the snapshot (covers an in-place re-join)
				if (channelChanged) {
					// Baseline the channel's announced key-check from the key we joined with — only on an ACTUAL
					// channel change (a switch). switchTo deliberately doesn't advance it, so the transmit gate keeps
					// suppressing plaintext through the switch round-trip; a same-channel re-snapshot must not reset
					// it either (it would clobber a pending rotation), mirroring the browser's onJoined.
					currentChannelKeyCheck = crypto == null ? null : crypto.keyCheck();
				}
				memberNames.clear();
				mutedMembers.clear();
				members.forEach(member -> {
					memberNames.put(member.id(), member.displayName());
					if (member.muted()) {
						mutedMembers.add(member.id());   // seed the mute state so a member joining a channel with someone already muted renders it
					}
				});
				audio.setTransmitting(shouldAutoOpenMic(mode));
				log("[joined] channel=" + channel + " mode=" + mode + (mode == options.mode() // If the channel already existed in another mode, its owner's mode wins and you adopt it.
						? ""
						: " (you requested " + options.mode() + ", adopted the channel's existing mode)") + " members=" + members.size()
						+ (locked ? " 🔒 locked" : "")
						// Full-duplex: the mic is live as soon as you join, unless --muted was passed; PTT/global start
						// muted and require 't' to grab the floor. (Full-duplex transmit needs no floor request.) Done AFTER
						// seeding mutedMembers so a member re-joining its current channel while muted keeps its mic closed —
						// shouldAutoOpenMic checks the mute.
						+ System.lineSeparator() + "[owner] " + (SERVER_OWNER.equals(ownerId)
						? "server-managed room — no owner, unencrypted"
						: selfId.equals(ownerId)
						  ? "you own this channel — 'm <ptt|global|duplex>' to change the mode for everyone"
						  : "owner: " + name(ownerId))
						+ System.lineSeparator() + modeHint(mode, audio.isTransmitting()));
				// Report the channel's E2EE status on EVERY confirmed entry (initial join AND in-place switch), like
				// the browser — so switching into/out of an encrypted channel says so. The global room already states
				// "unencrypted" in its owner line, so skip the redundant line there. crypto reflects the key held for
				// this channel (on a successful Joined an encrypted channel implies we hold a matching key).
				if (!SERVER_OWNER.equals(ownerId)) {
					log(crypto == null ? "[e2ee] off" : "[e2ee] ON (AES-256-GCM)");
				}
				if (!welcomeShown) {
					// Now that this first Joined has set our role, print the role-aware command help — deferred from
					// consoleLoop's start, where the role wasn't known yet. Once only, so a later channel switch
					// (another Joined) doesn't reprint the whole help box.
					welcomeShown = true;
					printHelp();
				}
			}
			case ServerMessage.MemberJoined(MemberInfo member) -> announceJoin(member);
			case ServerMessage.MemberLeft(String memberId) -> announceLeave(memberId);
			case ServerMessage.MemberRenamed(String memberId, String displayName) ->
					announceRename(memberId, displayName);
			case ServerMessage.MemberMuted(String memberId, boolean muted) -> handleMuteChange(memberId, muted);
			case ServerMessage.ChannelLocked(boolean locked) -> handleChannelLocked(locked);
			case ServerMessage.FloorGranted _ when mutedMembers.contains(selfId) ->
				// Owner-muted: never open the mic, even on a (stray) grant — the server refuses the floor to a
				// muted member, so this shouldn't arrive, but guard it like the browser's beginTransmit does.
					log("[floor granted] but you are muted by the owner — mic stays closed until unmuted.");
			case ServerMessage.FloorGranted _ -> {
				audio.setTransmitting(true);
				log("[floor granted] talking — type 't' to stop");
			}
			case ServerMessage.FloorDenied(String currentHolderId) ->
					log("[floor busy] currently held by " + name(currentHolderId));
			case ServerMessage.FloorTaken(String holderId)
					when audio.isTransmitting() && currentMode != ChannelMode.FULL_DUPLEX && !holderId.equals(selfId) -> {
				// We held the floor and the server handed it to someone ELSE (idle auto-release): stop. The
				// !holderId.equals(selfId) guard mirrors the browser: a FloorTaken naming US must not stop our mic.
				audio.setTransmitting(false);
				log("[released] floor reassigned to " + name(holderId) + " — type 't' to request it again");
			}
			case ServerMessage.FloorTaken(String holderId) -> log("[talking] " + name(holderId));
			case ServerMessage.FloorIdle _ when audio.isTransmitting() && currentMode != ChannelMode.FULL_DUPLEX -> {
				// The server freed our floor (max talk-time reached): stop; re-request to keep talking.
				audio.setTransmitting(false);
				log("[released] your talk time was up — type 't' to request the floor again");
			}
			case ServerMessage.FloorIdle _ -> log("[floor free]");
			case ServerMessage.ModeChanged(ChannelMode mode) -> {
				currentMode = mode;
				// Match the browser: switching to full-duplex opens the mic (unless --muted or owner-muted); else it
				// mutes. The mute check keeps a muted member's mic closed (and its "mic is live" hint honest) across
				// a mode change — otherwise setTransmitting would report live while onAudio/sendAudioFrame drop it.
				audio.setTransmitting(shouldAutoOpenMic(mode));
				log("[mode changed] now " + mode + System.lineSeparator()
						+ modeHint(mode, audio.isTransmitting()));
			}
			case ServerMessage.OwnerChanged(String ownerId) -> {
				boolean becameOwner = selfId.equals(ownerId) && !selfId.equals(this.ownerId);
				this.ownerId = ownerId;
				log(becameOwner ?
						// On promotion, show the commands we just gained (so the user needn't press 'h' to discover
						// them) — the same block the role-aware help prints for an owner.
						"[owner] you are now the channel owner. You can now also:" + System.lineSeparator() + OWNER_COMMANDS :
						selfId.equals(ownerId)
						? "[owner] you own this channel"
						: "[owner] channel owner is now " + name(ownerId));
				// Mirror the browser: if we were promoted while still holding a key that doesn't match the channel
				// (a rotation we never reconciled), warn that 'p' now ROTATES for everyone — so a user must not
				// just re-type the stale passphrase (it would re-key the whole channel to it).
				FrameCrypto held = crypto;
				if (becameOwner
						&& currentChannelKeyCheck != null
						&& (held == null || !currentChannelKeyCheck.equals(held.keyCheck()))) {
					log("[owner] note: your key doesn't match the channel — as owner, 'p <passphrase>' now ROTATES it for everyone, so set one you actually hold instead of re-typing a stale one.");
				}
			}
			case ServerMessage.PassphraseChanged(String keyCheck, String wrappedKey) ->
					handlePassphraseChanged(keyCheck, wrappedKey);
			case ServerMessage.SignalOffer _, ServerMessage.SignalAnswer _,
			     ServerMessage.SignalIce _ -> { /* WebRTC: not used by the relay client */ }
			case ServerMessage.ErrorMessage(ErrorCode code, String message) -> {
				log("[error] " + code + ": " + message);
				switch (code) {
					// Abandon an in-flight rekey ONLY when THIS error is the one that rejected our ChangePassphrase
					// (NOT_OWNER if we lost ownership in a race, or NOT_IN_CHANNEL) — otherwise a later
					// PassphraseChanged (from the new owner) would wrongly apply our stashed passphrase. Scoped to
					// these codes so an UNRELATED error (e.g. a rejected rename/mode we sent just before) can't wipe
					// a legitimately in-flight rotation and lock us out of our own just-rotated channel.
					case NOT_OWNER, NOT_IN_CHANNEL -> {
						rekeyInFlight = false;
						pendingPassphrase = null;
					}
					// A passphrase mismatch is fatal: the channel requires a different --key, so there's nothing to do
					// but disconnect — gracefully, so the server sees a clean close instead of an abrupt EOF.
					case PASSPHRASE_MISMATCH -> exitGracefully("Disconnecting — this channel needs a different --key.");
					// A locked/full channel refused our join. Like a passphrase mismatch, the join failed (an initial
					// connect joined nothing; a refused switch dropped us), so there's nothing to do but exit gracefully.
					case CHANNEL_LOCKED -> exitGracefully("This channel is locked by its owner — cannot join.");
					case CHANNEL_FULL -> exitGracefully("This channel is full — it has reached its member limit.");
					// The target channel lives on another instance (channel affinity): an in-place switch can't reach
					// it, so reconnect — a fresh handshake carrying ?channel=<target> is routed to the owning instance,
					// and switchTo already applied the target's mode/key + advanced connectChannel/connectMode so the
					// re-join lands us in it. A single instance never emits this code, so this path stays dormant there.
					case CHANNEL_ROUTING_MISMATCH -> reconnect();
					// Every other code — including UNKNOWN, the fallback the mapper substitutes for a code a NEWER
					// server minted (see ErrorCode) — needs no reaction beyond the [error] line already logged. A
					// deliberate default: future codes must degrade gracefully here, not force client handling.
					default -> {
					}
				}
			}
		}
	}

	/// Resolves a member's display name from its session id, always suffixed with a short session-id prefix (the
	/// session id is the real identity — display names aren't unique). Every call site passes a current member's
	/// id (membership precedes any floor/owner/mute reference, and delivery is ordered per recipient), so no
	/// unknown-id fallback is needed — mirrors the browser client's memberLabel().
	private String name(String id) {
		return memberNames.get(id) + " (#" + id.substring(0, Math.min(ID_PREFIX_LENGTH, id.length())) + ")";
	}

	private void announceJoin(MemberInfo member) {
		memberNames.put(member.id(), member.displayName());
		if (member.muted()) {
			mutedMembers.add(member.id());   // a fresh joiner is never pre-muted, but honor the flag defensively
		}
		log("[+] " + name(member.id()));
	}

	private void announceLeave(String memberId) {
		log("[-] " + name(memberId));
		memberNames.remove(memberId);
		mutedMembers.remove(memberId);   // a mute never outlives the member (mirrors the server's Channel.remove)
	}

	/// The owner muted or unmuted a member. Server-authoritative: the server also DROPS a muted member's relay
	/// audio, so this reflects enforcement rather than being it. Tracks the mute set for the roster; if WE are the
	/// target, stops the mic at once and (in PTT) releases the floor so we don't hold it silently, and says why.
	/// Runs on the single listener thread, so the read-then-act on `audio.isTransmitting()` needs no extra guard.
	private void handleMuteChange(String memberId, boolean muted) {
		if (muted) {
			mutedMembers.add(memberId);
		} else {
			mutedMembers.remove(memberId);
		}
		if (!memberId.equals(selfId)) {
			log("[" + (muted ? "muted" : "unmuted") + "] " + name(memberId) + " (by the owner)");
			return;
		}
		if (muted) {
			boolean wasTransmitting = audio.isTransmitting();
			audio.setTransmitting(false);   // stop the mic immediately — best-effort locally; the server drops us regardless
			if (wasTransmitting && currentMode != ChannelMode.FULL_DUPLEX) {
				enqueue(new ClientMessage.ReleaseFloor());   // don't keep holding the PTT floor while muted
			}
			log("[muted] the channel owner muted you — you can't talk until unmuted.");
		} else {
			log("[unmuted] the channel owner unmuted you — type 't' to talk again.");
		}
	}

	/// The owner locked or unlocked the channel to new members (server-enforced at join). Existing members — us
	/// included — are unaffected; we just track the state (for `w` and the join line) and note the change.
	private void handleChannelLocked(boolean locked) {
		channelLocked = locked;
		log(locked
				? "[locked] the owner locked this channel — new members can't join (current members are unaffected)."
				: "[unlocked] the owner unlocked this channel — new members can join again.");
	}

	/// Whether the mic should auto-open on a full-duplex join or mode change, for the current session — reads our
	/// `--muted` option and our own owner-mute state, then defers to the pure [#shouldAutoOpenMic(ChannelMode, boolean, boolean)].
	private boolean shouldAutoOpenMic(ChannelMode mode) {
		return shouldAutoOpenMic(mode, options.startMuted(), mutedMembers.contains(selfId));
	}

	/// A member changed its display name (its session id — the routing identity — is unchanged). Update the
	/// id→name map; everything else (floor, audio, ownership) is keyed by id and so is unaffected.
	///
	/// Only updates a member we already know: a rename that races the renamer's own disconnect can arrive after
	/// their MemberLeft (the server's two broadcasts can interleave), and re-adding them here would leave a ghost
	/// in the roster. Server messages are handled one-at-a-time on the listener thread, so the get-then-act is safe.
	private void announceRename(String memberId, String displayName) {
		String previous = memberNames.get(memberId);
		if (previous == null) {
			return;
		}
		memberNames.put(memberId, displayName);
		log(memberId.equals(selfId)
				? "[name] you are now " + name(memberId)
				: "[name] " + previous + " is now " + name(memberId));
	}

	/// Prints the current roster on demand (the 'w' command), sorted lexicographically by display name (then by
	/// id), each member shown via [#name] (display name + `#id` prefix) with `(you)` / `(owner)` markers.
	private void listMembers() {
		if (memberNames.isEmpty()) {
			log("[members] (none yet — join a channel first)");
			return;
		}
		log(memberNames.entrySet().stream()
				.sorted(Map.Entry.<String, String>comparingByValue(String.CASE_INSENSITIVE_ORDER)
						.thenComparing(Map.Entry.comparingByKey()))   // lexicographic by name, then id
				.map(entry -> {
					String id = entry.getKey();
					String role = id.equals(selfId)
							? " (you)"
							: id.equals(ownerId)
							  ? " (owner)"
							  : "";
					return name(id) + role + (mutedMembers.contains(id) ? " [muted]" : "");
				})
				.collect(Collectors.joining(
						System.lineSeparator() + "  - ",
						"[members] " + memberNames.size() + " in this channel"
								+ (channelLocked ? " 🔒 locked to new members" : "") + ":" + System.lineSeparator() + "  - ",
						""
				)));
	}

	private void consoleLoop() {
		// The command help is printed from the first Joined handler, not here: at this point we haven't received
		// our role (selfId/ownerId are still ""/null), so a role-aware help printed now would always show the
		// non-owner set even for a channel creator. Deferring it until Joined makes the very first help correct.
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
			String line;
			while (running.get() && (line = reader.readLine()) != null) {
				String[] parts = line.strip().split("\\s+", 2);
				switch (parts[0].toLowerCase(Locale.ROOT)) {
					case "t", "talk" -> toggleTalk();
					case "m", "mode" -> changeMode(parts.length > 1 ? parts[1] : "");
					case "c", "channel" -> switchChannel(parts.length > 1 ? parts[1] : "");
					case "p", "passphrase" -> changePassphrase(parts.length > 1 ? parts[1] : "", true);
					case "p!" -> changePassphrase(parts.length > 1 ? parts[1] : "", false);
					case "o", "owner" -> transferOwnership(parts.length > 1 ? parts[1] : "");
					case "mute" -> muteMember(parts.length > 1 ? parts[1] : "", true);
					case "unmute" -> muteMember(parts.length > 1 ? parts[1] : "", false);
					case "lock" -> setChannelLock(true);
					case "unlock" -> setChannelLock(false);
					case "n", "name" -> rename(parts.length > 1 ? parts[1] : "");
					case "f", "fidelity" -> toggleFidelity();
					case "w", "who", "members" -> listMembers();
					case "q", "quit", "exit" -> running.set(false);
					case "h", "help" -> printHelp();
					case "" -> { /* ignore blank lines */ }
					// Point at the single, role-aware source of truth ('h' -> printHelp) rather than repeating the
					// command list here — a third copy would drift and would advertise owner commands to non-owners.
					default ->
							System.out.println("Unrecognized command '" + parts[0] + "' — press 'h' for the list of commands.");
				}
			}
		} catch (IOException _) {
			// stdin closed; fall through to shutdown
		}
	}

	private void toggleTalk() {
		if (mutedMembers.contains(selfId)) {
			// Owner-muted: refuse. We already stopped the mic on MemberMuted, so we're not transmitting here; this
			// just tells a user who tries to talk why they can't (the server would drop us and refuse us the floor).
			log("[muted] you are muted by the channel owner — you can't talk until unmuted.");
			return;
		}
		if (audio.isTransmitting()) {
			audio.setTransmitting(false);
			if (currentMode != ChannelMode.FULL_DUPLEX) {
				enqueue(new ClientMessage.ReleaseFloor());
			}
			log("[stopped]");
		} else if (currentMode == ChannelMode.FULL_DUPLEX) {
			audio.setTransmitting(true);
			log("[talking]");
		} else {
			enqueue(new ClientMessage.RequestFloor());
			log("[requesting floor...]");
		}
	}

	/// Flips the hi-fi (Opus music vs voice) profile live; [AudioEngine] rebuilds the encoder on its next
	/// transmitted frame, so the change applies without reconnecting.
	private void toggleFidelity() {
		boolean hifi = audio.toggleHiFi();
		log("[hi-fi " + (hifi ? "on — music profile" : "off — voice profile") + "] (applies on the next transmitted frame)");
	}

	/// `m <ptt|global|duplex>` — mirrors the browser's mode selector. `ptt`/`duplex` change the CURRENT channel's
	/// mode for everyone (owner-only; the server enforces it, and the echoed [ServerMessage.ModeChanged] is what
	/// updates the controls). `global` is different: global-ptt lives only in the server-managed "global" room and
	/// can't be set on a regular channel (the server rejects ChangeMode(GLOBAL_PTT) with INVALID_MODE), so — exactly
	/// like the browser, whose "global" mode pick performs a Join to the global room rather than a mode change — it
	/// SWITCHES you there. That's a room change open to anyone (the same as `c global global`), so it is handled
	/// before the owner gate that guards real mode changes.
	private void changeMode(String arg) {
		String mode = arg.toLowerCase(Locale.ROOT);
		if ("global".equals(mode)) {
			switchTo("global", ChannelMode.GLOBAL_PTT, null);
			return;
		}
		if (!selfId.equals(ownerId)) {
			log("[denied] only the channel owner can change the mode");
			return;
		}
		switch (mode) {
			case "ptt", "multi" -> enqueue(new ClientMessage.ChangeMode(ChannelMode.MULTI_CHANNEL_PTT));
			case "duplex", "full" -> enqueue(new ClientMessage.ChangeMode(ChannelMode.FULL_DUPLEX));
			default -> System.out.println("Usage: m <ptt|global|duplex>");
		}
	}

	/// `p [passphrase]` / `p! [passphrase]` — change the channel's end-to-end-encryption passphrase. For the OWNER
	/// this rotates it for everyone (a blank passphrase turns encryption off); the new key is applied only when the
	/// server echoes [ServerMessage.PassphraseChanged], so a rejected request leaves the old key intact. With `p`
	/// (when this is an encrypted→encrypted rotation, so an OLD key exists) the new passphrase is also wrapped under
	/// that old key and relayed so connected members ADOPT it automatically (the server still never sees it); `p!`
	/// opts out of auto-distribution for a revocation-style rotation, leaving members to re-enter the new secret
	/// out-of-band. A non-owner CANNOT rotate the shared key — `p` is accepted only to ADOPT an owner's
	/// already-announced rotation ([#memberRekeyPending]), applied LOCALLY (no server round-trip) and verified
	/// against the channel's announced key-check. To use a different passphrase otherwise, a non-owner switches
	/// channels with `c`. (Mirrors the web client's owner-only passphrase field + "share with members" box.)
	private void changePassphrase(String arg, boolean share) {
		String passphrase = arg.strip();
		if (currentMode == ChannelMode.GLOBAL_PTT) {
			log("[passphrase] the global room is the server's unencrypted broadcast channel — encryption isn't available there.");
			return;
		}
		if (selfId.equals(ownerId)) {
			try {
				FrameCrypto next = deriveCrypto(passphrase, currentMode, currentChannel);   // blank -> null -> no encryption
				FrameCrypto old = crypto;   // the key we currently hold — read once off the volatile
				// Auto-distribution: wrap the new passphrase under the OLD key so connected members adopt it
				// automatically. Only for an encrypted→encrypted rotation (an old key must exist) and only when
				// sharing — a plaintext→encrypted ENABLE has no old key to wrap under (first secret goes out-of-band),
				// disabling carries nothing, and `p!` deliberately withholds it (revocation-style). Server relays the
				// blob blindly; only an old-key holder can unwrap it.
				String wrappedKey = share && old != null && next != null ? old.wrap(passphrase) : null;
				pendingPassphrase = next == null ? null : passphrase;
				rekeyInFlight = true;
				enqueue(new ClientMessage.ChangePassphrase(next == null ? null : next.keyCheck(), wrappedKey));
				log(next == null ?
						"[passphrase] requested encryption OFF for everyone..."
						: wrappedKey == null
						  ? "[passphrase] requested a re-key for everyone (members must enter the new passphrase out-of-band)..."
						  : "[passphrase] requested a re-key — connected members will adopt it automatically...");
			} catch (GeneralSecurityException e) {
				log("[passphrase] key derivation failed: " + e.getMessage());
			}
		} else if (memberRekeyPending()) {
			applyMemberPassphrase(passphrase);   // adopt the owner's announced rotation (the only non-owner use)
		} else {
			log("[denied] only the channel owner can change this channel's passphrase — use 'c <channel> [mode] [key]' to switch to a channel with a different one.");
		}
	}

	/// Whether the channel announces an encryption state this (non-owner) member does not currently match — i.e.
	/// the owner rotated or enabled the passphrase and we still hold the wrong key (or none). Only then may a
	/// member set the CURRENT channel's passphrase (to adopt the announced one); otherwise changing it is the
	/// owner's prerogative.
	private boolean memberRekeyPending() {
		// Read each volatile ONCE into a local: the listener thread can null both fields (a disable rotation)
		// concurrently with this console-thread call, so re-reading `crypto`/`currentChannelKeyCheck` between the
		// null-check and the deref would risk an NPE on `crypto.keyCheck()` / `currentChannelKeyCheck.equals(...)`.
		String announced = currentChannelKeyCheck;
		FrameCrypto held = crypto;
		return announced != null && (held == null || !announced.equals(held.keyCheck()));
	}

	/// A member adopting the owner's new passphrase locally. Re-derives the key and applies it only if it matches
	/// the channel's announced key-check ([#rekeyAction]); on a mismatch it warns and KEEPS the current key —
	/// never falling back to plaintext, which would broadcast in the clear into a still-encrypted channel.
	private void applyMemberPassphrase(String passphrase) {
		try {
			FrameCrypto candidate = currentChannelKeyCheck == null ? null : deriveCrypto(passphrase, currentMode, currentChannel);
			switch (rekeyAction(currentChannelKeyCheck, candidate)) {
				case DISABLE -> {
					crypto = null;
					currentPassphrase = null;
					log("[passphrase] this channel is currently unencrypted.");
				}
				case APPLY -> {
					crypto = candidate;
					currentPassphrase = passphrase;
					log("[passphrase] re-keyed — end-to-end encryption updated.");
				}
				case KEEP ->
						log("[passphrase] that passphrase doesn't match the channel's current key — try 'p <passphrase>' again.");
			}
		} catch (GeneralSecurityException e) {
			log("[passphrase] key derivation failed: " + e.getMessage());
		}
	}

	/// Applies an owner's passphrase rotation echoed by the server. The server relays only the new key-check (or
	/// `null` to disable encryption), never the passphrase — so we re-derive the key from a passphrase we already
	/// hold and verify it against `keyCheck` via [#rekeyAction]. If we initiated this (we are the owner) that is
	/// the passphrase we just submitted; for a member it is the one currently in use, which won't match until the
	/// user re-enters the new one with `p`. On a mismatch we KEEP the old key (no plaintext fallback). The
	/// volatile `crypto` swap is read once by the capture/listener threads, so the worst a transition does is drop
	/// a few frames on a failed GCM tag.
	private void handlePassphraseChanged(String keyCheck, String wrappedKey) {
		currentChannelKeyCheck = keyCheck;
		// A new key era — re-arm the one-shot decrypt-failure warning (set on the first failure in onBinary and
		// otherwise never cleared) so a member who misses THIS rotation still gets a fresh cue to re-key. Same WS
		// listener thread as onBinary, so no extra synchronization is needed.
		warnedDecrypt = false;
		String passphrase = rekeyInFlight ? pendingPassphrase : currentPassphrase;
		rekeyInFlight = false;
		pendingPassphrase = null;
		// Auto-adopt: if the owner shared the new passphrase wrapped under the OLD key (which we still hold),
		// unwrap it, confirm it derives the announced key-check, and adopt automatically — seamless for everyone
		// who held the old key (the owner echoing its own rotation included). A missing/foreign/superseded blob
		// (different key, tampered, or a later rotation) throws or mismatches and falls through to the manual path.
		FrameCrypto held = crypto;   // read once off the volatile
		if (keyCheck != null && wrappedKey != null && held != null) {
			try {
				String unwrapped = held.unwrap(wrappedKey);
				FrameCrypto candidate = deriveCrypto(unwrapped, currentMode, currentChannel);
				if (candidate != null && keyCheck.equals(candidate.keyCheck())) {
					crypto = candidate;
					currentPassphrase = unwrapped;
					log("[passphrase] channel re-keyed automatically — end-to-end encryption updated.");
					return;
				}
			} catch (GeneralSecurityException _) {
				// not wrapped under our (old) key, or tampered/superseded — fall back to the manual path below
			}
		}
		try {
			FrameCrypto candidate = keyCheck == null ? null : deriveCrypto(passphrase, currentMode, currentChannel);
			switch (rekeyAction(keyCheck, candidate)) {
				case DISABLE -> {
					crypto = null;
					currentPassphrase = null;
					log("[passphrase] the owner turned encryption OFF for this channel.");
				}
				case APPLY -> {
					crypto = candidate;
					currentPassphrase = passphrase;
					log("[passphrase] channel re-keyed — end-to-end encryption updated.");
				}
				case KEEP ->
						log("[passphrase] the owner changed the passphrase — run 'p <new-passphrase>' to keep talking.");
			}
		} catch (GeneralSecurityException e) {
			log("[passphrase] key derivation failed: " + e.getMessage());
		}
	}

	/// Switches to a different channel WITHOUT dropping the session: the server treats a fresh Join as
	/// "leave the old channel, join the new one" on the same socket, so the session id (and the audio loops)
	/// survive. Mode and passphrase are optional and default to the current ones. Usage: `c <channel> [mode] [key]`.
	private void switchChannel(String args) {
		String[] parts = args.strip().split("\\s+", 3);
		String channel = parts[0];
		ChannelMode mode = parts.length > 1 ? parseMode(parts[1], currentMode) : currentMode;
		// Validate the name locally before the round-trip (like the `n` command and the browser client). Global
		// forces the channel to "global" server-side, so the name only matters — and is only checked — otherwise.
		if (mode != ChannelMode.GLOBAL_PTT && !CHANNEL_NAME.matcher(channel).matches()) {
			System.out.println("Usage: c <channel> [ptt|global|duplex] [passphrase]  (channel = 1-64 chars of letters, digits, _ or -)");
			return;
		}
		switchTo(channel, mode, parts.length > 2 ? parts[2] : currentPassphrase);
	}

	/// Re-derives the E2EE key for the new channel (the key salts on the channel name) and sends the Join; the
	/// resulting Joined snapshot resets the roster/mode like the initial join. NOTE: switching to a channel whose
	/// passphrase doesn't match drops you from the current channel — the server leaves the old channel before the
	/// new join is validated — so supply the right passphrase for the target.
	private void switchTo(String channel, ChannelMode mode, String passphrase) {
		String effective = mode == ChannelMode.GLOBAL_PTT ? "global" : channel;
		if (effective.equals(currentChannel)) {
			log("[switch] already in \"" + effective + "\" — use 'p <passphrase>' to change the passphrase here, or pick a different channel to switch.");
			return;
		}
		try {
			FrameCrypto next = deriveCrypto(passphrase, mode, channel);
			crypto = next;                 // volatile — the capture/playback loops pick up the new key
			currentPassphrase = passphrase;
			// Advance the (re)connect target too, optimistically like crypto above: if the server refuses this
			// in-place switch with CHANNEL_ROUTING_MISMATCH (the target lives on another instance under channel
			// affinity), reconnect() rebuilds the socket against exactly this channel/mode and onOpen re-joins it.
			connectChannel = channel;
			connectMode = mode;
			// Do NOT advance currentChannelKeyCheck here: leave it at the OLD channel's value until the server
			// confirms the switch (the Joined handler baselines it). The server still routes our audio to the OLD
			// channel during the join round-trip, so if we're switching OUT of an encrypted channel to a plaintext
			// one, keeping the old (non-null) key-check makes the transmit gate (outboundFrame) keep dropping
			// frames instead of leaking cleartext into the channel we're leaving.
			String display = memberNames.getOrDefault(selfId, options.display());
			enqueue(new ClientMessage.Join(channel, mode, display, next == null ? null : next.keyCheck()));
			log("[switch] joining \"" + channel + "\" (" + mode + ")...");
		} catch (GeneralSecurityException e) {
			log("[switch] key derivation failed: " + e.getMessage());
		}
	}

	/// Asks the server to change our display name. Validated locally for a fast no, but the server validates
	/// authoritatively and the resulting [ServerMessage.MemberRenamed] (broadcast back to us) is what actually
	/// updates the roster — so a rejected name surfaces as an `[error]` line instead.
	private void rename(String newName) {
		if (!DISPLAY_NAME.matcher(newName).matches()) {
			System.out.println("Usage: n <new-name>  (1-32 chars of letters, digits, _ . or -)");
			return;
		}
		if (newName.equals(memberNames.get(selfId))) {
			System.out.println("[name] that is already your display name.");   // a no-op the server would reject anyway
			return;
		}
		enqueue(new ClientMessage.Rename(newName));
	}

	/// `o <id-prefix>` — hand channel ownership to another member, identified by the start of its session id (the
	/// `#`-prefix shown next to each member in the roster; a leading `#` is optional). Gated locally to the owner
	/// (the server enforces it too); the resulting [ServerMessage.OwnerChanged] is what actually moves the
	/// owner-only controls.
	private void transferOwnership(String arg) {
		if (!selfId.equals(ownerId)) {
			log("[denied] only the channel owner can transfer ownership");
			return;
		}
		String prefix = arg.strip();
		if (prefix.startsWith("#")) {
			prefix = prefix.substring(1);
		}
		if (prefix.isBlank()) {
			System.out.println("Usage: o <id-prefix>  (the #id shown next to a member in 'w')");
			return;
		}
		List<String> matches = otherMembersMatching(prefix);
		switch (matches.size()) {
			case 0 -> log("[transfer] no other member's id starts with \"" + prefix + "\" — use 'w' to list members.");
			case 1 -> {
				String target = matches.getFirst();
				enqueue(new ClientMessage.TransferOwnership(target));
				log("[transfer] handing ownership to " + name(target) + "...");
			}
			default ->
					log("[transfer] \"" + prefix + "\" matches " + matches.size() + " members — use more of the id.");
		}
	}

	/// The other members (never ourself) whose session id starts with `needle` — the shared resolution for the
	/// id-prefix targeting used by `o` (transfer ownership) and `mute`/`unmute`. Ourself is excluded because none
	/// of those actions apply to it (you can't transfer to, or mute, yourself).
	private List<String> otherMembersMatching(String needle) {
		return memberNames.keySet().stream()
				.filter(id -> !id.equals(selfId) && id.startsWith(needle))
				.toList();
	}

	/// `mute <#id|all>` / `unmute <#id|all>` — owner-only moderation. `all` mutes (or unmutes) every OTHER member at
	/// once; otherwise the target is identified by the start of its session id (the `#`-prefix shown in 'w', a
	/// leading `#` optional). Gated locally to the owner (the server enforces it too, and never trusts the client);
	/// the resulting [ServerMessage.MemberMuted] broadcast is what actually updates the roster and stops a muted
	/// member's mic. Applies immediately — there is no staged apply for moderation.
	private void muteMember(String arg, boolean muted) {
		String verb = muted ? "mute" : "unmute";
		if (!selfId.equals(ownerId)) {
			log("[denied] only the channel owner can " + verb + " members");
			return;
		}
		String prefix = arg.strip();
		if (prefix.equalsIgnoreCase("all")) {
			enqueue(new ClientMessage.MuteAll(muted));
			log("[" + verb + "] " + verb + "-ing all other members...");
			return;
		}
		if (prefix.startsWith("#")) {
			prefix = prefix.substring(1);
		}
		if (prefix.isBlank()) {
			System.out.println("Usage: " + verb + " <#id|all>  (the #id shown next to a member in 'w', or 'all')");
			return;
		}
		List<String> matches = otherMembersMatching(prefix);
		switch (matches.size()) {
			case 0 ->
					log("[" + verb + "] no other member's id starts with \"" + prefix + "\" — use 'w' to list members.");
			case 1 -> {
				String target = matches.getFirst();
				enqueue(new ClientMessage.MuteMember(target, muted));
				log("[" + verb + "] " + verb + "-ing " + name(target) + "...");
			}
			default ->
					log("[" + verb + "] \"" + prefix + "\" matches " + matches.size() + " members — use more of the id.");
		}
	}

	/// `lock` / `unlock` — owner-only: stop / allow NEW members joining this channel. Gated locally to the owner
	/// (the server enforces it too and never trusts the client); the resulting [ServerMessage.ChannelLocked] is what
	/// actually flips everyone's state. Existing members are never affected.
	private void setChannelLock(boolean locked) {
		if (!selfId.equals(ownerId)) {
			log("[denied] only the channel owner can " + (locked ? "lock" : "unlock") + " the channel");
			return;
		}
		enqueue(new ClientMessage.SetLocked(locked));
		log("[" + (locked ? "lock" : "unlock") + "] requesting to " + (locked ? "lock" : "unlock") + " the channel...");
	}

	private WebSocket connect(String token) {
		// Carry the effective channel as the ?channel= routing key so a channel-affinity ingress can pin this
		// socket to the instance that owns the channel (see the server's ChannelHandshakeInterceptor). Harmless
		// single-instance. Global forces the routing key to "global", matching the Join's effective channel. Reads
		// the connect target (not options) so a reconnect routes to the channel we switched to, not the startup one.
		String routingChannel = connectMode == ChannelMode.GLOBAL_PTT ? "global" : connectChannel;
		return httpClient.newWebSocketBuilder()
				.buildAsync(
						URI.create(options.server().replaceFirst("^http", "ws") + "/ws/audio"
								+ "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
								+ "&channel=" + URLEncoder.encode(routingChannel, StandardCharsets.UTF_8)),
						new ClientListener()
				)
				.join();
	}

	private void enqueue(ClientMessage message) {
		sendQueue.offer(new Outbound.Text(JSON_MAPPER.writeValueAsString(message)));
	}

	private void sendJoin() {
		// (Re)announce us on this socket's target channel. connectChannel/connectMode (not options) so a reconnect
		// joins the channel we switched to; the current display (not options.display()) so a rename survives it.
		enqueue(new ClientMessage.Join(
				connectChannel,
				connectMode,
				memberNames.getOrDefault(selfId, options.display()),
				crypto == null ? null : crypto.keyCheck()
		));
	}

	/// Tears the session down: stops the loops, closes the WebSocket, closes the [AudioEngine], and shuts the
	/// HttpClient down (bounded, so it can't hang on a slow server). Idempotent, so it is safe in a
	/// try-with-resources block (the launcher's) — note some paths exit the process directly via
	/// [#onConnectionLost] and so never reach here.
	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		running.set(false);
		// Closing the WebSocket ends the session — the bearer token is stateless and self-expiring, so
		// there is nothing to revoke server-side.
		if (webSocket != null) {
			webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
		}
		audio.close();
		// HttpClient has been AutoCloseable since Java 21; shutting it down frees its selector/executor
		// threads. Its close() blocks until in-flight operations (the WebSocket included) finish, so bound the
		// wait: shut down gracefully, then force it if the close handshake doesn't drain in time — teardown
		// must never hang on a slow or vanished server.
		httpClient.shutdown();
		try {
			if (!httpClient.awaitTermination(HTTP_SHUTDOWN_GRACE)) {
				httpClient.shutdownNow();
			}
		} catch (InterruptedException _) {
			httpClient.shutdownNow();
			Thread.currentThread().interrupt();
		}
		System.out.println("Goodbye.");
	}

	/// Rebuilds the relay socket against the current connect target and re-joins it. Triggered by
	/// `CHANNEL_ROUTING_MISMATCH`: under channel affinity the target channel lives on another instance, so only a
	/// fresh handshake — carrying `?channel=<target>` — is routed to the owning instance; an in-place switch can't
	/// reach it. [#switchTo] already applied the target's mode/key and advanced [#connectChannel]/[#connectMode], so
	/// [ClientListener#onOpen]'s [#sendJoin] lands us straight in it.
	///
	/// Runs on its own virtual thread — never the listener callback thread (whose executor [#connect]'s `join()`
	/// blocks on) and never the console thread. A fresh token keeps it robust even if the original has expired
	/// (login takes no input). The [#reconnecting] guard both collapses a burst of mismatches into one reconnect and
	/// tells the old socket's `onClose` this drop is intentional, so it is not treated as a lost connection.
	private void reconnect() {
		if (!reconnecting.compareAndSet(false, true)) {
			return;   // a reconnect is already in flight; ignore piled-up mismatches
		}
		log("[switch] \"" + connectChannel + "\" is served by another instance — reconnecting to reach it...");
		Thread.ofVirtual().name("ptt-reconnect").start(() -> {
			try {
				WebSocket previous = webSocket;
				if (previous != null) {
					previous.sendClose(WebSocket.NORMAL_CLOSURE, "switching instance");
				}
				webSocket = connect(login());   // onOpen publishes the new socket + re-joins the target via sendJoin()
			} catch (IOException | RuntimeException e) {
				log("[reconnect] could not switch to \"" + connectChannel + "\": " + e.getMessage());
				onConnectionLost();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				onConnectionLost();
			} finally {
				reconnecting.set(false);
			}
		});
	}

	/// Ends the session with a CLEAN WebSocket close — a `NORMAL_CLOSURE` frame the server sees, instead of the
	/// abrupt EOF an immediate `System.exit` leaves — then stops the process. Used by the fatal join rejections
	/// (wrong passphrase, locked or full channel) and by a lost connection: the console loop is parked in a
	/// non-interruptible `System.in` read and can't observe a flag, so stopping the process is the only way out.
	/// The close runs on its OWN virtual thread, NOT the WebSocket listener callback thread these paths fire on
	/// (whose executor [#close]'s bounded HttpClient shutdown must drain to flush the frame). Fires exactly once.
	private void exitGracefully(String reason) {
		if (!running.getAndSet(false)) {
			return;
		}
		log(reason);
		Thread.ofVirtual().name("ptt-shutdown").start(() -> {
			close();   // sends NORMAL_CLOSURE, then the bounded HttpClient shutdown flushes it before we halt
			System.exit(0);
		});
	}

	/// Reacts to the WebSocket dropping. A user-initiated quit has already flipped `running` and is tearing down
	/// on the main thread (so [#exitGracefully] no-ops there); any other close means the server went away while we
	/// were live, so end gracefully and stop.
	private void onConnectionLost() {
		exitGracefully("Server connection lost — exiting.");
	}

	/// The decision for an announced passphrase change, given the channel's announced key-check and the key a
	/// client derived from the passphrase it currently holds. Pure (no field access) so the security rule — NEVER
	/// adopt a key whose key-check doesn't match the announced one, and only clear the key on an explicit disable
	/// — is unit-testable without a live socket. `APPLY`: adopt `candidate`. `KEEP`: hold the current key (we
	/// don't have the new passphrase yet, or it mismatched). `DISABLE`: the owner turned encryption off.
	enum RekeyAction {APPLY, KEEP, DISABLE}

	private sealed interface Outbound {
		record Text(String json) implements Outbound {
		}

		record Binary(byte[] data) implements Outbound {
		}
	}

	private final class ClientListener implements WebSocket.Listener {

		@SuppressWarnings("StringBufferField")
		private final StringBuilder textBuffer = new StringBuilder();
		private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();

		@Override
		public void onOpen(WebSocket webSocket) {
			// Publish this socket as the live one BEFORE queueing its Join, so the sender thread (which reads the
			// volatile webSocket) sends that Join on THIS socket even on a reconnect — where the sender is already
			// running and the reconnect thread's own `webSocket = connect(...)` assignment may not have landed yet.
			WalkieClient.this.webSocket = webSocket;
			log("[connected]");
			sendJoin();
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
			textBuffer.append(data);
			if (last) {
				String json = textBuffer.toString();
				textBuffer.setLength(0);
				try {
					handleServerMessage(json);
				} catch (RuntimeException e) {
					log("[warn] could not handle message: " + e.getMessage());
				}
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
			byte[] chunk = new byte[data.remaining()];
			data.get(chunk);
			binaryBuffer.writeBytes(chunk);
			if (last) {
				byte[] frame = binaryBuffer.toByteArray();
				binaryBuffer.reset();
				// Demultiplex by the server-prepended stream index, then strip it (before the decrypt branch)
				// so the body handed to the engine is the same [tag][payload] / E2EE envelope a sender produced.
				if (frame.length >= 2) {
					int sid = frame[0] & 0xFF;
					byte[] body = Arrays.copyOfRange(frame, 1, frame.length);
					FrameCrypto key = crypto;   // read the volatile once — a concurrent channel switch may swap it
					if (key == null) {
						if (body.length > 0 && (body[0] & 0xFF) == E2EE_SCHEME) {
							// Encrypted audio arriving while we hold no key (a plaintext->encrypted enable we haven't
							// adopted): drop it — the engine would treat 0xE2 as an unknown codec tag and silently emit
							// nothing — and explain once, like the browser's warnedEncryptedNoKey path.
							if (!warnedEncryptedNoKey) {
								warnedEncryptedNoKey = true;
								log("[warn] received end-to-end-encrypted audio but no passphrase is set — run 'p <passphrase>' to hear it.");
							}
						} else {
							audio.play(sid, body);
						}
					} else {
						try {
							audio.play(sid, key.decrypt(body));
						} catch (GeneralSecurityException _) {
							if (!warnedDecrypt) {
								warnedDecrypt = true;
								log("[warn] could not decrypt audio — confirm everyone uses the same --key, --channel, and --mode");
							}
						}
					}
				}
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			if (reconnecting.get() || webSocket != WalkieClient.this.webSocket) {
				// An intentional reconnect closing the OLD socket (or a late close of one we already replaced) — not
				// a lost connection. The new socket's own future close, once reconnecting clears, is handled normally.
				return null;
			}
			log("[closed " + statusCode + (reason.isBlank() ? "" : " " + reason) + "]");
			onConnectionLost();
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			if (reconnecting.get() || webSocket != WalkieClient.this.webSocket) {
				return;   // an error on a socket we are intentionally tearing down / have already replaced
			}
			log("[error] " + error.getMessage());
			onConnectionLost();
		}
	}
}
