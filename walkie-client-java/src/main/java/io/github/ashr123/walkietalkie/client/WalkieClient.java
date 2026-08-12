package io.github.ashr123.walkietalkie.client;

import io.github.ashr123.walkietalkie.shared.protocol.*;
import io.github.jaredmdobson.concentus.OpusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import javax.sound.sampled.LineUnavailableException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

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
	/// Mirrors the server's DISPLAY_NAME (ConnectionService) and the browser's names.js: letters, combining marks and
	/// digits from any script — `\p{M}` because Hebrew niqqud and Arabic diacritics ARE marks — plus a plain space,
	/// `_`, `.` and `-`, 1-32 code points. Everything invisible is excluded: the other separators (`\p{Zs}`) and every
	/// format/control character (`\p{C}`), since a control character can split a log line and a bidi override reorders
	/// the text around it.
	private static final Pattern DISPLAY_NAME = Pattern.compile("[\\p{L}\\p{M}\\p{N} _.-]{1,32}");

	/// The canonical form the SERVER stores and broadcasts (its canonicalDisplayName): NFC, then stripped. Applied
	/// here too so a locally-rejected name is judged on the same string the server would judge, and so `n <name>`
	/// with a trailing space is the no-op it looks like rather than a rename the server quietly rewrites.
	/// Normalising first and stripping second is deliberate: a name of nothing but spaces has to become empty for
	/// the pattern to reject it. Spaces INSIDE the name are left as typed.
	/// The canonical form of a channel name — NFC, then stripped — the form that must be used for the key
	/// derivation, the `?channel=` routing key and the `Join`, because it is the PBKDF2 SALT. Two members whose
	/// channel names differ by one byte derive DIFFERENT keys and hear nothing from each other in the same room,
	/// reported as a `PASSPHRASE_MISMATCH` for a passphrase that looks identical. Measured: Hebrew `שׁלום` written
	/// with the precomposed presentation form U+FB2A and as U+05E9 U+05C1 renders identically and derives a
	/// different key before NFC, the same key after. Mirrors `ConnectionService.canonicalChannelName` and the
	/// browser's `canonicalChannelName` in `static/assets/names.js`.
	/// Whether `raw` is an acceptable channel name once canonicalised — the same question the browser's
	/// `isValidChannelName` answers, exposed so the parity vectors can assert it without reaching for the pattern.
	static boolean isValidChannelName(String raw) {
		return CHANNEL_NAME.matcher(canonicalChannelName(raw)).matches();
	}

	static String canonicalChannelName(String requested) {
		if (requested == null) {
			return null;
		}
		return CHANNEL_WHITESPACE.matcher(Normalizer.normalize(requested, Normalizer.Form.NFC))
				.replaceAll(" ")
				.strip();
	}

	private static String canonicalDisplayName(String requested) {
		return requested == null ? null : Normalizer.normalize(requested, Normalizer.Form.NFC).strip();
	}
	/// Channel-name charset, mirrored from the server (and the browser client's CHANNEL_NAME) so the `c` command
	/// and the initial `--channel` are rejected locally before the round-trip. Note `.` is allowed in a display
	/// name but NOT a channel name.
	/// Letters, combining marks and digits from ANY script, plus `_` and `-`, 1-64 code points. Mirrors the
	/// server's `ConnectionService.CHANNEL_NAME` and the browser's in `static/assets/names.js`.
	///
	/// No whitespace, and that restriction is load-bearing HERE specifically: [ConsoleUi]'s `switchChannel` parses
	/// `c <channel> [mode] [key]` by splitting on `\s+`, so a room name with a space in it could not be typed at
	/// this prompt at all.
	/// Collapsed whitespace: `\p{Zs}` (every Unicode space separator) plus the five ASCII control whitespace
	/// characters, written out rather than as `\s` because Java's `\s` is ASCII-only while JavaScript's matches
	/// NBSP — using `\s` on both sides would have the browser accept a name (collapsing the NBSP) that this
	/// rejected, which is exactly the two-clients-disagree failure the channel-name rules exist to prevent.
	/// `\p{Zs}` has identical membership in both languages. See the browser's `canonicalChannelName` in
	/// `static/assets/names.js`, and the parity vectors in `names.test.js` / `ConnectionServiceTest`.
	private static final Pattern CHANNEL_WHITESPACE = Pattern.compile("[\\p{Zs}\\t\\n\\r\\x0B\\f]+");

	static final Pattern CHANNEL_NAME = Pattern.compile("[\\p{L}\\p{M}\\p{N} _-]{1,64}");
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
	// Session ids the owner has muted (server-authoritative — the server also DROPS their relay audio). Held as ONE
	// volatile IMMUTABLE set, republished whole on every change by the single listener thread — the FloorSnapshot
	// discipline. Not a mutable concurrent set: applying a ServerMessage.MuteStatus snapshot in place (clear, then
	// add-all) would expose an EMPTY set to the capture thread mid-swap, and that thread's per-frame read is the local
	// stop for our own mute. A reader sees the whole old set or the whole new one, never a half-applied one.
	private volatile Set<String> mutedMembers = Set.of();
	/// Everything this client shows a person goes through here — see [WalkieUi]. Final, and the first thing the
	/// constructor assigns, because construction itself reports progress ("Connecting to …", the audio description)
	/// and `deriveCrypto` can warn before anything else exists.
	private final WalkieUi ui;
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
	// Join sent from onOpen. Distinct from currentChannel/currentMode (server-CONFIRMED) — switchTo advances this
	// to the target OPTIMISTICALLY so a CHANNEL_ROUTING_MISMATCH reconnect rebuilds against the channel we asked
	// for. Held as ONE volatile record (like floorSnapshot) so channel + mode always move together: the console
	// thread writes it in switchTo while the reconnect/onOpen thread reads it in connect()/sendJoin(), and two
	// separate volatiles let a second switchTo racing an in-flight reconnect pair a new channel with the old mode
	// (only under channel-affinity, and self-healing, but the record removes the class outright).
	private volatile ConnectTarget connectTarget;
	private volatile String currentPassphrase;   // passphrase backing the current channel's key (for switch defaults)
	private volatile String currentChannelKeyCheck;   // the channel's currently-announced key-check (null = unencrypted); the yardstick a member re-keys against
	/// Whether the server has CONFIRMED the channel in [#currentChannel], which the constructor and [#switchTo] both
	/// set optimistically (the reconnect target and the key derivation need it before any answer arrives).
	///
	/// The distinction is load-bearing in exactly one place, [#joinRefused], which used "do we have a channel?" to tell
	/// a refused SWITCH (keep everything — the server never departed us) from a refused FIRST join (nothing to keep).
	/// Because the constructor had already filled the field, an initial refusal took the wrong branch: it told the user
	/// "You are still in \"team1\"" about a channel they had never entered, and kept the phantom. A window then had no
	/// way back — its adaptive button saw the typed name as the channel it was already in, so a mistyped passphrase
	/// could only be corrected by disconnecting.
	private volatile boolean channelConfirmed;
	private volatile boolean rekeyInFlight;      // true between sending our own ChangePassphrase (owner) and its echoed PassphraseChanged

	// --- HTTP login + WebSocket -------------------------------------------------------------------
	private volatile String pendingPassphrase;   // the new passphrase we (as owner) submitted, applied when the echo arrives
	private volatile boolean warnedDecrypt; // listener thread only today, but volatile so the warn-once intent survives a threading refactor
	private volatile boolean warnedEncryptedNoKey; // warn-once (like warnedDecrypt): encrypted audio arrived while we hold no key
	private volatile boolean welcomeShown;  // print the role-aware help once, after the first Joined reveals our role (same listener-thread-only-but-volatile rationale as warnedDecrypt)
	private volatile boolean channelLocked; // whether the owner has locked the channel to new members (from Joined/ChannelLocked); volatile — set on the listener thread, read on the console thread for 'w'
	// --- Push-to-talk floor snapshot (the authoritative ServerMessage.FloorStatus, from which ALL floor UI is
	// derived — see floorStateFor). Published by the listener thread (handleFloorStatus / the Joined handler) and
	// read on the console thread by the state-driven `t` control (toggleTalk). Held as ONE volatile record so the
	// holder and the queue always move together: two separate volatiles let the console thread pair a freshly
	// published holder with the OLD queue (or vice versa) mid-publish and derive the wrong FloorState — e.g. sending
	// ReleaseFloor instead of RequestFloor and dropping a floor the user just reserved. volatile for cross-thread
	// visibility, mirroring currentMode/crypto; read ONCE into a local before use.
	private volatile FloorSnapshot floorSnapshot = FloorSnapshot.IDLE;
	private volatile boolean floorQueueEnabled;   // whether the owner-toggleable floor queue is on (from Joined / FloorQueueChanged)
	private volatile boolean muteNewMembers;      // whether the owner mutes every arrival (from Joined / MuteNewMembersChanged)
	// Set when the server tells us it's our turn (FloorReserved / a FloorStatus that makes us the reserved head);
	// cleared when we claim (FloorGranted / a FloorStatus showing us live) or when the window lapses and the next
	// FloorStatus drops us (then we log "[your turn passed]"). Listener-thread-only today, volatile for the same
	// warn-once-survives-a-refactor rationale as warnedDecrypt.
	private volatile boolean awaitingClaim;
	/// The floor situation this client last LOGGED (see [#floorNarration]); an unchanged one is not narrated again,
	/// so a snapshot that repeats the status quo — a queue toggle, a mute change, a member leaving — passes quietly.
	private String lastFloorNarration;
	/// What an in-place switch optimistically overwrote, kept so a REFUSED switch can put it back. The server departs
	/// our current channel only once a join succeeds, so a refusal leaves us still in it — and without this we would
	/// sit there holding the target's key, which the transmit gate would (correctly) mute us for. Null when no switch
	/// is in flight. Written on the console thread, read on the listener thread.
	private volatile SwitchRollback switchRollback;
	/// The channel's waiting list of newcomers asking to be admitted — sent by the server ONLY while we own the
	/// channel, so it is empty for everyone else. Published by the listener thread as an immutable copy (the
	/// [FloorSnapshot] discipline: a reader never sees a half-built list) and read by the console thread.
	private volatile List<JoinRequestInfo> joinRequests = List.of();

	WalkieClient(ClientOptions options, WalkieUi ui) throws IOException, InterruptedException, GeneralSecurityException, LineUnavailableException, OpusException {
		this.ui = ui;
		this.options = options;
		// Validate the startup identity/channel locally before opening audio or a socket — the same checks the `n`
		// and `c` commands apply, and the browser client applies on connect. The server validates authoritatively
		// too, but failing fast here avoids a connected-but-not-joined dead-end on a bad --display/--channel.
		// Judge the canonical form, since that is what the server will store — otherwise `--display "Roy Ash "`
		// would be accepted here and then come back subtly different in the roster.
		if (!DISPLAY_NAME.matcher(canonicalDisplayName(options.display())).matches()) {
			throw new IllegalArgumentException("--display must be 1-32 letters, digits or spaces (any language), "
					+ "'_', '.' or '-' with no invisible characters (got: \"" + options.display() + "\").");
		}
		// Canonicalise ONCE and use that string for every one of its jobs — validation, the key derivation, the
		// ?channel= routing key and the Join — so they cannot disagree. See [#canonicalChannelName].
		String channel = canonicalChannelName(options.channel());
		// Global forces the channel to "global" server-side, so the --channel name only matters for the other modes.
		if (options.mode() != ChannelMode.GLOBAL_PTT && !CHANNEL_NAME.matcher(channel).matches()) {
			throw new IllegalArgumentException("--channel must be 1-64 letters or digits in any language, plus '_' or "
					+ "'-', with no whitespace (got: \"" + options.channel() + "\").");
		}
		this.httpClient = HttpClient.newBuilder()
				.sslContext(TlsTrust.forServer(options.server(), options.tlsTruststore()))
				.build();
		this.currentMode = options.mode();
		this.currentChannel = channel;
		this.connectTarget = new ConnectTarget(channel, options.mode());
		this.currentPassphrase = options.key();
		this.audio = new AudioEngine(options, this::sendAudioFrame, ui::note);
		ui.note("Connecting to " + options.server() + " as '" + options.display() + "' ...");
		String token = login();
		crypto = deriveCrypto(options.key(), options.mode(), channel);
		currentChannelKeyCheck = crypto == null ? null : crypto.keyCheck();   // baseline the channel's key-check from our own join key
		audio.start();
		// From here on the CAPTURE DEVICE IS OPEN, so a failure below has something to release. Nobody holds a
		// reference to a client whose constructor threw, so without this the microphone stayed open — with the OS
		// in-use indicator lit — while the window said "Not connected", once per failed attempt. It only became
		// visible with a front end that OUTLIVES a failed connect: a console launcher exits and the JVM cleans up.
		boolean live = false;
		try {
			ui.note("Audio: " + audio.description()
					+ (crypto == null ? "" : ", end-to-end encrypted (AES-256-GCM)"));

			webSocket = connect(token);
			// Start the sender only after webSocket is assigned, so it is published to the sender thread (Thread.start()
			// happens-after the write, and the field is volatile). onOpen republishes each socket it opens — including a
			// reconnect's — into webSocket before queueing that socket's Join, so a Join is never sent on a stale socket.
			Thread.ofVirtual().name("ptt-sender").start(this::senderLoop);
			live = true;
		} finally {
			// A flag and a `finally` rather than a catch: nothing here declares a checked exception, and this way an
			// Error releases the device too. The throw itself is left alone — the caller still sees what failed.
			if (!live) {
				running.set(false);
				audio.close();
			}
		}
	}

	// --- Console-shaped residue, deliberately still here. -----------------------------------------------------------
	//
	// The help text and the mode hint are terminal prose, so they belong with the terminal — except that the CLIENT is
	// what invokes them: the welcome help is printed once from the first Joined handler (only there is our role known,
	// so only there is a role-aware list correct), and the hint is concatenated INTO a status line by the mode-change
	// handler. Moving them would need new hooks on WalkieUi for "you have joined" and "the mode changed", invented
	// with no second front end to check them against — so they stay until there is one, and ConsoleUi calls printHelp
	// for its own `h` command.

	/// Fixed width of the help box's horizontal rule — a cosmetic frame (some command lines run longer than this).
	private static final int HELP_RULE_WIDTH = 98;
	private static final String HELP_RULE = "-".repeat(HELP_RULE_WIDTH);
	/// The commands available to everyone, owner or not. A non-owner additionally sees [#MEMBER_PASSPHRASE_COMMAND];
	/// the owner instead sees [#OWNER_COMMANDS]. (`p` is role-split: a member ADOPTS a shared passphrase, an owner
	/// CHANGES it.)
	private static final String COMMON_COMMANDS = """
			Commands:  t = talk/stop — in push-to-talk it's state-driven: grab a free floor, claim your turn, or join/leave the queue when busy
			           w = who's here
			           c <channel> [mode] [key] = switch channel (quote a name with spaces)
			           n <name> = rename
			           f = hi-fi on/off
			           cancel = stop waiting to be admitted to a locked channel
			           q = quit
			           h = help""";
	/// The owner-only command block — shown in the help ONLY to the current channel owner, and announced verbatim
	/// the instant a member is promoted (see the [ServerMessage.OwnerChanged] handler) so it learns the abilities it
	/// just gained. One source of truth, so the help and the promotion notice can't drift; the server also rejects
	/// these from a non-owner, so hiding them is UI honesty, not the security boundary.
	private static final String OWNER_COMMANDS = """
			Owner:     m <ptt|global|duplex> = change the mode for everyone ('m global' switches YOU to the global room)
			           p <passphrase> = rotate the passphrase for everyone (members auto-adopt); it can't be turned off
			           p! [passphrase] = change the passphrase WITHOUT auto-sharing (members must re-enter it)
			           o <#id> = give ownership to another member
			           mute <#id|all> / unmute <#id|all> = mute or unmute members
			           lock / unlock = lock or unlock the channel to new members
			           queue on / queue off = turn the push-to-talk floor queue on or off
			           entry on / entry off = mute every member that JOINS from now on ('mute all' covers those already here)
			           requests = list the newcomers waiting to be admitted (a locked channel parks them)
			           admit <#id|all> / deny <#id|all> = let a waiting newcomer in, or turn it away""";
	/// The one passphrase command a NON-owner has: adopt a rotation the owner announced but didn't auto-share (an
	/// owner instead changes the passphrase with `p`/`p!` — see [#OWNER_COMMANDS]). The 11 leading spaces align its
	/// `p` under the command column of [#COMMON_COMMANDS] / [#OWNER_COMMANDS] after their text-block indent is
	/// stripped (their " Commands:" / " Owner:" label lines set that margin one space in from the frame).
	private static final String MEMBER_PASSPHRASE_COMMAND =
			"           p [passphrase] = adopt the owner's new passphrase (only needed if you weren't auto-updated)";

	/// Prints the command help for the caller's CURRENT role: the [#COMMON_COMMANDS] everyone has, then either the
	/// non-owner's [#MEMBER_PASSPHRASE_COMMAND] or — when our session id currently owns the channel — the full
	/// [#OWNER_COMMANDS]. The role is read live (not cached at connect), so pressing `h` right after being promoted
	/// shows the owner commands; the sentinel-owned `global` room has no participant owner, so no one there is shown
	/// the owner set.
	void printHelp() {
		ui.hint(HELP_RULE + System.lineSeparator()
				+ COMMON_COMMANDS + System.lineSeparator()
				+ (selfId.equals(ownerId) ? OWNER_COMMANDS : MEMBER_PASSPHRASE_COMMAND) + System.lineSeparator()
				+ HELP_RULE);
	}

	private static String modeHint(ChannelMode mode, boolean micLive) {
		return mode == ChannelMode.FULL_DUPLEX
				? "Full-duplex: mic is " + (micLive ? "live" : "muted") + " — type 't' to mute/unmute."
				: "Push-to-talk: type 't' to grab a free floor (or to join/leave the queue when it's busy; claim with 't' when it's your turn).";
	}

	/// Reports a status line — the running commentary of the session. Kept as a short local name because 97 call
	/// sites read better for it; all it does now is hand the line to [WalkieUi#status], which decides whether that
	/// means a timestamped `System.out.println` or a row in a window.
	private void log(String message) {
		ui.status(message);
	}

	/// Builds the AES-256-GCM frame cipher from `--key` (or the WALKIE_KEY env var), or null to disable
	/// E2EE. Salted with the effective channel (the server forces "global" for global mode), so every
	/// client in the channel derives the same key.
	private FrameCrypto deriveCrypto(String passphrase, ChannelMode mode, String channel) throws GeneralSecurityException {
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
	/// - `plaintextAllowed` AND we hold no key → send the frame in the clear. That flag is the CALLER's own knowledge — true only for
	///   the server-managed `global` room, the one channel that is plaintext by design — and it is the whole point
	///   of this signature. The gate used to infer "unencrypted" from `announcedKeyCheck == null`, a value the
	///   SERVER supplies, so one forged `passphraseChanged { keyCheck: null }` flipped a whole encrypted channel
	///   into transmitting cleartext. Deciding from a fact the client owns means no value the server sends can
	///   produce a plaintext frame in a named channel; the worst it achieves is getting us dropped, i.e. silence;
	/// - else we hold the matching key (`key.keyCheck().equals(announcedKeyCheck)`) → send ciphertext;
	/// - else → **drop** (stay silent). That covers a member still holding a STALE key after a rotation it hasn't
	///   adopted (don't emit audio the rekeyed channel can't decode, and don't desync — a straggler is muted until
	///   it adopts the new key, so the experience is symmetric for everyone), a member holding NO key for an
	///   encrypted channel, and — deliberately — a named channel with nothing announced and nothing held, which
	///   used to fall through to plaintext.
	static byte[] outboundFrame(byte[] frame, FrameCrypto key, String announcedKeyCheck, boolean plaintextAllowed)
			throws GeneralSecurityException {
		// BOTH terms: `plaintextAllowed` comes from the mode, which arrives in the Joined snapshot, so a server that
		// lied about it could otherwise still ask a member of a named channel to talk in the clear. Whether we hold
		// a key is ours alone — we derived it from a passphrase the user typed — and holding one means encryption
		// was intended. So plaintext needs the channel to permit it AND us to have never derived a key for it.
		if (plaintextAllowed && key == null) {
			return frame;
		}
		// `key.keyCheck()` first, so a null `announcedKeyCheck` compares unequal instead of throwing.
		return key != null && key.keyCheck().equals(announcedKeyCheck) ? key.encrypt(frame) : null;
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

	/// The pure decision for an announced passphrase change. Mirrors the browser's `rekeyAction` in e2ee.js.
	///
	/// A null announced key-check maps to `KEEP`, not to dropping the key. It used to mean "the owner turned
	/// encryption off", and this returned a third `DISABLE` action that cleared `crypto`. No conformant server can
	/// send that any more — `ChannelRegistry.changePassphrase` refuses a null new key-check with
	/// `PASSPHRASE_REQUIRED` — and obeying it would have become a DOWNGRADE rather than a feature: with `crypto`
	/// cleared, [#outboundFrame] sends in the clear, so one forged or buggy broadcast would put every member of an
	/// encrypted channel on the air unencrypted. Keeping the key we hold fails closed instead.
	static RekeyAction rekeyAction(String announcedKeyCheck, FrameCrypto candidate) {
		return announcedKeyCheck != null && candidate != null && announcedKeyCheck.equals(candidate.keyCheck()) ?
				RekeyAction.APPLY :
				RekeyAction.KEEP;
	}

	/// Derives this client's floor state from the authoritative [ServerMessage.FloorStatus] snapshot (`holderId` +
	/// `waiting`) and our own session id — the SAME rule the design mandates and the browser applies. Pure (no field
	/// access) so the state-driven `t` decision built on it ([#floorActionFor]) is unit-testable without a live socket.
	/// - `LIVE` — we hold the floor (`holderId == self`).
	/// - `MY_TURN` — the floor is free and we are the reserved head (`holderId == null && waiting.get(0) == self`);
	///   there is no separate "reserved" field because the server reserves the head the instant the floor frees.
	/// - `IN_LINE` — we are waiting further back in the queue (in `waiting`, but not the reserved head).
	/// - `IDLE` — none of the above: the floor is free, reserved for another, or held by another.
	static FloorState floorStateFor(String selfId, String holderId, List<String> waiting) {
		if (selfId.equals(holderId)) {
			return FloorState.LIVE;
		}
		if (holderId == null && !waiting.isEmpty() && waiting.getFirst().equals(selfId)) {
			return FloorState.MY_TURN;
		}
		return waiting.contains(selfId) ? FloorState.IN_LINE : FloorState.IDLE;
	}

	/// The [ClientMessage] the state-driven `t` control sends for a given [FloorState] in a push-to-talk channel —
	/// the unified single control from the design (no separate queue command). Pure so the decision table is
	/// unit-testable:
	/// - `LIVE` → [ClientMessage.ReleaseFloor] (stop talking);
	/// - `IN_LINE` → [ClientMessage.ReleaseFloor] (leave the queue);
	/// - `MY_TURN` → [ClientMessage.RequestFloor] (claim it — the server grants and replies [ServerMessage.FloorGranted]);
	/// - `IDLE` → [ClientMessage.RequestFloor] (grab if free; enqueue if busy and the queue is on; ignored by the
	///   server if busy and the queue is off — the snapshot already shows it busy, so no state changes).
	static ClientMessage floorActionFor(FloorState state) {
		return switch (state) {
			case LIVE, IN_LINE -> new ClientMessage.ReleaseFloor();
			case MY_TURN, IDLE -> new ClientMessage.RequestFloor();
		};
	}

	/// The authoritative floor snapshot — the live holder's id (`null` when nobody is talking) and the FIFO queue —
	/// held as ONE immutable value so the console thread's `t` control always reads a consistent (holder, waiting)
	/// pair (see [#floorSnapshot]). The publisher stores `waiting` as an immutable copy.
	record FloorSnapshot(String holder, List<String> waiting) {
		static final FloorSnapshot IDLE = new FloorSnapshot(null, List.of());
	}

	/// The channel + mode this socket (re)connects and joins as (see [#connectTarget]) — one immutable value so the
	/// console thread (switchTo) and the reconnect/onOpen thread (connect / sendJoin) never pair a fresh channel
	/// with a stale mode.
	record ConnectTarget(String channel, ChannelMode mode) {
	}

	/// The key material and connect target belonging to the channel we are CURRENTLY in, captured before an in-place
	/// switch optimistically replaces them with the target's (see [#switchRollback]).
	record SwitchRollback(FrameCrypto crypto, String passphrase, ConnectTarget target) {
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
		// No channel, no audio. The server routes such a frame nowhere anyway, but the local stop is what matters:
		// the channel-less state also nulls currentChannelKeyCheck (see the initial-connect refusal path), so
		// without this the gate below would be deciding for a channel we are not in.
		if (currentChannel == null) {
			return;
		}
		if (mutedMembers.contains(selfId)) {
			// Owner-muted: drop before the wire. A volatile read of an immutable set — see the field's note on why it is
			// republished whole rather than mutated in place. We also stop the mic on the mute snapshot, so this only closes the brief
			// window where a frame captured just before the mute lands here — the server drops it anyway, this is the
			// authoritative local stop. A lock-free concurrent-set read, cheap on the per-frame path.
			return;
		}
		try {
			byte[] out = outboundFrame(frame, crypto, currentChannelKeyCheck, currentMode == ChannelMode.GLOBAL_PTT);
			if (out != null) {
				sendQueue.offer(new Outbound.Binary(out));
			}
		} catch (GeneralSecurityException _) {
			// drop this frame; keep going
		}
	}

	private void handleServerMessage(String json) {
		applyServerMessage(json);
		// Exactly one signal per inbound message, whether or not that message moved anything. Notifying
		// unconditionally is cheaper to reason about than notifying per mutation: the handler below writes 15-odd
		// fields across a 200-line switch, so "did anything change?" would be a second thing to keep in step with the
		// first, and a view that pulls a whole snapshot loses nothing by being told to re-read once too often.
		ui.stateChanged();
	}

	private void applyServerMessage(String json) {
		switch (JSON_MAPPER.readValue(json, ServerMessage.class)) {
			case ServerMessage.Joined(String selfId,
									  String channel,
									  ChannelMode mode,
									  Transport transport,
									  String ownerId,
									  boolean locked,
									  boolean floorQueueEnabled,
									  boolean muteNewMembers,
									  List<MemberInfo> members) -> {
				boolean channelChanged = !channel.equals(this.currentChannel);
				this.selfId = selfId;
				this.ownerId = ownerId;
				this.currentMode = mode;
				this.currentChannel = channel;
				this.channelConfirmed = true;   // the server put us here; a later refusal now knows there is something to keep
				this.channelLocked = locked;   // adopt the channel's lock state from the snapshot (covers an in-place re-join)
				// The switch (or initial join) landed, so what it overwrote is now the truth — there is nothing to
				// roll back. Leaving a stale rollback here would let a LATER refusal restore this channel's
				// superseded key.
				this.switchRollback = null;
				this.floorQueueEnabled = floorQueueEnabled;   // adopt the channel's queue setting (authoritative on every Joined)
				this.muteNewMembers = muteNewMembers;         // ditto for the standing "mute every arrival" rule
				// We dialled /ws/audio, but the channel — not the endpoint — decides the media plane, and a joiner
				// adopts it. If it is WebRTC we are in a channel whose audio we can neither send nor receive, so
				// leave at once rather than sit in it looking connected. Handled before the roster/mute seeding
				// below only for clarity; leaveBecauseWebRtc's own Leave frame is what actually ends it.
				if (transport == Transport.SIGNALING && leaveBecauseWebRtc()) {
					return;
				}
				if (channelChanged) {
					// Baseline the channel's announced key-check from the key we joined with — only on an ACTUAL
					// channel change (a switch). switchTo deliberately doesn't advance it, so the transmit gate keeps
					// suppressing plaintext through the switch round-trip; a same-channel re-snapshot must not reset
					// it either (it would clobber a pending rotation), mirroring the browser's onJoined.
					currentChannelKeyCheck = crypto == null ? null : crypto.keyCheck();
					// Clear the stale floor snapshot from the OLD channel. The server sends a fresh authoritative
					// FloorStatus right after this Joined (to-one), so it re-seeds immediately; this just stops the
					// `t` control acting on the previous channel's holder/queue in the interim.
					floorSnapshot = FloorSnapshot.IDLE;
					awaitingClaim = false;
				}
				memberNames.clear();
		lastFloorNarration = null;   // a new channel narrates its floor afresh
				// Seed the mute set from the roster so a member joining a channel where someone is already muted renders
				// it — built into a local and published ONCE, so no reader ever sees it half-filled.
				Set<String> seededMutes = new HashSet<>();
				members.forEach(member -> {
					memberNames.put(member.id(), member.displayName());
					if (member.muted()) {
						seededMutes.add(member.id());
					}
				});
				mutedMembers = Set.copyOf(seededMutes);
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
						);
				ui.hint(modeHint(mode, audio.isTransmitting()));
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
			case ServerMessage.MuteStatus(Set<String> muted) -> handleMuteStatus(muted);
			case ServerMessage.ChannelLocked(boolean locked) -> handleChannelLocked(locked);
			case ServerMessage.JoinPending(String channel) ->
				// Parked at a locked channel's door. We are NOT in it (and still in whatever channel we were in), so
				// there is nothing to reset — just say so and wait for the owner's decision.
					log("[waiting] \"" + channel + "\" is locked — waiting for its owner to admit you.");
			case ServerMessage.JoinApproved(String channel) -> {
				// Cleared to join: the server never adds us itself, so the final step is ours. Claim it immediately —
				// switchTo already advanced connectTarget when we asked, so sendJoin carries the right target.
				log("[admitted] \"" + channel + "\" — joining…");
				sendJoin();
			}
			case ServerMessage.JoinRequests(List<JoinRequestInfo> requests) -> handleJoinRequests(requests);
			case ServerMessage.FloorGranted _ when mutedMembers.contains(selfId) ->
				// Owner-muted: never open the mic, even on a (stray) grant — the server refuses the floor to a
				// muted member, so this shouldn't arrive, but guard it like the browser's beginTransmit does.
					log("[floor granted] but you are muted by the owner — mic stays closed until unmuted.");
			case ServerMessage.FloorGranted _ -> {
				awaitingClaim = false;   // we claimed — no longer waiting for our turn
				audio.setTransmitting(true);
				log("[floor granted] talking — " + ui.gesture(WalkieUi.Cue.STOP) + " to stop");
			}
			case ServerMessage.FloorStatus(String holderId, List<String> waiting) -> handleFloorStatus(holderId, waiting);
			case ServerMessage.FloorReserved(long claimSeconds) -> handleFloorReserved(claimSeconds);
			case ServerMessage.MuteNewMembersChanged(boolean enabled) -> {
				muteNewMembers = enabled;
				log(enabled
						? "[mute on entry] new members will be muted as they join (this changes nobody already here)"
						: "[mute on entry] off — new members can talk as soon as they join");
			}
			case ServerMessage.FloorQueueChanged(boolean enabled) -> {
				floorQueueEnabled = enabled;
				log(enabled
						? "[floor queue enabled] a busy floor now forms a line — type 't' to join it"
						: "[floor queue disabled] a busy floor now refuses new requests until it frees");
			}
			case ServerMessage.ModeChanged(ChannelMode mode) -> {
				currentMode = mode;
				// Match the browser: switching to full-duplex opens the mic (unless --muted or owner-muted); else it
				// mutes. The mute check keeps a muted member's mic closed (and its "mic is live" hint honest) across
				// a mode change — otherwise setTransmitting would report live while onAudio/sendAudioFrame drop it.
				audio.setTransmitting(shouldAutoOpenMic(mode));
				log("[mode changed] now " + mode);
				ui.hint(modeHint(mode, audio.isTransmitting()));
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
			// The owner moved the whole channel between media planes, without disconnecting anyone. A browser
			// rebuilds its audio pipeline in place; this client can only do relay, so a move to WebRTC is the one
			// case it cannot follow — it leaves instead. A move back to relay needs nothing: our capture and
			// playback loops never stopped, and the server is once again forwarding the frames they produce.
			case ServerMessage.TransportChanged(Transport transport) -> {
				if (transport != Transport.SIGNALING) {
					log("[transport] this channel is back on the WebSocket relay — audio resumes.");
				} else {
					leaveBecauseWebRtc();
				}
			}
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
					// The three join REFUSALS. All three leave us connected but in NO channel (see joinRefused), so
					// they are no longer fatal: we keep the socket and the audio loops, and 'c <channel>' can try
					// another. They used to exit the process, which threw away a healthy connection and left the user
					// nothing to do but restart.
					case PASSPHRASE_MISMATCH -> joinRefused("this channel needs a different --key.");
					case CHANNEL_LOCKED -> joinRefused("this channel is locked by its owner.");
					case CHANNEL_FULL -> joinRefused("this channel is full — it has reached its member limit.");
					// The target channel lives on another instance (channel affinity): an in-place switch can't reach
					// it, so reconnect — a fresh handshake carrying ?channel=<target> is routed to the owning instance,
					// and switchTo already applied the target's mode/key + advanced connectTarget so the
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

	/// Applies the authoritative [ServerMessage.FloorStatus] snapshot: publishes it for the console thread's `t`
	/// control, reconciles our local transmit state, and logs a concise status derived from it.
	///
	/// **Release reconciliation** — if we were live in a push-to-talk channel but the floor is no longer ours, the
	/// server released us (idle preempt, max-hold, or an owner action) and we stop the mic. This is now the SINGLE
	/// source of that "you were released" truth: the old imperative `FloorTaken`/`FloorIdle` triggers are retired, so
	/// the only signal that our floor was taken away is the holder in the next snapshot no longer being us.
	private void handleFloorStatus(String holderId, List<String> waiting) {
		// Publish the snapshot in ONE volatile write, holder + queue together, so the console thread's `t` control
		// can't pair a new holder with the old queue. The queue is an immutable copy, so a reader never sees a
		// half-built list.
		floorSnapshot = new FloorSnapshot(holderId, List.copyOf(waiting));

		String self = selfId;
		boolean released = audio.isTransmitting() && currentMode != ChannelMode.FULL_DUPLEX && !self.equals(holderId);
		if (released) {
			audio.setTransmitting(false);
		}
		FloorState state = floorStateFor(self, holderId, waiting);
		// What is worth SAYING about this snapshot, and whether it repeats what was already said. FloorStatus is
		// re-sent on plenty of occasions that do not move the floor (a member leaving, a mute change, a re-join), so
		// an unchanged situation stays silent — mirrors floorNarration in the browser's talk.js, key for key, with
		// ONE deliberate exception in the OTHER direction: the browser now draws the queue as a list and therefore
		// falls silent on IN_LINE and OFFERED, which would only repeat what its panel already shows. A terminal has
		// no panel, so here these two lines ARE the queue display and both stay. Do not "restore parity" by dropping
		// them; that would leave this client unable to report the queue at all.
		FloorNarration narration = floorNarration(self, holderId, waiting, released, awaitingClaim, floorQueueEnabled);
		if (narration != null && !narration.key().equals(lastFloorNarration)) {
			switch (narration.kind()) {
				// We were the reserved head and let the claim window lapse (or declined): the server dropped us and
				// offered the floor onward (grant-to-claim, miss → dropped). floorNarration reports this only while
				// the queue is still ON — if the owner just disabled it, the FloorQueueChanged that arrives right
				// before this snapshot already explained the drop.
				case TURN_PASSED -> log("[your turn passed] you didn't claim in time — "
						+ ui.gesture(WalkieUi.Cue.JOIN_QUEUE) + " to rejoin the queue");
				case RELEASED -> log("[released] the floor is no longer yours — "
						+ ui.gesture(WalkieUi.Cue.TALK) + " to request it again");
				case IN_LINE -> log("[in line #" + narration.position() + " of " + narration.size()
						+ "] — " + ui.gesture(WalkieUi.Cue.LEAVE_QUEUE) + " to leave the queue");
				case TALKING -> log("[talking] " + name(narration.memberId()));
				case OFFERED -> log("[floor reserved] being offered to " + name(narration.memberId()));
				case FREE -> log("[floor free] — " + ui.gesture(WalkieUi.Cue.TALK) + " to talk");
			}
		}
		// Remembered even when nothing was logged, so LIVE/MY_TURN (which narrate nothing) cannot let the next IDLE
		// snapshot repeat the line that preceded them.
		lastFloorNarration = narration == null ? null : narration.key();
		// Remember whether it is now OUR turn, so a later snapshot that drops us can log "[your turn passed]" above.
		awaitingClaim = state == FloorState.MY_TURN;
	}

	/// What a floor snapshot is worth saying, if anything — the Java mirror of `floorNarration` in the browser's
	/// talk.js, key for key. `null` means say nothing.
	///
	/// The two clients no longer fall silent on exactly the same snapshots, and that is deliberate: the browser
	/// draws the queue as a list, so it suppresses `IN_LINE` and `OFFERED` while that panel is up. A terminal has no
	/// panel, so here those two lines ARE the queue display and both stay. Every other kind and key still matches;
	/// see the note at the call site.
	///
	/// `key` identifies the SITUATION; the caller logs only when it differs from the last one it logged. LIVE and
	/// MY_TURN say nothing at all here: FloorGranted and FloorReserved are the imperative triggers that announce
	/// those, and repeating them on queue churn would talk over the alert.
	static FloorNarration floorNarration(String self,
	                                     String holderId,
	                                     List<String> waiting,
	                                     boolean released,
	                                     boolean awaitingClaim,
	                                     boolean floorQueueEnabled) {
		FloorState state = floorStateFor(self, holderId, waiting);
		if (state == FloorState.LIVE || state == FloorState.MY_TURN) {
			return null;
		}
		if (state == FloorState.IN_LINE) {
			int position = waiting.indexOf(self) + 1;
			return new FloorNarration(FloorNarration.Kind.IN_LINE, "in-line:" + position + "/" + waiting.size(),
					null, position, waiting.size());
		}
		if (awaitingClaim && floorQueueEnabled) {
			return new FloorNarration(FloorNarration.Kind.TURN_PASSED, "turn-passed", null, 0, 0);
		}
		if (released) {
			return new FloorNarration(FloorNarration.Kind.RELEASED, "released", null, 0, 0);
		}
		if (holderId != null) {
			return new FloorNarration(FloorNarration.Kind.TALKING, "talking:" + holderId, holderId, 0, 0);
		}
		if (!waiting.isEmpty()) {
			String head = waiting.getFirst();
			return new FloorNarration(FloorNarration.Kind.OFFERED, "offered:" + head, head, 0, 0);
		}
		return new FloorNarration(FloorNarration.Kind.FREE, "free", null, 0, 0);
	}

	/// One narration decision: which line to print (`kind`), the situation it describes (`key`, compared against the
	/// last one logged), and the parameters the wording needs.
	record FloorNarration(Kind kind, String key, String memberId, int position, int size) {

		enum Kind {IN_LINE, TURN_PASSED, RELEASED, TALKING, OFFERED, FREE}
	}

	/// Handles [ServerMessage.FloorReserved]: it is our turn, reserved for `claimSeconds`. Alerts the user (terminal
	/// BEL + a prominent line) but does NOT open the mic — grant-to-claim requires an explicit `t`. No client-side
	/// countdown is run: the server is authoritative on the window, and if it lapses the next [ServerMessage.FloorStatus]
	/// drops us and [#handleFloorStatus] logs "[your turn passed]".
	private void handleFloorReserved(long claimSeconds) {
		awaitingClaim = true;
		ui.attention();
		log("*** YOUR TURN — " + ui.gesture(WalkieUi.Cue.TALK) + " within " + claimSeconds + "s to talk ***");
	}

	/// This client's observable state as one immutable value — see [ClientSnapshot] for why a view needs one read
	/// rather than a dozen. Every field it reads is `volatile` (or a concurrent collection), so this is safe from any
	/// thread; what it is NOT is atomic across fields, and it deliberately does not pretend to be. Two members
	/// arriving while the snapshot is being taken can land on either side of it — which is the same weak consistency
	/// the console has always had, and self-corrects on the next [WalkieUi#stateChanged].
	ClientSnapshot snapshot() {
		return new ClientSnapshot(
				selfId,
				currentChannel,
				currentMode,
				ownerId,
				channelLocked,
				floorQueueEnabled,
				muteNewMembers,
				audio.isTransmitting(),
				memberNames,
				mutedMembers,
				floorSnapshot,
				joinRequests
		);
	}

	/// Resolves a member's display name from its session id, always suffixed with a short session-id prefix (the
	/// session id is the real identity — display names aren't unique). Every call site passes a current member's
	/// id (membership precedes any floor/owner/mute reference, and delivery is ordered per recipient), so no
	/// unknown-id fallback is needed — mirrors the browser client's memberLabel().
	String name(String id) {
		return memberNames.get(id) + " (#" + shortId(id) + ")";
	}

	private void announceJoin(MemberInfo member) {
		memberNames.put(member.id(), member.displayName());
		if (member.muted()) {
			setMutedLocally(member.id(), true);   // a joiner is not pre-muted today, but honor the flag rather than assume
		}
		log("[+] " + name(member.id()));
	}

	private void announceLeave(String memberId) {
		log("[-] " + name(memberId));
		memberNames.remove(memberId);
		// A mute never outlives the member (mirrors the server's Channel.remove). Also what keeps the MuteStatus diff
		// honest: without it a departed muted id would linger here and the next snapshot would read as "unmuted".
		setMutedLocally(memberId, false);
	}

	/// The authoritative owner-mute snapshot ([ServerMessage.MuteStatus]): every currently-muted id, sent on every
	/// mute change so that muting a whole channel costs ONE message rather than one per member. Server-authoritative:
	/// the server also DROPS a muted member's relay audio, so this reflects enforcement rather than being it.
	///
	/// Carries state, not transitions, so the lines we print are DERIVED by diffing it against the set we held — the
	/// same way [#handleFloorStatus] derives "you lost the floor". If WE just became muted, stops the mic at once and
	/// (in PTT) releases the floor so we don't hold it silently. Runs on the single listener thread, so the
	/// read-then-act on `audio.isTransmitting()` needs no extra guard.
	private void handleMuteStatus(Set<String> muted) {
		Set<String> previous = mutedMembers;
		Set<String> next = Set.copyOf(muted);   // defensive: the deserialized set is ours to keep, but publish an immutable one
		// PUBLISH before acting: toggleTalk's full-duplex re-check relies on the gate engaging before the mic stops,
		// so a `t` racing this handler either sees the old state entirely or is caught by the re-check.
		mutedMembers = next;
		if (next.contains(selfId) && !previous.contains(selfId)) {
			boolean wasTransmitting = audio.isTransmitting();
			audio.setTransmitting(false);   // stop the mic immediately — best-effort locally; the server drops us regardless
			if (wasTransmitting && currentMode != ChannelMode.FULL_DUPLEX) {
				enqueue(new ClientMessage.ReleaseFloor());   // don't keep holding the PTT floor while muted
			}
			log("[muted] the channel owner muted you — you can't talk until unmuted.");
		} else if (previous.contains(selfId) && !next.contains(selfId)) {
			log("[unmuted] the channel owner unmuted you — " + ui.gesture(WalkieUi.Cue.TALK) + " to talk again.");
		}
		logMuteChange(next.stream().filter(id -> !previous.contains(id) && !id.equals(selfId)).toList(), "muted");
		logMuteChange(previous.stream().filter(id -> !next.contains(id) && !id.equals(selfId)).toList(), "unmuted");
	}

	/// Reports members that just became muted/unmuted, naming them only while few. A "mute all" on a busy channel
	/// would otherwise print one line per member on the listener thread — the very per-member fan-out the snapshot
	/// exists to collapse — and bury the roster the user is reading.
	///
	/// Ordered HERE, by display name then id, matching [ConsoleUi]'s `listMembers`: `MuteStatus.muted` is a `Set` precisely
	/// because arranging ids is a display decision, and this is the display.
	private void logMuteChange(List<String> ids, String verb) {
		if (ids.isEmpty()) {
			return;
		}
		List<String> named = ids.stream()
				.sorted(Comparator.<String, String>comparing(id -> memberNames.getOrDefault(id, ""), String.CASE_INSENSITIVE_ORDER)
						.thenComparing(Comparator.naturalOrder()))
				.map(this::name)
				.toList();
		log("[" + verb + "] " + (named.size() <= 2 ? String.join(", ", named) : named.size() + " members") + " (by the owner)");
	}

	/// Adds or drops ONE id in the owner-mute set, republishing it whole. For the roster-driven edges (a joiner
	/// carrying the flag, a member leaving) — the snapshot handler above replaces the set outright instead. Listener
	/// thread only, so the read-modify-publish needs no CAS.
	private void setMutedLocally(String memberId, boolean muted) {
		Set<String> next = new HashSet<>(mutedMembers);
		if (muted ? next.add(memberId) : next.remove(memberId)) {
			mutedMembers = Set.copyOf(next);
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

	/// A join was REFUSED by the server — a passphrase mismatch, or a locked/full channel. Either way we end up
	/// connected but in NO channel: an initial connect joined nothing, and on a switch the server validates the
	/// target by leaving the current channel BEFORE the atomic join, so a refused switch has already removed us
	/// there. Reconcile to that truth (drop every piece of per-channel state) and KEEP RUNNING: the socket, the
	/// capture/playback loops and the console stay alive, so `c <channel> [mode]` can pick another channel. This
	/// used to exit the process, which discarded a healthy session and left restarting as the only option.
	///
	/// The typed `--key` (`crypto`) is deliberately KEPT — it is the passphrase the user supplied, reusable for the
	/// next attempt — while `currentChannelKeyCheck` (the *channel's* announced value) is cleared, since the channel
	/// it described is one we are no longer in.
	private void joinRefused(String reason) {
		SwitchRollback rollback = switchRollback;
		switchRollback = null;
		if (channelConfirmed) {
			// A refused SWITCH. The server departs our current channel only once a join succeeds, so we are still in
			// it with our floor and roster intact — keep all of that, and just undo what switchTo applied ahead of
			// the answer. Restoring the key matters: holding the target's key while in this channel makes the
			// transmit gate mute us (its announced key-check no longer matches the one we hold).
			if (rollback != null) {
				crypto = rollback.crypto();
				currentPassphrase = rollback.passphrase();
				connectTarget = rollback.target();
			}
			log("[refused] " + reason + " You are still in \"" + currentChannel + "\".");
			return;
		}
		// Nothing was joined in the first place (an initial connect), so there is nothing to keep: settle into the
		// connected-but-channel-less state and let the user pick another channel.
		forgetChannelState();
		log("[refused] " + reason + " Use 'c <channel> [mode]' to try another.");
	}

	/// Drops every piece of per-channel state, settling into the connected-but-channel-less state that both ways
	/// out of a channel end in — a refused initial join ([#joinRefused]) and a deliberate departure
	/// ([#leaveBecauseWebRtc]) — so it clears [#currentChannel] too, which is what "channel-less" means to every
	/// reader of a snapshot. The typed `--key` (`crypto`) is deliberately KEPT, since it is the user's own
	/// passphrase and reusable for the next attempt; `currentChannelKeyCheck` is the CHANNEL's announced value and
	/// describes a channel we are no longer in, so it goes.
	private void forgetChannelState() {
		channelConfirmed = false;
		// The CHANNEL itself, which this method claimed to drop and did not. Both callers had already been told they
		// are out of one, and leaving the name behind made every reader disagree with that: a window's header still
		// announced the channel, and its adaptive button read the typed name as "the channel you are already in" — so
		// a mistyped passphrase could only be corrected by disconnecting. Measured before the fix: after a refused
		// join, header "team1 · MULTI_CHANNEL_PTT · no owner" over a log line saying to pick another channel.
		currentChannel = null;
		ownerId = null;
		channelLocked = false;
		muteNewMembers = false;
		currentChannelKeyCheck = null;
		memberNames.clear();
		mutedMembers = Set.of();
		floorSnapshot = FloorSnapshot.IDLE;
		awaitingClaim = false;
		// The waiting list is sent ONLY to the channel's current owner, so it is not ours to keep once we are leaving
		// this channel: a stale entry would have a front end offering to admit somebody into a room it no longer owns.
		joinRequests = List.of();
		audio.setTransmitting(false);
	}

	/// Leaves the current channel because it is on the WebRTC media plane, which this client cannot speak, and
	/// returns whether it actually left (false when we are somehow not in a channel, so the caller carries on).
	///
	/// There is no mature pure-Java WebRTC stack (see the README's known constraints), so unlike the browser this
	/// client cannot rebuild its audio onto the other plane — and staying would be the worst outcome available: a
	/// full roster, a working floor, a mic that appears live, and not one byte of audio in either direction. It
	/// leaves deliberately, with the `leave` frame rather than by dropping the socket, so the channel runs its
	/// normal departure (a `MemberLeft` for everyone, and an owner election if we owned it) instead of waiting on
	/// a timeout — and so the console stays connected and `c <channel>` can pick another.
	private boolean leaveBecauseWebRtc() {
		if (currentChannel == null) {
			return false;
		}
		String left = currentChannel;
		enqueue(new ClientMessage.Leave());
		log("[left] \"" + left + "\" is on the WebRTC transport and the console client speaks only the WebSocket "
				+ "relay — join it from a browser instead. Still connected: use 'c <channel>' for another.");
		// Drop the per-channel state ourselves rather than waiting for anything back: `leave` draws no reply, so
		// this is the only place that can reconcile us to being out of it.
		switchRollback = null;
		forgetChannelState();   // clears currentChannel as well; see its doc
		return true;
	}


	/// Handles [ServerMessage.JoinRequests]: the authoritative list of newcomers waiting at this locked channel's
	/// door, sent only while we own it. A terminal has no badge to glance at, so an ARRIVAL is announced as a log
	/// line — the line is the notification. Departures (a withdrawal, a decision) are not announced: they are
	/// consequences of something already reported, and re-announcing them would be noise.
	private void handleJoinRequests(List<JoinRequestInfo> requests) {
		List<String> known = joinRequests.stream().map(JoinRequestInfo::id).toList();
		joinRequests = List.copyOf(requests);
		requests.stream()
				.filter(request -> !known.contains(request.id()))
				.forEach(request -> log("[knock] " + request.displayName() + " (#" + shortId(request.id())
						+ ") wants to join — 'admit #" + shortId(request.id()) + "' or 'deny #" + shortId(request.id())
						+ "' (" + requests.size() + " waiting)"));
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

	/// The unified `t` control. In full-duplex `t` just toggles the local mic (there is no floor). In a push-to-talk
	/// channel it is STATE-DRIVEN: its meaning (and the message it sends) is derived from the latest
	/// [ServerMessage.FloorStatus] snapshot via [#floorStateFor] / [#floorActionFor] — release when we hold it or are
	/// queued, claim when it's our turn, grab-or-enqueue otherwise. The owner-mute guard and the full-duplex behaviour
	/// are unchanged.
	void toggleTalk() {
		if (currentChannel == null) {
			// Connected but in no channel — a refused join leaves us here (see joinRefused). Refuse locally rather
			// than open the mic and emit frames/floor requests the server would only drop as NOT_IN_CHANNEL.
			log("[no channel] you are not in a channel — use 'c <channel> [mode]' to join one.");
			return;
		}
		if (mutedMembers.contains(selfId)) {
			// Owner-muted: refuse. We already stopped the mic on the mute snapshot, so we're not transmitting here; this
			// just tells a user who tries to talk why they can't (the server would drop us and refuse us the floor).
			log("[muted] you are muted by the channel owner — you can't talk until unmuted.");
			return;
		}
		if (currentMode == ChannelMode.FULL_DUPLEX) {
			// Full-duplex has no floor: `t` is a plain mic mute/unmute toggle. Opening the mic races the listener
			// thread's owner-mute handler (handleMuteStatus PUBLISHES the snapshot, THEN stops the mic): a mute that
			// lands after the guard above but before this write would otherwise be overwritten, leaving the mic live
			// while muted — a privacy leak. So after opening, re-check mutedMembers and back off. Because the handler
			// adds to the set before it stops the mic, either the mute is already visible here (we undo it) or it
			// isn't yet and its later setTransmitting(false) runs after ours and wins — the mic ends OFF either way.
			boolean live = !audio.isTransmitting();
			audio.setTransmitting(live);
			if (live && mutedMembers.contains(selfId)) {
				audio.setTransmitting(false);
				live = false;
			}
			log(live ? "[talking]" : "[stopped]");
			// Tell the view. Every OTHER state change reaches a front end through handleServerMessage's stateChanged,
			// but this one is purely local: full duplex has no floor, so no message goes out and none comes back. A
			// window that missed it kept reading "Mic OFF — click to talk" over an open microphone, and in a quiet
			// duplex channel (where the only traffic is binary audio) nothing would have contradicted it for minutes.
			ui.stateChanged();
			return;
		}
		// Push-to-talk: read the floor snapshot ONCE (the listener thread may replace it under us), derive our
		// state, and send the message that state dictates. One volatile read gives a consistent holder+queue pair.
		String self = selfId;
		FloorSnapshot snap = floorSnapshot;
		String holder = snap.holder();
		List<String> waiting = snap.waiting();
		FloorState state = floorStateFor(self, holder, waiting);
		switch (state) {
			case LIVE -> {
				// We hold the floor: stop the mic at once for immediate feedback, then release it. The following
				// FloorStatus(holderId=null) needs no reconciliation because we've already stopped here.
				audio.setTransmitting(false);
				log("[stopped]");
			}
			case MY_TURN -> log("[claiming the floor...]");   // grant-to-claim: the mic opens on FloorGranted, never here
			case IN_LINE -> log("[leaving the queue...]");
			case IDLE -> {
				if (holder == null && waiting.isEmpty()) {
					log("[requesting floor...]");
				} else if (floorQueueEnabled) {
					log("[joining the queue...]");
				} else {
					// Busy and the queue is off: the server ignores our RequestFloor; the snapshot already shows it busy.
					log("[floor busy] " + (holder != null ? name(holder) + " is talking" : "reserved for " + name(waiting.getFirst()))
							+ " — the queue is off; try again when it's free");
				}
			}
		}
		enqueue(floorActionFor(state));
	}

	/// Hands back a floor whose grant arrived AFTER the hold that asked for it ended.
	///
	/// [#toggleTalk] cannot serve here, and that is the whole reason this exists: on the snapshot a quick tap leaves
	/// behind — floor still free, our request unanswered — it derives `IDLE` and would send a SECOND
	/// [ClientMessage.RequestFloor], re-claiming the floor instead of giving it up. So this releases outright.
	///
	/// The browser answers the same race one layer up, in `grantOpensMic(mode, talkHeld)`: it declines to open the mic
	/// at all and sends a release. This client opens it in the [ServerMessage.FloorGranted] handler before any front
	/// end is consulted, so here the correction has to come after the fact. Either way the rule is the browser's —
	/// a grant nobody is holding must not leave a microphone open.
	void releaseHeldFloor() {
		// Not in full duplex: there is no floor to hand back there, so an open mic is a deliberate switch and this
		// would read to the user as a silent, unexplained mute.
		if (currentMode == ChannelMode.FULL_DUPLEX || !audio.isTransmitting()) {
			return;
		}
		audio.setTransmitting(false);   // stop first, as the LIVE branch of toggleTalk does — feedback before I/O
		log("[stopped] the floor was granted after you let go — handed straight back");
		enqueue(new ClientMessage.ReleaseFloor());
	}

	/// Flips the hi-fi (Opus music vs voice) profile live; [AudioEngine] rebuilds the encoder on its next
	/// transmitted frame, so the change applies without reconnecting.
	void toggleFidelity() {
		boolean hifi = audio.toggleHiFi();
		log("[hi-fi " + (hifi ? "on — music profile" : "off — voice profile") + "] (applies on the next transmitted frame)");
	}

	// --- The INTENT surface: what a front end can ask this client to DO. -------------------------------------------
	//
	// Package-private rather than private, and typed rather than parsed. Each of these takes the value it acts on — a
	// ChannelMode, a boolean, a full session id — never the console's spelling of it, because the console's grammar is
	// the console's business: a window holds a ChannelMode from a dropdown and a session id from a roster selection,
	// and making it render those back to "duplex" and "#a1b2" so this class can parse them again would be a round
	// trip through a format neither side wants.
	//
	// The GUARDS stay here, on the intent, not with the parsing. "Only the owner may do this" and "full-duplex has no
	// floor" are facts about the action, and a checkbox in a window needs telling them exactly as much as a typed
	// command does. What moved out is only which WORD means which value, and what to say about a word that means none.
	//
	// One deliberate behaviour change fell out of that division: a NON-owner typing a mistyped `m bogus` now gets
	// "Usage: m <ptt|global|duplex>" where it used to get "[denied] only the channel owner can change the mode",
	// because the parse layer rejects a word it cannot map without first asking whether the action would have been
	// allowed — which it could only do by duplicating the ownership rule this split exists to keep in one place. The
	// mistyped word is also the more useful of the two things to be told.

	/// Sets the channel's mode for everyone. Owner-only; the server enforces that too, and the echoed
	/// [ServerMessage.ModeChanged] is what actually updates the controls.
	///
	/// [ChannelMode#GLOBAL_PTT] is the exception, and not really a mode change at all: global push-to-talk exists only
	/// in the server-managed "global" room and cannot be set on a regular channel (the server refuses
	/// `ChangeMode(GLOBAL_PTT)` with `INVALID_MODE`), so — exactly like the browser, whose "global" pick performs a
	/// Join rather than a mode change — it SWITCHES you there. That is a room change open to anyone (the same as
	/// `c global global`), which is why it is handled BEFORE the owner gate that guards real mode changes. Keeping
	/// that order is also what makes the browser's Mode selector safe to leave enabled in global: it is the only way
	/// out of a room with no owner to ask.
	void setMode(ChannelMode mode) {
		if (mode == ChannelMode.GLOBAL_PTT) {
			switchTo(WalkieClientLauncher.GLOBAL_CHANNEL, ChannelMode.GLOBAL_PTT, null);
			return;
		}
		if (!selfId.equals(ownerId)) {
			log("[denied] only the channel owner can change the mode");
			return;
		}
		enqueue(new ClientMessage.ChangeMode(mode));
	}

	/// `p [passphrase]` / `p! [passphrase]` — change the channel's end-to-end-encryption passphrase. For the OWNER
	/// this rotates it for everyone (a blank passphrase is refused — encryption cannot be turned off); the new key
	/// is applied only when the
	/// server echoes [ServerMessage.PassphraseChanged], so a rejected request leaves the old key intact. With `p`
	/// (when this is an encrypted→encrypted rotation, so an OLD key exists) the new passphrase is also wrapped under
	/// that old key and relayed so connected members ADOPT it automatically (the server still never sees it); `p!`
	/// opts out of auto-distribution for a revocation-style rotation, leaving members to re-enter the new secret
	/// out-of-band. A non-owner CANNOT rotate the shared key — `p` is accepted only to ADOPT an owner's
	/// already-announced rotation ([#memberRekeyPending]), applied LOCALLY (no server round-trip) and verified
	/// against the channel's announced key-check. To use a different passphrase otherwise, a non-owner switches
	/// channels with `c`. (Mirrors the web client's owner-only passphrase field + "share with members" box.)
	void changePassphrase(String arg, boolean share) {
		String passphrase = arg.strip();
		if (currentMode == ChannelMode.GLOBAL_PTT) {
			log("[passphrase] the global room is the server's unencrypted broadcast channel — encryption isn't available there.");
			return;
		}
		// A blank argument used to mean "make this channel plaintext". It cannot any more — the server refuses a
		// cleared key-check with PASSPHRASE_REQUIRED — so refuse it here, where the reason fits on one line.
		if (passphrase.isBlank()) {
			log("[passphrase] encryption can't be turned off — give the new passphrase to rotate it: p <passphrase>");
			return;
		}
		if (selfId.equals(ownerId)) {
			try {
				FrameCrypto next = deriveCrypto(passphrase, currentMode, currentChannel);
				FrameCrypto old = crypto;   // the key we currently hold — read once off the volatile
				// Auto-distribution: wrap the new passphrase under the OLD key so connected members adopt it
				// automatically, when sharing. Every rotation is encrypted→encrypted now, so an old key always
				// exists; `p!` deliberately withholds the wrap (revocation-style). Server relays the blob blindly;
				// only an old-key holder can unwrap it.
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
	///
	/// Package-private rather than private because a FRONT END has to ask it too: the window's adaptive Apply button
	/// is the only way a member can adopt an announced rotation, and gating that button on ownership alone would
	/// strand exactly the person the log has just told to enter a new passphrase.
	boolean memberRekeyPending() {
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
			// currentChannelKeyCheck is null only in the global room, which is never encrypted and has no passphrase
			// to adopt — deriving there and calling it a re-key would be nonsense, so say what is true and stop.
			if (currentChannelKeyCheck == null) {
				log("[passphrase] the global room is the server's unencrypted broadcast channel — no passphrase to apply.");
				return;
			}
			FrameCrypto candidate = deriveCrypto(passphrase, currentMode, currentChannel);
			switch (rekeyAction(currentChannelKeyCheck, candidate)) {
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

	/// Applies an owner's passphrase rotation echoed by the server. The server relays only the new key-check —
	/// which is always non-null, since a clearing rotation is refused with `PASSPHRASE_REQUIRED`, and one that
	/// arrives null anyway is ignored below — never the passphrase, so we re-derive the key from a passphrase we already
	/// hold and verify it against `keyCheck` via [#rekeyAction]. If we initiated this (we are the owner) that is
	/// the passphrase we just submitted; for a member it is the one currently in use, which won't match until the
	/// user re-enters the new one with `p`. On a mismatch we KEEP the old key (no plaintext fallback). The
	/// volatile `crypto` swap is read once by the capture/listener threads, so the worst a transition does is drop
	/// a few frames on a failed GCM tag.
	private void handlePassphraseChanged(String keyCheck, String wrappedKey) {
		// BEFORE the write, not after it. A null announced key-check is a server telling us to stop encrypting;
		// recording it would poison `currentChannelKeyCheck`, which is the value the transmit gate consults — so
		// returning later (as an earlier version of this guard did) kept the KEY while still handing the gate a
		// null, and `outboundFrame` sent in the clear. Keeping our own value is what actually refuses the
		// downgrade. `outboundFrame`'s `plaintextAllowed` argument is the belt to this braces.
		if (keyCheck == null) {
			log("[passphrase] ignored a change that would have turned encryption off — this channel stays encrypted.");
			return;
		}
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
			FrameCrypto candidate = deriveCrypto(passphrase, currentMode, currentChannel);
			switch (rekeyAction(keyCheck, candidate)) {
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

	/// Re-derives the E2EE key for the new channel (the key salts on the channel name) and sends the Join; the
	/// resulting Joined snapshot resets the roster/mode like the initial join. A switch is all-or-nothing server-side:
	/// if it is refused — wrong passphrase, full, or parked for a locked channel's owner to approve — we KEEP the
	/// channel we are in, and [#joinRefused] puts back the key this optimistically applied.
	/// The passphrase the key in force was derived from, for a front end that has to answer "is what the user typed a
	/// DIFFERENT passphrase?" — which is what the browser reads its own `state.passphrase` for. Without it, "the box
	/// is not empty" gets mistaken for "the user asked for a rotation", and a passphrase seeded from `--key` offers to
	/// re-key a channel to the value it already has.
	///
	/// Deliberately NOT on [ClientSnapshot]: that record is copied and handed to whatever draws it, and this is key
	/// material. It lives in memory only — nothing here writes it anywhere.
	String currentPassphrase() {
		return currentPassphrase;
	}

	/// Switches channel KEEPING the passphrase we already hold — what omitting one means, and a decision that belongs
	/// to the client rather than to whoever asked: a window leaving its passphrase box untouched means the same thing
	/// a typed `c other-room` does. Distinct from passing `null` to the three-argument form, which means "no
	/// passphrase at all" and is what [#setMode] uses for the unencrypted global room.
	void switchTo(String channel, ChannelMode mode) {
		switchTo(channel, mode, currentPassphrase);
	}

	void switchTo(String channel, ChannelMode mode, String passphrase) {
		String effective = mode == ChannelMode.GLOBAL_PTT ? WalkieClientLauncher.GLOBAL_CHANNEL : channel;
		if (effective.equals(currentChannel)) {
			log("[switch] already in \"" + effective + "\" — use 'p <passphrase>' to change the passphrase here, or pick a different channel to switch.");
			return;
		}
		try {
			FrameCrypto next = deriveCrypto(passphrase, mode, channel);
			// Remember what this switch is about to overwrite: the server keeps us in our current channel unless the
			// join succeeds, so a refusal has to restore it (see joinRefused).
			switchRollback = new SwitchRollback(crypto, currentPassphrase, connectTarget);
			crypto = next;                 // volatile — the capture/playback loops pick up the new key
			currentPassphrase = passphrase;
			// Advance the (re)connect target too, optimistically like crypto above: if the server refuses this
			// in-place switch with CHANNEL_ROUTING_MISMATCH (the target lives on another instance under channel
			// affinity), reconnect() rebuilds the socket against exactly this channel/mode and onOpen re-joins it.
			connectTarget = new ConnectTarget(channel, mode);   // one volatile write: channel + mode never tear apart for the reconnect thread
			// Do NOT advance currentChannelKeyCheck here: leave it at the OLD channel's value until the server
			// confirms the switch (the Joined handler baselines it). The server still routes our audio to the OLD
			// channel during the join round-trip, so if we're switching OUT of an encrypted channel to a plaintext
			// one, keeping the old (non-null) key-check makes the transmit gate (outboundFrame) keep dropping
			// frames instead of leaking cleartext into the channel we're leaving.
			String display = memberNames.getOrDefault(selfId, options.display());
			enqueue(new ClientMessage.Join(channel, mode, null, display, next == null ? null : next.keyCheck()));
			log("[switch] joining \"" + channel + "\" (" + mode + ")...");
		} catch (GeneralSecurityException e) {
			log("[switch] key derivation failed: " + e.getMessage());
		}
	}

	/// Asks the server to change our display name. Validated locally for a fast no, but the server validates
	/// authoritatively and the resulting [ServerMessage.MemberRenamed] (broadcast back to us) is what actually
	/// updates the roster — so a rejected name surfaces as an `[error]` line instead.
	void rename(String requestedName) {
		// The console hands over the rest of the line (`split("\\s+", 2)`), so a name with spaces arrives whole; the
		// canonical form is what is compared and sent, matching the server byte for byte.
		String newName = canonicalDisplayName(requestedName);
		if (!DISPLAY_NAME.matcher(newName).matches()) {
			ui.note("Usage: n <new-name>  (1-32 letters, digits or spaces in any language, '_', '.' or '-')");
			return;
		}
		if (newName.equals(memberNames.get(selfId))) {
			ui.note("[name] that is already your display name.");   // a no-op the server would reject anyway
			return;
		}
		enqueue(new ClientMessage.Rename(newName));
	}

	/// `cancel` — stop waiting to be admitted somewhere. Harmless when we are not waiting: the server treats it as a
	/// no-op, and saying so locally is friendlier than a silent nothing.
	void cancelJoinRequest() {
		enqueue(new ClientMessage.WithdrawJoinRequest());
		log("[cancel] withdrawing any request to join a locked channel.");
	}

	/// The short `#id` prefix both clients show beside a name, so two people sharing a display name stay tellable
	/// apart. Shared by the roster and the waiting list.
	static String shortId(String id) {
		return id.substring(0, Math.min(ID_PREFIX_LENGTH, id.length()));
	}

	/// Admits or denies ONE newcomer waiting at this locked channel's door, named by its full session id — the id a
	/// request row already holds. `displayName` is passed in rather than looked up because a waiting newcomer is NOT a
	/// member, so it is not in the roster: its name is only ever known from the request list itself.
	void resolveJoinRequestFor(String requesterId, String displayName, boolean admit) {
		if (!selfId.equals(ownerId)) {
			log("[denied] only the channel owner can " + (admit ? "admit" : "deny") + " newcomers");
			return;
		}
		enqueue(new ClientMessage.ResolveJoinRequest(requesterId, admit));
		log("[" + (admit ? "admit" : "deny") + "] " + (admit ? "admit" : "deny") + "-ing "
				+ displayName + " (#" + shortId(requesterId) + ")...");
	}

	/// Admits or denies everyone waiting, in one message.
	void resolveAllJoinRequests(boolean admit) {
		if (!selfId.equals(ownerId)) {
			log("[denied] only the channel owner can " + (admit ? "admit" : "deny") + " newcomers");
			return;
		}
		enqueue(new ClientMessage.ResolveAllJoinRequests(admit));
		log("[" + (admit ? "admit" : "deny") + "] " + (admit ? "admit" : "deny") + "-ing everyone waiting...");
	}

	/// Mutes or unmutes ONE member, named by its full session id — the id a roster selection already holds, which is
	/// why the `#prefix` matching above is console grammar and stops here. Owner-only; the server enforces the same
	/// rule, so this refusal is courtesy rather than the boundary.
	void setMemberMuted(String memberId, boolean muted) {
		if (!selfId.equals(ownerId)) {
			log("[denied] only the channel owner can " + (muted ? "mute" : "unmute") + " members");
			return;
		}
		enqueue(new ClientMessage.MuteMember(memberId, muted));
		log("[" + (muted ? "mute" : "unmute") + "] " + (muted ? "mute" : "unmute") + "-ing " + name(memberId) + "...");
	}

	/// Mutes or unmutes every member except us, in one message. A one-shot over who is PRESENT — the standing rule for
	/// arrivals is [#setMuteNewMembers].
	void setAllMuted(boolean muted) {
		if (!selfId.equals(ownerId)) {
			log("[denied] only the channel owner can " + (muted ? "mute" : "unmute") + " members");
			return;
		}
		enqueue(new ClientMessage.MuteAll(muted));
		log("[" + (muted ? "mute" : "unmute") + "] " + (muted ? "mute" : "unmute") + "-ing all other members...");
	}

	/// `lock` / `unlock` — owner-only: stop / allow NEW members joining this channel. Gated locally to the owner
	/// (the server enforces it too and never trusts the client); the resulting [ServerMessage.ChannelLocked] is what
	/// actually flips everyone's state. Existing members are never affected.
	void setChannelLock(boolean locked) {
		if (!selfId.equals(ownerId)) {
			log("[denied] only the channel owner can " + (locked ? "lock" : "unlock") + " the channel");
			return;
		}
		enqueue(new ClientMessage.SetLocked(locked));
		log("[" + (locked ? "lock" : "unlock") + "] requesting to " + (locked ? "lock" : "unlock") + " the channel...");
	}

	/// Hands the channel to another member, named by its full session id. Owner-only. The server validates that the
	/// target is a current member (`UNKNOWN_TARGET`), so this does not re-check the roster it was given.
	void transferOwnershipTo(String memberId) {
		if (!selfId.equals(ownerId)) {
			log("[denied] only the channel owner can transfer ownership");
			return;
		}
		enqueue(new ClientMessage.TransferOwnership(memberId));
		log("[transfer] handing ownership to " + name(memberId) + "...");
	}

	/// Turns the owner-toggleable push-to-talk floor queue on or off. Both guards belong here rather than to a caller:
	/// full-duplex has no floor to queue for, and only the owner may toggle it — a checkbox in a window needs telling
	/// the same two things a typed command does.
	void setFloorQueue(boolean enabled) {
		if (currentMode == ChannelMode.FULL_DUPLEX) {
			log("[queue] full-duplex has no floor, so there's no queue to toggle.");
			return;
		}
		if (!selfId.equals(ownerId)) {
			log("[denied] only the channel owner can turn the floor queue on or off");
			return;
		}
		enqueue(new ClientMessage.SetFloorQueue(enabled));
		log("[queue] requesting to turn the floor queue " + (enabled ? "on" : "off") + "...");
	}

	/// Arms or disarms the standing "mute every arrival" rule. Owner-only. Changes nobody already present — that is
	/// the server's rule, not this client's, and the one-shot over present members is [#setAllMuted].
	void setMuteNewMembers(boolean enabled) {
		if (!selfId.equals(ownerId)) {
			log("[denied] only the channel owner can change who is muted on entry");
			return;
		}
		enqueue(new ClientMessage.SetMuteNewMembers(enabled));
		log(enabled
				? "[entry] requesting to mute new members on entry..."
				: "[entry] requesting to stop muting new members on entry...");
	}

	/// The audio socket's address: the server's base with its scheme swapped to `ws`/`wss`, and the channel ROUTING KEY
	/// as its only parameter.
	///
	/// Static and named so a test can assert what is NOT in it. The bearer token travels as a header instead (see
	/// [#connect]) because a URL is the least private part of a request — it reaches access logs, proxy logs and error
	/// reports — and nothing but a test stops a credential drifting back into one.
	static URI audioSocketUri(String server, String routingChannel) {
		return URI.create(server.replaceFirst("^http", "ws") + "/ws/audio"
				+ "?channel=" + URLEncoder.encode(routingChannel, StandardCharsets.UTF_8));
	}

	/// Opens the WebSocket, INTERRUPTIBLY.
	///
	/// `join()` would be shorter and was what this used, but it ignores an interrupt and keeps waiting — so a front end
	/// offering to cancel a connection could free its own controls and still not stop the attempt, and the socket would
	/// then open into a session nobody was waiting for. `get()` throws, and cancelling the future tears the half-open
	/// attempt down with it.
	private WebSocket connect(String token) throws IOException, InterruptedException {
		// Carry the effective channel as the ?channel= routing key so a channel-affinity ingress can pin this
		// socket to the instance that owns the channel (see the server's ChannelHandshakeInterceptor). Harmless
		// single-instance. Global forces the routing key to "global", matching the Join's effective channel. Reads
		// the connect target (not options) so a reconnect routes to the channel we switched to, not the startup one.
		ConnectTarget target = connectTarget;   // read the pair once — channel + mode consistent
		CompletableFuture<WebSocket> handshake = httpClient.newWebSocketBuilder()
				// The token goes in the HEADER, not in the URL. A URL is the least private part of a request: it lands
				// in access logs, proxy logs and error reports, and every hop between here and the origin sees it even
				// under TLS if anything terminates in between. `TokenAuthenticationFilter` reads this header first and
				// keeps its `?token=` parameter only for the BROWSER client, which cannot set headers on a WebSocket
				// handshake at all — a limitation of the browser API, not a choice, and no reason for a Java client
				// that has the option to give up the safer one.
				.header("Authorization", "Bearer " + token)
				.buildAsync(
						audioSocketUri(options.server(), target.mode() == ChannelMode.GLOBAL_PTT
								? WalkieClientLauncher.GLOBAL_CHANNEL
								: target.channel()),
						new ClientListener()
				);
		try {
			return handshake.get();
		} catch (InterruptedException interrupted) {
			handshake.cancel(true);   // do not leave a socket opening for a caller that has gone
			throw interrupted;
		} catch (ExecutionException failure) {
			// `join` wrapped failures in an unchecked CompletionException; `get` uses ExecutionException. Unwrapped so
			// both callers keep seeing the IOException or RuntimeException they were already written to handle — and so
			// a front end's error line names the real cause instead of the wrapper.
			Throwable cause = failure.getCause();
			if (cause instanceof IOException problem) {
				throw problem;
			}
			if (cause instanceof RuntimeException problem) {
				throw problem;
			}
			throw new IOException(cause == null ? failure.toString() : cause.toString(), cause);
		}
	}

	private void enqueue(ClientMessage message) {
		sendQueue.offer(new Outbound.Text(JSON_MAPPER.writeValueAsString(message)));
	}

	private void sendJoin() {
		// (Re)announce us on this socket's target channel. connectTarget (not options) so a reconnect joins the
		// channel we switched to; the current display (not options.display()) so a rename survives it.
		ConnectTarget target = connectTarget;   // read the pair once — channel + mode consistent
		enqueue(new ClientMessage.Join(
				target.channel(),
				target.mode(),
				// null = "whichever endpoint I dialled". This client only ever dials /ws/audio and offers no
				// transport choice, so asking for one would only be a way to disagree with itself.
				null,
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
		ui.note("Goodbye.");
	}

	/// Rebuilds the relay socket against the current connect target and re-joins it. Triggered by
	/// `CHANNEL_ROUTING_MISMATCH`: under channel affinity the target channel lives on another instance, so only a
	/// fresh handshake — carrying `?channel=<target>` — is routed to the owning instance; an in-place switch can't
	/// reach it. [#switchTo] already applied the target's mode/key and advanced [#connectTarget], so
	/// [ClientListener#onOpen]'s [#sendJoin] lands us straight in it.
	///
	/// Runs on its own virtual thread — never the listener callback thread (whose executor [#connect]'s `get()`
	/// blocks on) and never the console thread. A fresh token keeps it robust even if the original has expired
	/// (login takes no input). The [#reconnecting] guard both collapses a burst of mismatches into one reconnect and
	/// tells the old socket's `onClose` this drop is intentional, so it is not treated as a lost connection.
	private void reconnect() {
		if (!reconnecting.compareAndSet(false, true)) {
			return;   // a reconnect is already in flight; ignore piled-up mismatches
		}
		log("[switch] \"" + connectTarget.channel() + "\" is served by another instance — reconnecting to reach it...");
		Thread.ofVirtual().name("ptt-reconnect").start(() -> {
			try {
				WebSocket previous = webSocket;
				if (previous != null) {
					previous.sendClose(WebSocket.NORMAL_CLOSURE, "switching instance");
				}
				webSocket = connect(login());   // onOpen publishes the new socket + re-joins the target via sendJoin()
			} catch (IOException | RuntimeException e) {
				log("[reconnect] could not switch to \"" + connectTarget.channel() + "\": " + e.getMessage());
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
	/// abrupt EOF an immediate `System.exit` leaves — and then hands over to the front end.
	///
	/// Used by a lost connection. The close runs on its OWN virtual thread, NOT the WebSocket listener callback
	/// thread this path fires on (whose executor [#close]'s bounded HttpClient shutdown must drain to flush the
	/// frame). Fires exactly once.
	///
	/// What happens NEXT is deliberately not decided here. This used to call `System.exit(0)`, which is a terminal
	/// application's prerogative and no business of the client's: the console has nothing left to do once its
	/// non-interruptible `System.in` read can no longer be satisfied, so stopping the process IS its answer — but a
	/// window's answer is to show that the session ended and stay open. So the client reports and defers, and
	/// [WalkieUi#sessionEnded] is where each front end says what that means for it.
	private void exitGracefully(String reason) {
		if (!running.getAndSet(false)) {
			return;
		}
		log(reason);
		Thread.ofVirtual().name("ptt-shutdown").start(() -> {
			close();   // sends NORMAL_CLOSURE, then the bounded HttpClient shutdown flushes it before we halt
			ui.sessionEnded();
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
	/// don't have the new passphrase yet, or it mismatched — including an announcement of NO key-check, which is
	/// refused rather than obeyed; see [#rekeyAction]).
	enum RekeyAction {APPLY, KEEP}

	/// This client's push-to-talk floor state, derived from the latest [ServerMessage.FloorStatus] via
	/// [#floorStateFor]. Drives both the status log and the state-driven `t` control ([#floorActionFor]).
	enum FloorState {
		/// We hold the floor and are live (`holderId == self`).
		LIVE,
		/// It is our turn: the floor is free and we are the reserved head (`holderId == null && waiting.get(0) == self`).
		MY_TURN,
		/// We are waiting further back in the queue (in `waiting`, but not the reserved head).
		IN_LINE,
		/// Nobody has offered us the floor: it is free, reserved for another, or held by another.
		IDLE
	}

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
			int sid = 0;
			byte[] body = null;
			if (last && binaryBuffer.size() == 0) {
				// Fast path (the common case): a whole frame in one fragment — read the stream index + body straight
				// from the ByteBuffer, skipping the BAOS accumulate + toByteArray + copyOfRange the slow path needs.
				if (data.remaining() >= 2) {
					sid = data.get() & 0xFF;
					body = new byte[data.remaining()];
					data.get(body);
				}
			} else {
				// Real fragmentation: accumulate, and demultiplex only once the last fragment has arrived.
				byte[] chunk = new byte[data.remaining()];
				data.get(chunk);
				binaryBuffer.writeBytes(chunk);
				if (last) {
					byte[] frame = binaryBuffer.toByteArray();
					binaryBuffer.reset();
					if (frame.length >= 2) {
						sid = frame[0] & 0xFF;
						body = Arrays.copyOfRange(frame, 1, frame.length);
					}
				}
			}
			// Demultiplex by the server-prepended stream index (stripped above): the body handed to the engine is
			// the same [tag][payload] / E2EE envelope a sender produced.
			if (body != null) {
				FrameCrypto key = crypto;   // read the volatile once — a concurrent channel switch may swap it
				if (key == null) {
					if (body.length > 0 && (body[0] & 0xFF) == E2EE_SCHEME) {
						// Encrypted audio arriving while we hold no key (the global room should never carry any, and
						// elsewhere we should always have one): drop it — the engine would treat 0xE2 as an unknown
						// codec tag and silently emit
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
