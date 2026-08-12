package io.github.ashr123.walkietalkie.client;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.JoinRequestInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/// The terminal front end: the implementation of [WalkieUi] that writes to `System.out`, AND the command loop that
/// reads from `System.in`. The client's original face, and still its default one.
///
/// Both halves live here because they are one front end — the same reason the browser's `app.js` renders and handles
/// clicks in one place. What is NOT here is any decision: every command resolves to a typed intent on
/// [WalkieClient] (`setMode`, `setFloorQueue`, `setMemberMuted`, …), and every guard that decides whether an action
/// is allowed sits on that intent. This class owns only the GRAMMAR — which word means which value, which `#id`
/// prefix names which member, what to say about a word that means neither — plus the three views (`w`, `requests`,
/// `h`) that render the model as text.
///
/// The client is a field rather than a constructor argument because the dependency runs the other way at
/// construction: [WalkieClient] takes a [WalkieUi], so the front end must exist first. [#run] closes the loop.
final class ConsoleUi implements WalkieUi {

	/// The client this front end drives, set once by [#run]. Not final for the construction-order reason above; only
	/// the command loop and the views read it, and both run after `run` has been called.
	private WalkieClient client;

	/// Runs the terminal session: reads commands until the user quits or stdin closes, then returns so the caller can
	/// close the client. The launcher's whole body, in other words — see WalkieClientLauncher.
	void run(WalkieClient client) {
		this.client = client;
		consoleLoop();
	}

	/// Whether the user has asked to quit. The console's OWN flag, not the client's `running`: "the user typed q" is a
	/// front-end fact, and the client learns of it the moment the launcher closes it. It also never needed to be
	/// shared — the loop is parked in a `System.in` read that cannot be interrupted, so a flag another thread flipped
	/// could not have unparked it anyway; only a line arriving lets the loop look at anything.
	private boolean quitRequested;

	/// Whether we own the channel we are in — the same question the browser's front end asks itself as
	/// `ownsChannel()`, and for the same reason.
	///
	/// This is NOT the authority: every intent on [WalkieClient] re-checks ownership, and the server checks it again.
	/// It exists so the grammar layer can refuse EARLY, because the alternative is worse than a duplicated
	/// comparison — without it, a non-owner typing `mute #ab` would have its `#ab` matched against a roster it has no
	/// right to act on and be told "no other member's id starts with..." rather than that it may not mute anyone.
	private boolean ownedByUs() {
		ClientSnapshot view = client.snapshot();
		return view.selfId().equals(view.ownerId());
	}

	/// The status-line timestamp, `yyyy-MM-dd HH:mm:ss,SSS` — local time, since this is a log for the person sitting
	/// at the terminal rather than something to correlate across machines. Comma before the millis, matching the
	/// server's SLF4J pattern so the two logs read alike side by side.
	private static final DateTimeFormatter DATE_TIME_FORMATTER =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");
	/// ASCII BEL (0x07): written to the terminal to audibly/visibly nudge an inattentive user the instant it is their
	/// turn to talk (a console has no other way to grab attention). Mirrors the browser's "your turn" beep.
	private static final char TERMINAL_BELL = '\u0007';

	@Override
	public void status(String line) {
		System.out.println(LocalDateTime.now().format(DATE_TIME_FORMATTER) + " " + line);
	}

	@Override
	public void note(String text) {
		System.out.println(text);
	}

	@Override
	public String gesture(Cue cue) {
		// One keystroke does all four — `t` is state-driven (see WalkieClient#toggleTalk), which is exactly why a
		// terminal needs no separate controls and a window needs four different ones.
		return "type 't'";
	}

	@Override
	public void hint(String terminalAdvice) {
		// A terminal IS where this advice applies, so it prints exactly as it always did — untimestamped, like note().
		System.out.println(terminalAdvice);
	}

	@Override
	public void attention() {
		System.out.print(TERMINAL_BELL);
		// Flushed explicitly: the bell is the whole point of the write and `print` (unlike `println`) does not flush
		// an auto-flushing stream, so without this it can sit in the buffer until the next status line — arriving
		// after the moment it was meant to announce.
		System.out.flush();
	}

	@Override
	public void sessionEnded() {
		// A terminal front end's honest answer. The console reader is parked in a `System.in` read that cannot be
		// interrupted and will never be satisfied again, so there is no state to return to and nothing to show — the
		// process ending IS the report. The reason line was already printed via status().
		System.exit(0);
	}

	@Override
	public void stateChanged() {
		// Nothing to do. The console has no model on screen to repaint: it narrates each change as prose at the
		// moment it happens, which is what status() is for. The signal is for a window, which renders its roster,
		// floor and queue from WalkieClient#snapshot — see SwingUi#refresh, the reason the port declares it.
	}

	private void consoleLoop() {
		// The command help is printed from the first Joined handler, not here: at this point we haven't received
		// our role (selfId/ownerId are still ""/null), so a role-aware help printed now would always show the
		// non-owner set even for a channel creator. Deferring it until Joined makes the very first help correct.
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
			String line;
			while (!quitRequested && (line = reader.readLine()) != null) {
				String[] parts = line.strip().split("\\s+", 2);
				switch (parts[0].toLowerCase(Locale.ROOT)) {
					case "t", "talk" -> client.toggleTalk();
					case "m", "mode" -> changeMode(parts.length > 1 ? parts[1] : "");
					case "c", "channel" -> switchChannel(parts.length > 1 ? parts[1] : "");
					case "p", "passphrase" -> client.changePassphrase(parts.length > 1 ? parts[1] : "", true);
					case "p!" -> client.changePassphrase(parts.length > 1 ? parts[1] : "", false);
					case "o", "owner" -> transferOwnership(parts.length > 1 ? parts[1] : "");
					case "mute" -> muteMember(parts.length > 1 ? parts[1] : "", true);
					case "unmute" -> muteMember(parts.length > 1 ? parts[1] : "", false);
					case "lock" -> client.setChannelLock(true);
					case "unlock" -> client.setChannelLock(false);
					case "queue" -> floorQueueCommand(parts.length > 1 ? parts[1] : "");
					case "entry" -> muteOnEntryCommand(parts.length > 1 ? parts[1] : "");
					case "requests" -> listJoinRequests();
					case "admit" -> resolveJoinRequest(parts.length > 1 ? parts[1] : "", true);
					case "deny" -> resolveJoinRequest(parts.length > 1 ? parts[1] : "", false);
					case "cancel" -> client.cancelJoinRequest();
					case "n", "name" -> client.rename(parts.length > 1 ? parts[1] : "");
					case "f", "fidelity" -> client.toggleFidelity();
					case "w", "who", "members" -> listMembers();
					case "q", "quit", "exit" -> quitRequested = true;
					case "h", "help" -> client.printHelp();
					case "" -> { /* ignore blank lines */ }
					// Point at the single, role-aware source of truth ('h' -> printHelp) rather than repeating the
					// command list here — a third copy would drift and would advertise owner commands to non-owners.
					default ->
							note("Unrecognized command '" + parts[0] + "' — press 'h' for the list of commands.");
				}
			}
		} catch (IOException _) {
			// stdin closed; fall through to shutdown
		}
	}

	/// Prints the current roster on demand (the 'w' command), sorted lexicographically by display name (then by
	/// id), each member shown via [WalkieClient#name] (display name + `#id` prefix) with `(you)` / `(owner)` markers.
	private void listMembers() {
		// ONE snapshot for the whole listing, which is the rule this method used to state for the mute set alone
		// ("one read per decision") applied to every field it renders — the roster, the mutes, the owner, the lock,
		// the entry rule and the waiting count were six separate volatile reads that could straddle a change and
		// blend two moments. It is also what a window will render from, so the console proves the record carries
		// enough before anything depends on it.
		ClientSnapshot view = client.snapshot();
		if (view.memberNames().isEmpty()) {
				status("[members] (none yet — join a channel first)");
			return;
		}
		Set<String> muted = view.mutedMembers();
			status(view.memberNames().entrySet().stream()
				.sorted(Map.Entry.<String, String>comparingByValue(String.CASE_INSENSITIVE_ORDER)
						.thenComparing(Map.Entry.comparingByKey()))   // lexicographic by name, then id
				.map(entry -> {
					String id = entry.getKey();
					String role = id.equals(view.selfId())
							? " (you)"
							: id.equals(view.ownerId())
							  ? " (owner)"
							  : "";
					return client.name(id) + role + (muted.contains(id) ? " [muted]" : "");
				})
				.collect(Collectors.joining(
						System.lineSeparator() + "  - ",
						"[members] " + view.memberNames().size() + " in this channel"
								+ (view.channelLocked() ? " 🔒 locked to new members" : "")
								+ (view.muteNewMembers() ? " 🔇 new members muted on entry" : "")
								// A terminal has no badge to glance at, so the count rides the status line the user
								// already types. Only the owner is sent the list, so it is silently 0 for anyone else.
								+ (view.joinRequests().isEmpty() ? "" : " · " + view.joinRequests().size() + " waiting to join ('requests')")
								+ ":" + System.lineSeparator() + "  - ",
						""
				)));
	}

	/// The other members (never ourself) whose session id starts with `needle` — the shared resolution for the
	/// id-prefix targeting used by `o` (transfer ownership) and `mute`/`unmute`. Ourself is excluded because none
	/// of those actions apply to it (you can't transfer to, or mute, yourself).
	/// `requests` — the newcomers waiting to be admitted to this locked channel, in arrival order. The server sends
	/// this list only to the owner, so for anyone else it is simply empty (rather than a permission error): being
	/// unable to see who is knocking is not a failed command.
	private void listJoinRequests() {
		ClientSnapshot view = client.snapshot();
		List<JoinRequestInfo> waiting = view.joinRequests();
		if (waiting.isEmpty()) {
				status(view.selfId().equals(view.ownerId())
					? "[requests] nobody is waiting to join."
					: "[requests] only the channel owner sees who is waiting to join.");
			return;
		}
			status(waiting.stream()
				.map(request -> request.displayName() + " (#" + WalkieClient.shortId(request.id()) + ")")
				.collect(Collectors.joining(
						System.lineSeparator() + "  - ",
						"[requests] " + waiting.size() + " waiting to join — 'admit <#id>' or 'deny <#id>':"
								+ System.lineSeparator() + "  - ",
						"")));
	}

	/// `m <ptt|global|duplex>` — the console grammar for the mode selector: which word means which [ChannelMode], and
	/// what to say about a word that means none. The decision itself is [WalkieClient#setMode]; a front end with a dropdown holds
	/// a [ChannelMode] already and must never have to spell it "duplex" for us to spell it back.
	private void changeMode(String arg) {
		ChannelMode mode = parseMode(arg, null);
		if (mode == null) {
			note("Usage: m <ptt|global|duplex>");
			return;
		}
		client.setMode(mode);
	}

	private void switchChannel(String args) {
		String[] split = splitChannelArgs(args);
		String channel = WalkieClient.canonicalChannelName(split[0]);   // the salt's form; see [WalkieClient#canonicalChannelName]
		String[] parts = split[1].isEmpty() ? new String[0] : split[1].split("\\s+", 2);
		ChannelMode current = client.snapshot().mode();
		ChannelMode mode = parts.length > 0 ? parseMode(parts[0], current) : current;
		// Validate the name locally before the round-trip (like the `n` command and the browser client). Global
		// forces the channel to "global" server-side, so the name only matters — and is only checked — otherwise.
		if (mode != ChannelMode.GLOBAL_PTT && !WalkieClient.CHANNEL_NAME.matcher(channel).matches()) {
			note("Usage: c <channel> [ptt|global|duplex] [passphrase]  (channel = 1-64 letters, "
					+ "digits or spaces in any language, plus _ or -; quote a name with spaces: c \"my room\" ptt secret)");
			return;
		}
		// Every channel but the global room is end-to-end encrypted, so a switch has to bring a passphrase — either
		// given here or carried over from the channel we are in. Without one the server refuses the join with
		// PASSPHRASE_REQUIRED, so say so here, where it can name the argument to add. Reachable in practice by
		// switching out of the global room (whose passphrase is empty) into a named one.
		String passphrase = parts.length > 1 ? parts[1] : null;   // null = keep the passphrase we hold (see switchTo)
		if (mode != ChannelMode.GLOBAL_PTT && (passphrase == null || passphrase.isBlank())) {
			note("Usage: c <channel> [ptt|global|duplex] <passphrase>  — '" + channel + "' needs an "
					+ "encryption passphrase (every channel except the global room is encrypted).");
			return;
		}
		if (passphrase == null) {
			client.switchTo(channel, mode);
		} else {
			client.switchTo(channel, mode, passphrase);
		}
	}

	/// `mute <#id|all>` / `unmute <#id|all>` — owner-only moderation. `all` mutes (or unmutes) every OTHER member at
	/// once; otherwise the target is identified by the start of its session id (the `#`-prefix shown in 'w', a
	/// leading `#` optional). Gated locally to the owner (the server enforces it too, and never trusts the client);
	/// the resulting [io.github.ashr123.walkietalkie.shared.protocol.ServerMessage.MuteStatus] broadcast is what actually updates the roster and stops a muted
	/// member's mic. Applies immediately — there is no staged apply for moderation.
	private void muteMember(String arg, boolean muted) {
		String verb = muted ? "mute" : "unmute";
		if (!ownedByUs()) {
				status("[denied] only the channel owner can " + verb + " members");
			return;
		}
		String prefix = arg.strip();
		if (prefix.equalsIgnoreCase("all")) {
			client.setAllMuted(muted);
			return;
		}
		if (prefix.startsWith("#")) {
			prefix = prefix.substring(1);
		}
		if (prefix.isBlank()) {
			note("Usage: " + verb + " <#id|all>  (the #id shown next to a member in 'w', or 'all')");
			return;
		}
		List<String> matches = otherMembersMatching(prefix);
		switch (matches.size()) {
			case 0 ->
						status("[" + verb + "] no other member's id starts with \"" + prefix + "\" — use 'w' to list members.");
			case 1 -> client.setMemberMuted(matches.getFirst(), muted);
			default ->
						status("[" + verb + "] \"" + prefix + "\" matches " + matches.size() + " members — use more of the id.");
		}
	}

	/// `o <id-prefix>` — hand channel ownership to another member, identified by the start of its session id (the
	/// `#`-prefix shown next to each member in the roster; a leading `#` is optional). Gated locally to the owner
	/// (the server enforces it too); the resulting [io.github.ashr123.walkietalkie.shared.protocol.ServerMessage.OwnerChanged] is what actually moves the
	/// owner-only controls.
	private void transferOwnership(String arg) {
		if (!ownedByUs()) {
				status("[denied] only the channel owner can transfer ownership");
			return;
		}
		String prefix = arg.strip();
		if (prefix.startsWith("#")) {
			prefix = prefix.substring(1);
		}
		if (prefix.isBlank()) {
			note("Usage: o <id-prefix>  (the #id shown next to a member in 'w')");
			return;
		}
		List<String> matches = otherMembersMatching(prefix);
		switch (matches.size()) {
			case 0 -> status("[transfer] no other member's id starts with \"" + prefix + "\" — use 'w' to list members.");
			case 1 -> client.transferOwnershipTo(matches.getFirst());
			default ->
						status("[transfer] \"" + prefix + "\" matches " + matches.size() + " members — use more of the id.");
		}
	}

	/// `admit <#id|all>` / `deny <#id|all>` — the owner's decision on a waiting newcomer, resolved from the `#id`
	/// prefix shown by `requests` exactly the way `mute` resolves a member's.
	///
	/// Admitting does not add the member here: the server records a one-shot approval and the newcomer's own client
	/// completes the join, so nothing happens on this side until that lands as a MemberJoined.
	private void resolveJoinRequest(String arg, boolean admit) {
		String verb = admit ? "admit" : "deny";
		if (!ownedByUs()) {
				status("[denied] only the channel owner can " + verb + " newcomers");
			return;
		}
		String prefix = arg.strip();
		if (prefix.equalsIgnoreCase("all")) {
			client.resolveAllJoinRequests(admit);
			return;
		}
		if (prefix.startsWith("#")) {
			prefix = prefix.substring(1);
		}
		if (prefix.isBlank()) {
			note("Usage: " + verb + " <#id|all>  (the #id shown by 'requests', or 'all')");
			return;
		}
		String needle = prefix;
		List<JoinRequestInfo> matches = client.snapshot().joinRequests().stream()
				.filter(request -> request.id().startsWith(needle))
				.toList();
		switch (matches.size()) {
			case 0 -> status("[" + verb + "] nobody waiting has an id starting with \"" + needle
					+ "\" — use 'requests' to list them.");
			case 1 -> {
				JoinRequestInfo target = matches.getFirst();
				client.resolveJoinRequestFor(target.id(), target.displayName(), admit);
			}
			default -> status("[" + verb + "] \"" + needle + "\" matches " + matches.size()
					+ " waiting newcomers — use more of the id.");
		}
	}

	/// Console grammar for `queue`; the decision is [WalkieClient#setFloorQueue(boolean)].
	private void floorQueueCommand(String arg) {
		Boolean enabled = parseOnOff(arg);
		if (enabled == null) {
			note("Usage: queue <on|off>");
			return;
		}
		client.setFloorQueue(enabled);
	}

	/// `entry <on|off>` — owner-only: mute every member that JOINS from now on. The standing counterpart to
	/// `mute all`, which is a one-shot over the members present, so an owner quieting a room and keeping it quiet
	/// uses both; this one deliberately changes nobody who is already here. Gated locally to the owner (the server
	/// enforces it too, and never trusts the client). No mode restriction: full-duplex has no floor, but that is
	/// where mute matters most, since every mic is open.
	/// Console grammar for `entry`; the decision is [WalkieClient#setMuteNewMembers(boolean)].
	private void muteOnEntryCommand(String arg) {
		Boolean enabled = parseOnOff(arg);
		if (enabled == null) {
			note("Usage: entry <on|off>");
			return;
		}
		client.setMuteNewMembers(enabled);
	}

	private List<String> otherMembersMatching(String needle) {
		ClientSnapshot view = client.snapshot();
		return view.memberNames().keySet().stream()
				.filter(id -> !id.equals(view.selfId()) && id.startsWith(needle))
				.toList();
	}

	private static ChannelMode parseMode(String arg, ChannelMode fallback) {
		return switch (arg.toLowerCase(Locale.ROOT)) {
			case "ptt", "multi" -> ChannelMode.MULTI_CHANNEL_PTT;
			case "global" -> ChannelMode.GLOBAL_PTT;
			case "duplex", "full" -> ChannelMode.FULL_DUPLEX;
			default -> fallback;
		};
	}

	/// `on`/`off` as a nullable Boolean — null meaning "that word is neither", which is what lets each caller print
	/// its OWN usage line naming its own command. Shared by `queue` and `entry`, whose grammars are identical.
	private static Boolean parseOnOff(String arg) {
		return switch (arg.strip().toLowerCase(Locale.ROOT)) {
			case "on" -> Boolean.TRUE;
			case "off" -> Boolean.FALSE;
			default -> null;
		};
	}

	/// Switches to a different channel WITHOUT dropping the session: the server treats a fresh Join as
	/// "leave the old channel, join the new one" on the same socket, so the session id (and the audio loops)
	/// survive. Mode and passphrase are optional and default to the current ones. Usage: `c <channel> [mode] [key]`.
	/// Splits `c` command arguments into `{channel, rest}`, honouring double quotes around the channel name.
	///
	/// Needed because channel names may now contain spaces, so `split` on whitespace could no longer tell where the
	/// name ends. Only the CHANNEL is quotable, and the rest is deliberately left as ONE string for the caller to
	/// split again: the trailing passphrase may itself contain spaces (`c room ptt correct horse battery staple`
	/// worked before this change and has to keep working), so it must stay a remainder rather than become tokens.
	///
	/// An unterminated quote takes the rest of the line as the name — forgiving on purpose, since the usage line the
	/// caller then prints is about the name the user actually typed rather than a complaint about quoting.
	static String[] splitChannelArgs(String args) {
		String trimmed = args.strip();
		if (trimmed.startsWith("\"")) {
			int close = trimmed.indexOf('\"', 1);
			return close < 0
					? new String[]{trimmed.substring(1), ""}
					: new String[]{trimmed.substring(1, close), trimmed.substring(close + 1).strip()};
		}
		String[] head = trimmed.split("\\s+", 2);
		return new String[]{head[0], head.length > 1 ? head[1] : ""};
	}
}
