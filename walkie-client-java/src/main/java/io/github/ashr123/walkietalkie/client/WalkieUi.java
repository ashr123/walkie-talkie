package io.github.ashr123.walkietalkie.client;

/// A [WalkieClient]'s front end: everything it shows a person, plus what to do when the session is over — behind one
/// interface, so the client can drive a terminal or a window without knowing which. [ConsoleUi] is the terminal
/// implementation and the client's default face.
///
/// Deliberately three prose channels — plus a model signal and a lifecycle one — rather than the single
/// `println(String)` the client used to have. A single string sink would have been a smaller change and a dead end: the only thing a graphical client
/// could do with a pre-formatted line is print it, so it would have been a terminal in a window. The split below is
/// the least structure that lets a window be a window — a running log, transient answers, an attention-grabber, and
/// a model to render from.
///
/// The prose here is genuinely prose. What a view must render as STATE — the roster, the mute set, the floor and its
/// queue — does not travel through these methods at all; it is pulled from [WalkieClient#snapshot] after
/// [#stateChanged]. That division is the point: a queue drawn from log text would mean parsing our own output back.
///
/// The client calls these from ANY of its threads — the WebSocket listener, the console reader, the audio callback.
/// An implementation that touches a UI toolkit is therefore responsible for marshalling onto its own event thread;
/// [ConsoleUi] needs nothing, because `System.out` is already synchronized.
interface WalkieUi {

	/// How THIS front end tells the user to act on the floor.
	///
	/// The client narrates the FACTS ("the floor is free"), but the ACT is a property of the front end: a terminal has
	/// a keystroke, a window has a button you hold. Without this the console's own coaching leaked into the window,
	/// which showed "[floor free] — type 't' to talk" beside a button labelled "Hold to talk" and no prompt to type
	/// anything at. The browser has always written its own wording here ("Tap Talk to rejoin the queue"); only the
	/// narration KEYS are the cross-client contract, never the prose.
	///
	/// Distinct from [#hint]: that DROPS terminal-only advice, while this translates it.
	String gesture(Cue cue);

	/// The floor actions a narration line can ask for. One cue per thing a front end has a different control for.
	enum Cue {
		/// Start talking: grab a free floor, claim a reserved turn, or ask again after losing it.
		TALK,
		/// Stop talking while live.
		STOP,
		/// Join the line for a busy floor.
		JOIN_QUEUE,
		/// Give up a place already held in that line.
		LEAVE_QUEUE
	}
	/// A timestamped status line: the running commentary of a session — floor changes, arrivals and departures,
	/// refusals, encryption status. This is the tier the console prefixes with `yyyy-MM-dd HH:mm:ss,SSS`.
	void status(String line);

	/// An untimestamped notice: the connect banner, the help box, and the `Usage: …` reply to a mistyped command.
	/// Separate from [#status] because these ANSWER something the user just did rather than report something that
	/// happened in the channel — a window would put them somewhere transient rather than in a scrolling log.
	void note(String text);

	/// Coaching phrased for a TERMINAL: the command list, and the "type 't' to grab a free floor" advice after a join
	/// or a mode change. Separate from [#note] because it is the one kind of text that a window should DROP rather
	/// than show — a window's advice is its buttons, and a list of single-letter commands in a window is not merely
	/// redundant, it is wrong (there is no prompt to type them at). The client still decides WHEN such advice is due;
	/// each front end decides whether it means anything.
	void hint(String terminalAdvice);

	/// It is this user's turn to talk and they may not be looking. The console writes an ASCII BEL; a window would
	/// badge its icon, post a notification or ask for attention. Called once per reservation, alongside a [#status]
	/// line that says the same thing in words — so a client that cannot grab attention still reports the turn.
	void attention();

	/// The session is over and the client has closed cleanly — the server went away. What that MEANS is the front
	/// end's to decide, which is the whole reason this is on the port: a terminal has nothing left to do, because its
	/// non-interruptible `System.in` read can no longer be satisfied, so it stops the process; a window shows that the
	/// call ended and stays open for the next one. The reason has already been reported through [#status] by the time
	/// this is called, so an implementation that only needs to say something has nothing to add here.
	void sessionEnded();

	/// The observable state moved: re-read [WalkieClient#snapshot].
	///
	/// Carries NO payload on purpose. A view PULLS the whole model, so several of these can be coalesced into one
	/// repaint and there is never a half-applied update on screen — the same argument that makes
	/// [WalkieClient.FloorSnapshot] one value instead of two fields, applied to the whole model. Every inbound
	/// server message ends with exactly one call, whether or not that message changed anything.
	void stateChanged();
}
