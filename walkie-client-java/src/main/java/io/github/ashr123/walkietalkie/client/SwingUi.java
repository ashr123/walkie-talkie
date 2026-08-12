package io.github.ashr123.walkietalkie.client;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.JoinRequestInfo;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.JTextComponent;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Taskbar;
import java.awt.Toolkit;
import java.awt.desktop.QuitStrategy;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;


/// A window: the second implementation of [WalkieUi], and the whole reason the port exists.
///
/// It adds NO floor logic. Every action is one of [WalkieClient]'s typed intents, and the only question the window
/// answers for itself is about the GESTURE — hold or tap — which is a property of an input device, not of the floor.
/// The floor state behind that question comes from [WalkieClient#floorStateFor], the same rule the console and the
/// browser use. A fourth copy of "who may talk" is what this class must never become.
///
/// **It owns the client**, unlike [ConsoleUi]: a window can be open while disconnected, so it constructs the client on
/// Connect and closes it on Disconnect. That is why the launcher hands it options rather than a client.
///
/// **Threading.** The client calls the port from any of its threads — the WebSocket listener, the audio callback — and
/// Swing may only be touched on the Event Dispatch Thread, so every port method marshals with
/// [SwingUtilities#invokeLater] and does nothing else. Conversely, connecting BLOCKS (a login round trip, opening the
/// audio device, a WebSocket handshake), so it runs on its own thread: doing it on the EDT would freeze the window for
/// as long as it took, which on a bad network is the whole point at which a user wants to see something.
final class SwingUi implements WalkieUi {

	/// Wall-clock only: a window shows one session. The console keeps the full date because its output is commonly
	/// piped to a file.
	private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
	/// How many lines the log keeps — a `JTextArea` holds every character in memory, unlike a terminal's scrollback.
	private static final int LOG_LINES_KEPT = 2_000;
	private static final Dimension WINDOW_SIZE = new Dimension(1_000, 620);
	/// Wide enough for the longest label ("New passphrase:") plus a usable field, narrow enough to leave the log the
	/// rest of the window.
	private static final int CONTROL_COLUMN_WIDTH = 330;
	private static final int MEMBER_COLUMN_WIDTH = 260;
	private static final int TALK_HEIGHT = 68;
	/// The browser client's own icon, copied into this jar by the build (see build.gradle.kts) rather than duplicated
	/// in this module, so the desktop app and the web page cannot end up wearing different faces.
	private static final String ICON_RESOURCE = "/walkie-icon.png";
	/// How far from the tile colour a pixel may be and still count as tile, as a distance in RGB space.
	///
	/// Twelve, because the nearest significant non-tile colour in the icon is a MEASURED 25.6 away — `#0e151c`, the
	/// glyph's own speaker grille, 856 pixels of it. Arithmetic on the source SVG suggested ~37 and would have been
	/// wrong. Staying at less than half the real margin means the fill could not mistake the grille for the tile even
	/// if it reached it, which [#withoutTile]'s connectivity already prevents.
	private static final int TILE_TOLERANCE = 12;
	/// How long [#awaitClose] waits for a connect that is still in flight. Bounded for the same reason
	/// [WalkieClient#close] bounds its own HTTP shutdown: a login against a vanished server must not hold up the exit.
	private static final Duration CONNECT_HANDOVER_GRACE = Duration.ofSeconds(2);

	/// Named as the browser client names itself, so a window list reads like the app rather than like a generic Java
	/// frame. The 📻 comes from the page's own heading — its `<title>`, and so its browser tab, carries no emoji.
	private final JFrame frame = new JFrame("📻 Walkie-Talkie");
	private final JLabel header = new JLabel();
	private final JTextArea log = new JTextArea();
	private final JButton talk = new JButton();
	private final JButton raiseHand = new JButton("Raise hand ✋");

	private final DefaultListModel<String> roster = new DefaultListModel<>();
	private final List<String> rosterIds = new ArrayList<>();
	private final JList<String> members = new JList<>(roster);
	private final JButton muteMember = new JButton("Mute");
	private final JButton makeOwner = new JButton("Make owner");
	private final JButton muteEveryone = new JButton("Mute everyone");

	private final DefaultListModel<String> requests = new DefaultListModel<>();
	private final List<JoinRequestInfo> requestRows = new ArrayList<>();
	private final JList<String> requestList = new JList<>(requests);
	private final JPanel requestPanel = new JPanel(new BorderLayout());
	/// Per-row and bulk admission, as two SEPARATE pairs — the distinction this window used to lose. An empty
	/// selection once fell through to "resolve everyone waiting", and since [#refresh] empties that selection on every
	/// inbound message, an owner aiming at one newcomer could admit the whole queue. Admitting is irrevocable (there
	/// is no kick), so the bulk action gets its own button and has to be asked for, as the console makes you type the
	/// literal word `all` and the browser gives it its own control.
	private final JButton admitRequest = new JButton("Admit");
	private final JButton denyRequest = new JButton("Deny");
	private final JButton admitAll = new JButton("Admit all");
	private final JButton denyAll = new JButton("Deny all");

	private final JTextField serverField = new JTextField(18);
	private final JTextField displayName = new JTextField(14);
	private final JTextField channelField = new JTextField(14);
	private final JComboBox<ChannelMode> modeBox = new JComboBox<>(ChannelMode.values());
	/// A [JPasswordField], not a text field: the passphrase is a credential, and it is never written anywhere —
	/// there is deliberately no "remember this" convenience, since that would mean a plaintext secret on disk.
	private final JPasswordField passphrase = new JPasswordField(14);
	private final JCheckBox hiFi = new JCheckBox("High fidelity (music profile)");
	/// ONE button for both directions, because they are the same question — am I in a session? — and the answer is
	/// never ambiguous. Deliberately NOT merged with [#apply] as well: a single Connect/Switch/Disconnect control
	/// would make Disconnect unreachable the moment a field was edited, since the button would have become Switch and
	/// the only way back would be to undo the edit. The browser separates them for the same reason.
	private final JButton connection = new JButton("Connect");
	private final JButton rename = new JButton("Rename");
	/// One adaptive button, as the browser has: it SWITCHES when the channel name differs and APPLIES an owner's
	/// mode/passphrase change when it does not — and is disabled when neither would do anything.
	private final JButton apply = new JButton("Apply");

	/// The owner-only actions on the roster selection, held in a panel so they can be HIDDEN rather than merely
	/// disabled — the same treatment the owner checkboxes get. A row of permanently greyed-out buttons is an
	/// invitation to wonder what you did wrong; absent controls say "not yours" without asking anything of the reader.
	private final JPanel memberActions = new JPanel(new BorderLayout());
	private final JPanel ownerPanel = new JPanel();
	private final JCheckBox locked = new JCheckBox("Locked — a newcomer must be admitted");
	private final JCheckBox queueOn = new JCheckBox("Raise-hand queue for a busy floor");
	private final JCheckBox muteArrivals = new JCheckBox("Mute members as they join");

	private final CountDownLatch closed = new CountDownLatch(1);

	/// The client, or null while disconnected. Written and read on the EDT only (the connect thread hands it back
	/// through `invokeLater`) — so `volatile` would be noise, not safety. Teardown deliberately does NOT read it; see
	/// [#session].
	private WalkieClient client;
	/// The live session as the CLOSING thread sees it, published by the connect thread BEFORE it hands the client to
	/// the EDT.
	///
	/// [#client] cannot serve that purpose, and the gap was real: close the window while a connect is in flight and
	/// [#awaitClose] read `client` once, found the null it was before the attempt started, and returned — leaving a
	/// fully live session (joined channel, capture device open, in full duplex already transmitting) that nothing ever
	/// closed and that never sent its goodbye. Publishing here, one step earlier than the EDT hand-off, is what makes
	/// a session visible to teardown even when the EDT never gets to run.
	private final AtomicReference<WalkieClient> session = new AtomicReference<>();
	/// The connect attempt in flight, or null — held so [#awaitClose] can WAIT for one instead of racing it.
	private volatile Thread connectAttempt;
	/// A Disconnect's close in flight, held for the same reason: it runs off the EDT (the close blocks), and the
	/// launcher's `System.exit` could otherwise halt the JVM before that thread flushed its NORMAL_CLOSURE.
	private volatile Thread closingSession;
	/// The options Connect will use, seeded from the command line and then from the form.
	private ClientOptions pending;
	/// True while [#refresh] writes the checkboxes FROM the snapshot, so their listeners cannot read that as a click
	/// and echo the server's own state back at it. The browser avoids the same loop by rendering flags from server
	/// state only.
	///
	/// Belt rather than braces, stated honestly: `AbstractButton.setSelected` fires ItemListener and ChangeListener,
	/// NOT the ActionListener these checkboxes use, so a programmatic write does not currently reach them. The guard
	/// costs a boolean and survives someone reaching for `doClick` or an item listener later.
	private boolean settingFromModel;
	/// Whether the Talk control is being held. Read and written on the EDT only.
	private boolean held;
	/// The last press state seen from the button's model, so the gesture listener acts on EDGES.
	///
	/// Reading the LEVEL instead was a real defect, and one this window's own focus fix triggered. A `ButtonModel`
	/// fires `stateChanged` for armed and rollover changes too, so any of them re-ran the press branch while the model
	/// was still pressed. For a HOLD that was invisible (`held` was already true), but a TAP control leaves `held`
	/// false by definition — so in full duplex a second event ran the tap again and FLIPPED THE MIC. Measured against a
	/// real button model: holding Space with the mic off and then deactivating the window ended with the mic ON.
	private boolean pressedBefore;
	/// What [#refresh] last wrote into the channel, mode and name fields FROM a snapshot — so it writes again only when
	/// the server actually moved them.
	///
	/// The focus check alone was not enough, and the gap cost the user their typing: name a channel to switch to, then
	/// click into Passphrase to enter its key, and the channel field is no longer the focus owner — so the next inbound
	/// message (anyone talking, joining, leaving) put the old channel name back. Worse than losing the text, it changed
	/// what the adaptive button DID, from a switch into a passphrase rotation, with the label the only clue.
	private String renderedChannel;
	private ChannelMode renderedMode;
	private String renderedName;
	/// True while a connection attempt is in flight, so the one button can offer to CANCEL it instead of going dead and
	/// silent for however long a login round trip and a WebSocket handshake take.
	private boolean connecting;
	/// Set when the user cancels an attempt, so a session that is built anyway — in the gap before the flag is read —
	/// is closed by the connect thread instead of being handed to a window that has moved on.
	private volatile boolean connectCancelled;

	/// Shows the window, pre-filled from the command line, and connects straight away only if those options are
	/// already complete. Returns at once; the caller waits on [#awaitClose].
	void start(ClientOptions options, boolean connectNow) {
		this.pending = options;
		SwingUtilities.invokeLater(() -> {
			build();
			if (connectNow) {
				connect();
			}
		});
	}

	/// Blocks until the window closes, then closes any live client. The window owns the session, so this is where it
	/// ends — the launcher cannot use a try-with-resources for something the user creates by clicking Connect.
	void awaitClose() {
		try {
			closed.await();
		} catch (InterruptedException _) {
			Thread.currentThread().interrupt();
		}
		// A connect can still be BUILDING a session at this point: it publishes into `session` before the EDT hand-off,
		// so waiting for the thread is what makes that publication visible here. Bounded, because the thing being
		// waited for is a login round trip against a server that may be gone.
		for (Thread pending : new Thread[]{connectAttempt, closingSession}) {
			if (pending == null) {
				continue;
			}
			try {
				pending.join(CONNECT_HANDOVER_GRACE);
			} catch (InterruptedException _) {
				Thread.currentThread().interrupt();
			}
		}
		WalkieClient live = session.getAndSet(null);
		if (live != null) {
			// Idempotent: [WalkieClient#close] compare-and-sets, so a Disconnect that raced this is harmless.
			live.close();
		}
	}

	// --- layout -----------------------------------------------------------------------------------------------------

	/// Stacks rows at their NATURAL height, top-aligned. Not `BoxLayout`, which is what made the first version of this
	/// window look wrong: a vertical box hands each child its MAXIMUM size, and a `JPanel`'s maximum is unbounded, so
	/// three rows in a tall panel spread out with big gaps between them. `GridBagLayout` with `weighty = 0` on every
	/// row and a single weighted filler at the end gives each row its preferred height and lets the filler absorb all
	/// the slack.
	private static JPanel stack(Component... rows) {
		JPanel panel = new JPanel(new GridBagLayout());
		GridBagConstraints layout = new GridBagConstraints();
		layout.gridx = 0;
		layout.weightx = 1;
		layout.fill = GridBagConstraints.HORIZONTAL;
		layout.anchor = GridBagConstraints.NORTHWEST;
		for (Component row : rows) {
			panel.add(row, layout);
		}
		layout.weighty = 1;
		panel.add(Box.createGlue(), layout);
		return panel;
	}

	/// A left-aligned row of controls: `label: widget widget`.
	private static JPanel row(String label, Component... widgets) {
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		if (!label.isEmpty()) {
			panel.add(new JLabel(label));
		}
		for (Component widget : widgets) {
			panel.add(widget);
		}
		return panel;
	}

	private static JPanel titled(String title, JPanel content) {
		content.setBorder(BorderFactory.createTitledBorder(title));
		return content;
	}

	private void build() {
		applyAppIcon();
		log.setEditable(false);
		log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		header.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

		talk.setFont(talk.getFont().deriveFont(Font.BOLD, 16f));
		talk.setPreferredSize(new Dimension(0, TALK_HEIGHT));
		wireTalkGesture();
		raiseHand.addActionListener(_ -> withClient(WalkieClient::toggleTalk));

		JPanel talkRow = new JPanel(new BorderLayout(0, 6));
		talkRow.setBorder(BorderFactory.createEmptyBorder(6, 10, 10, 10));
		talkRow.add(talk, BorderLayout.CENTER);
		talkRow.add(raiseHand, BorderLayout.SOUTH);

		frame.setLayout(new BorderLayout());
		frame.add(header, BorderLayout.NORTH);
		frame.add(buildMembersColumn(), BorderLayout.WEST);
		JScrollPane logPane = new JScrollPane(log);
		logPane.setBorder(BorderFactory.createTitledBorder("Log"));
		frame.add(logPane, BorderLayout.CENTER);
		frame.add(buildControlColumn(), BorderLayout.EAST);
		frame.add(talkRow, BorderLayout.SOUTH);

		// Route the platform QUIT gesture through the same close path as the red button. macOS defaults to
		// QuitStrategy.NORMAL_EXIT, which calls System.exit straight from the app-event handler — so Cmd-Q never
		// produced a windowClosed, the latch never counted down, and NO session ever sent its goodbye. On the very
		// platform whose Dock and Cmd-Tab behaviour the rest of this class is written around.
		if (Desktop.isDesktopSupported()) {
			Desktop desktop = Desktop.getDesktop();
			if (desktop.isSupported(Desktop.Action.APP_QUIT_STRATEGY)) {
				desktop.setQuitStrategy(QuitStrategy.CLOSE_ALL_WINDOWS);
			}
		}
		// DISPOSE, not EXIT: closing must run our own close (a clean WebSocket goodbye), not halt the JVM mid-frame.
		frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(WindowEvent event) {
				// Off the global focus manager before anything else: see [#spaceDrivesFloor].
				KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(spaceDrivesFloor);
				closed.countDown();
			}

			// A hold can end without this window ever seeing the up-edge: Cmd-Tab, or a click into another application
			// or on the Dock while Space is down, delivers the KEY_RELEASED to whatever took focus, so the
			// WHEN_IN_FOCUSED_WINDOW binding cannot fire — and the mic stayed open until the server's max-hold. The
			// browser answers the same interruption with a `blur` handler.
			//
			// Driving the MODEL, as the key binding does, rather than calling releaseTalk: it keeps the button from
			// staying visibly depressed, and it cannot turn an interruption into a queue toggle or a full-duplex mute,
			// because releaseTalk returns at once unless a HOLD was in progress.
			@Override
			public void windowDeactivated(WindowEvent event) {
				talk.getModel().setArmed(false);
				talk.getModel().setPressed(false);
			}
		});
		seedForm();
		frame.setSize(WINDOW_SIZE);
		frame.setLocationRelativeTo(null);
		frame.setVisible(true);
		refresh();
	}

	/// Fills the form from the command line, so `--gui` with the usual options behaves exactly as the console does:
	/// the window opens already holding them and connects.
	///
	/// Load-bearing, not cosmetic. [#connect] builds its options from the FORM — that is what lets the user change
	/// them and reconnect — so without this seeding a fully-specified command line produced an EMPTY form and a
	/// connection attempt against no server at all. Measured: with `--server`, `--display`, `--channel` and `--key`
	/// all given, the server recorded no join, because the window was dialling "".
	private void seedForm() {
		if (pending == null) {
			return;
		}
		serverField.setText(pending.server() == null ? "" : pending.server());
		displayName.setText(pending.display() == null ? "" : pending.display());
		channelField.setText(pending.channel() == null ? "" : pending.channel());
		if (pending.mode() != null) {
			modeBox.setSelectedItem(pending.mode());
		}
		hiFi.setSelected(pending.highFidelity());
		// The passphrase comes from --key or WALKIE_KEY, which are already in this process. It goes no further than
		// this field: nothing here writes it to disk, and there is deliberately no "remember it" option.
		passphrase.setText(pending.key() == null ? "" : pending.key());
	}

	/// The icon with its background tile made transparent, so the dock shows the walkie-talkie and not a dark square.
	///
	/// Derived rather than shipped as a second file, because the source is REQUIRED to be opaque: `apple-touch-icon.png`
	/// is what iOS uses, and Apple both forbids transparency there and applies its own rounded mask — which is why the
	/// corners of that file are flat tile colour rather than the SVG's `rx="7"`. A transparent variant is therefore a
	/// desktop-only need, and computing it here keeps ONE icon in the repository instead of two that can drift.
	///
	/// A flood fill from the border, NOT a colour key. That distinction is the whole correctness argument: the glyph's
	/// speaker grille is a measured 25.6 away from the tile in RGB space (see [#TILE_TOLERANCE]), so any key loose
	/// enough to catch the tile's antialiased frontier would also eat the grille — but the grille is enclosed by the
	/// orange body and unreachable from the edge, so connectivity protects it no matter what the tolerance is.
	///
	/// Antialiased pixels along the glyph's outline stay opaque, leaving a hair of dark rim. At dock size that is
	/// sub-pixel after downscaling, and it slightly helps the orange read against a light wallpaper.
	static BufferedImage withoutTile(BufferedImage source) {
		int width = source.getWidth();
		int height = source.getHeight();
		BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);
		// The corner IS the tile in this file, and sampling it rather than hard-coding `#1b232d` means a redesigned
		// icon needs no change here.
		int tile = pixels[0];
		boolean[] visited = new boolean[pixels.length];
		Deque<Integer> frontier = new ArrayDeque<>();
		for (int x = 0; x < width; x++) {
			frontier.add(x);                              // top edge
			frontier.add((height - 1) * width + x);       // bottom edge
		}
		for (int y = 0; y < height; y++) {
			frontier.add(y * width);                      // left edge
			frontier.add(y * width + width - 1);          // right edge
		}
		while (!frontier.isEmpty()) {
			int index = frontier.remove();
			if (visited[index]) {
				continue;
			}
			visited[index] = true;
			if (!nearTile(pixels[index], tile)) {
				continue;   // the glyph's outline: stop here, and everything behind it stays
			}
			pixels[index] = pixels[index] & 0x00ffffff;   // keep the colour, drop the alpha
			int x = index % width;
			int y = index / width;
			if (x > 0) {
				frontier.add(index - 1);
			}
			if (x < width - 1) {
				frontier.add(index + 1);
			}
			if (y > 0) {
				frontier.add(index - width);
			}
			if (y < height - 1) {
				frontier.add(index + width);
			}
		}
		result.setRGB(0, 0, width, height, pixels, 0, width);
		return result;
	}

	private static boolean nearTile(int pixel, int tile) {
		int deltaRed = (pixel >> 16 & 0xff) - (tile >> 16 & 0xff);
		int deltaGreen = (pixel >> 8 & 0xff) - (tile >> 8 & 0xff);
		int deltaBlue = (pixel & 0xff) - (tile & 0xff);
		return deltaRed * deltaRed + deltaGreen * deltaGreen + deltaBlue * deltaBlue
				<= TILE_TOLERANCE * TILE_TOLERANCE;
	}

	/// Dresses the app in the web client's icon.
	///
	/// Two calls, because they are two different icons and `setIconImages` alone is the one that does NOT work on
	/// macOS: a frame's icon list drives window decorations and the Windows/Linux taskbar, while the macOS DOCK icon
	/// belongs to the application and is reachable only through [Taskbar]. Setting just the frame's would have left
	/// the dock showing the generic Java cup — which is exactly what it was showing.
	///
	/// Failing to find or decode the image is not worth interrupting a session for: the app keeps the default icon and
	/// says so once in the log, because an icon is the least important thing a walkie-talkie does.
	private void applyAppIcon() {
		try (InputStream source = SwingUi.class.getResourceAsStream(ICON_RESOURCE)) {
			if (source == null) {
				status("[icon] " + ICON_RESOURCE + " is not on the classpath — keeping the default.");
				return;
			}
			BufferedImage loaded = ImageIO.read(source);
			if (loaded == null) {
				status("[icon] " + ICON_RESOURCE + " is not an image ImageIO can read — keeping the default.");
				return;
			}
			BufferedImage icon = withoutTile(loaded);
			frame.setIconImages(List.of(icon));
			if (Taskbar.isTaskbarSupported()) {
				Taskbar taskbar = Taskbar.getTaskbar();
				if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
					taskbar.setIconImage(icon);
				}
			}
		} catch (IOException | UnsupportedOperationException | SecurityException failure) {
			status("[icon] could not be applied (" + describe(failure) + ") — keeping the default.");
		}
	}

	/// Hold-to-talk, driven by the button's MODEL rather than by mouse events.
	///
	/// That is the fix for a real defect: a `MouseListener` never sees the SPACE key, so the first version of this
	/// window visibly depressed the button when Space was pressed and did nothing at all. Swing activates a focused
	/// button through its `ButtonModel`, so watching `isPressed` catches the mouse, Space and Enter with one listener —
	/// and the press/release edges are exactly what a hold needs and what a console keystroke cannot provide.
	///
	/// Space is ALSO bound window-wide, so it works without tabbing to the button first — but not while an editable
	/// field owns the keyboard, or the binding would eat the space bar in the middle of typing a channel name. The
	/// browser guards the same collision in `spaceDrivesFloor`; the shapes differ, and [#typing] says how.
	private void wireTalkGesture() {
		talk.getModel().addChangeListener(_ -> {
			boolean pressed = talk.getModel().isPressed();
			if (pressed == pressedBefore) {
				return;   // armed or rollover changed, not the press itself — see [#pressedBefore]
			}
			pressedBefore = pressed;
			if (pressed) {
				pressTalk();
			} else {
				releaseTalk();
			}
		});

		// Registered on the GLOBAL focus manager, so [#build]'s window listener removes it again: a leaked dispatcher
		// would go on stealing Space — and holding this window alive — for the rest of the JVM's life.
		KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(spaceDrivesFloor);
	}

	/// Space drives the floor from ANYWHERE in the window — which is not something an input map can deliver.
	///
	/// `WHEN_IN_FOCUSED_WINDOW` is consulted only when the focus owner has not consumed the key first, and every
	/// `JButton`, `JCheckBox`, `JList` and `JComboBox` in this window binds SPACE for its own purposes: pressing the
	/// button, ticking the box, extending the selection, opening the popup. So clicking a roster row — or the Locked
	/// checkbox — silently killed push-to-talk, with the Talk button still looking perfectly able and nothing to
	/// explain it. A [KeyEventDispatcher] sees the event before the focus owner does, and can consume it.
	///
	/// The trade is deliberate and worth stating plainly: while this window is active, Space no longer toggles a
	/// focused checkbox or extends a list selection. For a walkie-talkie the microphone wins that argument. An
	/// EDITABLE text component keeps its space bar ([#typing]) — the one case where taking it would be indefensible.
	///
	/// Auto-repeat costs nothing: a repeated KEY_PRESSED re-sets a model that is already pressed, which is not an edge
	/// and so is not a second gesture (see [#pressedBefore]).
	private final KeyEventDispatcher spaceDrivesFloor = event -> {
		if (!drivesFloor(event.getKeyCode(), event.getKeyChar(), event.getID(), frame.isActive(), typing())) {
			return false;
		}
		switch (event.getID()) {
			// Drive the MODEL, not pressTalk directly, so the button visibly depresses and the two input paths cannot
			// disagree about whether a hold is in progress. Armed BEFORE pressed, the order the JDK's own button action
			// uses: the reverse fires an extra `stateChanged` between the two writes.
			case KeyEvent.KEY_PRESSED -> {
				talk.getModel().setArmed(true);
				talk.getModel().setPressed(true);
			}
			case KeyEvent.KEY_RELEASED -> {
				talk.getModel().setArmed(false);
				talk.getModel().setPressed(false);
			}
			default -> { /* KEY_TYPED — consumed below so a focused control cannot act on it either */ }
		}
		return true;
	};

	/// Whether a key event is the floor gesture rather than somebody else's space bar.
	///
	/// Separated out because the dispatcher itself cannot be reached without a window that OWNS THE FOCUS, which is
	/// precisely what a test environment cannot give it — so the decision lives here, where it can be pinned, and the
	/// dispatcher is left with only the model writes.
	///
	/// KEY_TYPED is included by CHARACTER, not by code: a typed space reports `VK_UNDEFINED`. It is claimed so that a
	/// focused control cannot act on the leftover after the press has been taken.
	static boolean drivesFloor(int keyCode, char keyChar, int id, boolean windowActive, boolean editableTextFocused) {
		boolean space = keyCode == KeyEvent.VK_SPACE || id == KeyEvent.KEY_TYPED && keyChar == ' ';
		return space && windowActive && !editableTextFocused;
	}

	/// Whether the keyboard belongs to an EDITABLE text field right now — in which case Space is a space, not a talk
	/// gesture.
	///
	/// Editability rather than type, and the difference is not academic: the Log is a read-only `JTextArea`, so clicking
	/// it to read or copy a line parked the focus on a `JTextComponent` and push-to-talk by Space went silently dead,
	/// with the button still looking perfectly able. A non-editable text area binds no space key of its own, so there is
	/// nothing here to collide with.
	///
	/// Note the shape: this is a deny-list, and the browser's `spaceDrivesFloor` is deliberately an ALLOW-list whose own
	/// doc names the deny-list form — excluding only `INPUT` — as the thing that rots. It rotted the same way here. The
	/// two are not the same rule; what they share is the reason, that a space typed into a field must reach the field.
	private static boolean typing() {
		return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()
				instanceof JTextComponent field && field.isEditable();
	}

	private JPanel buildMembersColumn() {
		members.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		members.addListSelectionListener(_ -> refreshButtons());
		muteMember.addActionListener(_ -> withClient(live -> selectedMember().ifPresent(id ->
				live.setMemberMuted(id, !live.snapshot().mutedMembers().contains(id)))));
		makeOwner.addActionListener(_ -> withClient(live -> selectedMember().ifPresent(live::transferOwnershipTo)));
		muteEveryone.addActionListener(_ -> withClient(live -> live.setAllMuted(true)));

		requestList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		requestList.addListSelectionListener(_ -> refreshButtons());
		admitRequest.addActionListener(_ -> resolveSelectedRequest(true));
		denyRequest.addActionListener(_ -> resolveSelectedRequest(false));
		admitAll.addActionListener(_ -> withClient(live -> live.resolveAllJoinRequests(true)));
		denyAll.addActionListener(_ -> withClient(live -> live.resolveAllJoinRequests(false)));
		requestPanel.setBorder(BorderFactory.createTitledBorder("Waiting to join"));
		requestPanel.add(new JScrollPane(requestList), BorderLayout.CENTER);
		requestPanel.add(stack(row("", admitRequest, denyRequest), row("", admitAll, denyAll)), BorderLayout.SOUTH);
		requestPanel.setVisible(false);

		JScrollPane list = new JScrollPane(members);
		list.setBorder(BorderFactory.createTitledBorder("Members"));
		// UNDER the roster, not above it: these act on the selection, so they belong next to what is selected.
		memberActions.add(stack(row("", muteMember, makeOwner), row("", muteEveryone)), BorderLayout.CENTER);
		memberActions.setVisible(false);

		JPanel column = new JPanel(new BorderLayout());
		column.setPreferredSize(new Dimension(MEMBER_COLUMN_WIDTH, 0));
		column.add(list, BorderLayout.CENTER);
		column.add(stack(memberActions, requestPanel), BorderLayout.SOUTH);
		return column;
	}

	private JPanel buildControlColumn() {
		connection.addActionListener(_ -> {
			if (connecting) {
				cancelConnect();
			} else if (client == null) {
				connect();
			} else {
				disconnect();
			}
		});
		rename.addActionListener(_ -> withClient(live -> live.rename(displayName.getText())));
		apply.addActionListener(_ -> applyChannelChange());

		hiFi.addActionListener(_ -> {
			if (!settingFromModel) {
				withClient(WalkieClient::toggleFidelity);
			}
		});
		locked.addActionListener(_ -> ownerToggle(live -> live.setChannelLock(locked.isSelected())));
		queueOn.addActionListener(_ -> ownerToggle(live -> live.setFloorQueue(queueOn.isSelected())));
		muteArrivals.addActionListener(_ -> ownerToggle(live -> live.setMuteNewMembers(muteArrivals.isSelected())));

		// Every field re-decides which buttons are live, so Rename and Apply are disabled until something has actually
		// changed — the whole point of an enabled button being that pressing it does something.
		onEdit(displayName);
		onEdit(channelField);
		onEdit(passphrase);
		modeBox.addActionListener(_ -> refreshButtons());

		ownerPanel.setLayout(new BorderLayout());
		ownerPanel.add(stack(row("", locked), row("", queueOn), row("", muteArrivals)), BorderLayout.CENTER);
		titled("Owner", ownerPanel);

		JPanel session = stack(
				row("Server:", serverField),
				row("Name:", displayName, rename),
				row("Channel:", channelField),
				row("Mode:", modeBox),
				row("Passphrase:", passphrase),
				row("", hiFi),
				row("", connection, apply));
		titled("Session", session);

		JPanel column = new JPanel(new BorderLayout());
		column.setPreferredSize(new Dimension(CONTROL_COLUMN_WIDTH, 0));
		column.add(stack(session, ownerPanel), BorderLayout.CENTER);
		return column;
	}

	/// Re-evaluates the buttons on every keystroke, so "has this field changed?" is answered as the user types rather
	/// than after they act.
	private void onEdit(JTextComponent field) {
		field.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent event) {
				refreshButtons();
			}

			@Override
			public void removeUpdate(DocumentEvent event) {
				refreshButtons();
			}

			@Override
			public void changedUpdate(DocumentEvent event) {
				refreshButtons();
			}
		});
	}

	// --- actions ----------------------------------------------------------------------------------------------------

	private void withClient(Consumer<? super WalkieClient> action) {
		WalkieClient live = client;
		if (live != null) {
			action.accept(live);
		}
	}

	private void ownerToggle(Consumer<? super WalkieClient> action) {
		if (settingFromModel) {
			return;
		}
		withClient(action);
		// Re-render at once. These checkboxes are drawn FROM the snapshot, which relies on a server echo arriving to
		// correct them — and a toggle the client refuses LOCALLY (setFloorQueue in full duplex) sends nothing, so no
		// echo was ever coming and the box sat there claiming a setting the header denied. An accepted toggle snaps
		// back until its echo lands, which is the same "server state only" rule the browser's flagDisplay states.
		refresh();
	}

	/// Builds the client from the form. Runs on a background thread because construction BLOCKS — a login round trip,
	/// opening the capture device and a WebSocket handshake — and doing that on the EDT would freeze the window for
	/// exactly as long as the slow case takes.
	private void connect() {
		if (client != null) {
			return;
		}
		ClientOptions options = formOptions();
		connecting = true;
		connectCancelled = false;
		// refresh, not refreshButtons: the HEADER is what says an attempt is under way, and only a full render writes it.
		refresh();
		Thread attempt = Thread.ofVirtual().name("gui-connect").unstarted(() -> {
			try {
				WalkieClient built = new WalkieClient(options, this);
				// BEFORE the hand-off, not inside it: the EDT may never run the block below — the window can be gone
				// by now — and a session nobody can see is a session nobody closes. See [#session].
				session.set(built);
				if (connectCancelled || closed.getCount() == 0) {
					// Either the user cancelled or the window went WHILE this was being built, so nobody will ever read
					// the field the EDT is about to be handed. Close it here: awaitClose's wait is bounded, so on the
					// slow path it has already given up, and this is a joined channel with an open capture device.
					session.compareAndSet(built, null);
					built.close();
					return;
				}
				SwingUtilities.invokeLater(() -> {
					connecting = false;
					client = built;
					refresh();
				});
			} catch (Exception failure) {
				// Everything the constructor can throw — a refused login, a busy microphone, a bad passphrase, a
				// closed port — reaches the user as a line in the log rather than a stack trace on a stream nobody
				// is reading. A window has nowhere else to put it. A CANCELLED attempt is not a failure, though: the
				// interrupt that stopped it is what threw, and [#cancelConnect] has already said so.
				if (!connectCancelled) {
					status("[connect failed] " + describe(failure));
				}
				SwingUtilities.invokeLater(() -> {
					connecting = false;
					refresh();
				});
			}
		});
		connectAttempt = attempt;   // published before the start, so awaitClose can never see a thread it cannot join
		attempt.start();
	}

	/// Abandons an attempt that is still in flight — which was otherwise a wait with nothing at all to press.
	///
	/// The button used to go dead for the whole attempt, so the only way out of a slow one was to close the window:
	/// measured in the wild against a Cloudflare tunnel, a login that ended in an HTTP 524 after minutes of silence.
	///
	/// The controls are freed at once and the attempt is interrupted, which reaches both blocking stages — the login
	/// round trip (`HttpClient.send` throws `InterruptedException`) and the WebSocket handshake ([WalkieClient]'s own
	/// `connect` uses `get()` rather than `join()` for exactly this reason). Should a session be built
	/// anyway, in the gap before the flag is read, the connect thread closes it.
	private void cancelConnect() {
		connectCancelled = true;
		Thread attempt = connectAttempt;
		if (attempt != null) {
			attempt.interrupt();
		}
		connecting = false;
		status("[connect] cancelled.");
		refresh();
	}

	/// A failure the user can act on. `getMessage()` is null for a good few of the things that go wrong here — an
	/// `SSLHandshakeException` closed by the peer, some `IOException`s — and "[connect failed] null" tells the reader
	/// nothing at all; it was visible in the very first real use of this window. So fall back to the exception's type,
	/// which at least names the layer that failed, and keep the message when there is one.
	private static String describe(Throwable failure) {
		String message = failure.getMessage();
		return message == null || message.isBlank()
				? failure.getClass().getSimpleName()
				: message + " (" + failure.getClass().getSimpleName() + ")";
	}

	private void disconnect() {
		WalkieClient live = client;
		client = null;
		if (live != null) {
			session.compareAndSet(live, null);   // closing it here; awaitClose must not close it a second time
			// Off the EDT: close() flushes a NORMAL_CLOSURE through a bounded HttpClient shutdown, which blocks.
			Thread closing = Thread.ofVirtual().name("gui-disconnect").unstarted(live::close);
			closingSession = closing;   // so awaitClose waits for the goodbye instead of racing the JVM's exit
			closing.start();
		}
		roster.clear();
		rosterIds.clear();
		refresh();
	}

	/// The adaptive button, mirroring the browser's: a different CHANNEL name is a switch; the same channel with an
	/// owner's mode or passphrase change is an apply. [#applyState] decides which, and whether either is possible.
	private void applyChannelChange() {
		withClient(live -> {
			// Read every widget HERE, on the EDT, before anything moves off it.
			ApplyState state = applyState(live.snapshot(), live);
			ChannelMode mode = (ChannelMode) modeBox.getSelectedItem();
			String channel = typedChannel();
			String key = new String(passphrase.getPassword());
			// Validated locally before the round trip, as ConsoleUi and the browser both do. Not politeness: switchTo
			// applies the target's key OPTIMISTICALLY, and of the refusals only PASSPHRASE_MISMATCH, CHANNEL_LOCKED and
			// CHANNEL_FULL are rolled back — an INVALID_CHANNEL would leave us holding a key for a room that cannot
			// exist while the header still named the old one.
			if (state == ApplyState.SWITCH && mode != ChannelMode.GLOBAL_PTT) {
				if (!WalkieClient.isValidChannelName(channel)) {
					status("[switch] \"" + channel + "\" is not a usable channel name — 1-64 letters, digits or "
							+ "spaces in any language, plus _ or -.");
					return;
				}
				if (key.isBlank()) {
					status("[switch] \"" + channel + "\" needs an encryption passphrase — every channel except the "
							+ "global room is end-to-end encrypted.");
					return;
				}
			}
			// Off the EDT, for the same reason [#connect] is: both switchTo and changePassphrase derive a key, and that
			// is 600,000 PBKDF2 iterations — measured at 175-239 ms on this machine. Blocking the EDT for that long
			// freezes the window AND defers every queued gesture edge, including a Space up-edge, which is how an open
			// microphone would stay open.
			Thread.ofVirtual().name("gui-apply").start(() -> {
				switch (state) {
					case SWITCH -> {
						if (key.isEmpty()) {
							live.switchTo(channel, mode);
						} else {
							live.switchTo(channel, mode, key);
						}
					}
					case CHANGE_MODE -> live.setMode(mode);
					case ROTATE_PASSPHRASE -> live.changePassphrase(key, true);
					case NOTHING -> { /* the button is disabled in this state; nothing to do */ }
				}
			});
		});
	}

	/// The channel the form is asking for, in the ONE form all of its jobs must share: the PBKDF2 salt the E2EE key is
	/// derived from, the `?channel=` routing key, and the Join.
	///
	/// [WalkieClient#switchTo] does NOT canonicalise — its console caller does it first, and this window did not, which
	/// is a correctness bug and not a tidiness one: a trailing space in `"team2 "` salts a DIFFERENT key, while the
	/// server canonicalises the name and routes the join to `team2`. The result is a PASSPHRASE_MISMATCH for a
	/// passphrase that was typed correctly, or a room whose key-check no correctly-typed client can reproduce.
	private String typedChannel() {
		return WalkieClient.canonicalChannelName(channelField.getText());
	}

	private Optional<String> selectedMember() {
		int index = members.getSelectedIndex();
		return index >= 0 && index < rosterIds.size() ? Optional.of(rosterIds.get(index)) : Optional.empty();
	}

	/// Admits or denies the PICKED newcomer, and nobody else.
	///
	/// An empty selection deliberately does nothing. It used to mean "everyone", which read as a convenience and was a
	/// hazard: [#refresh] calls `requests.clear()` on every inbound server message and a cleared model leaves the list
	/// selected at -1 (measured), so "nothing picked" is the state a click routinely lands in rather than a decision
	/// about a whole waiting list — and there is no way to undo an admission. [#admitAll] is the deliberate version.
	private void resolveSelectedRequest(boolean admit) {
		withClient(live -> selectedRequest().ifPresent(target ->
				live.resolveJoinRequestFor(target.id(), target.displayName(), admit)));
	}

	private Optional<JoinRequestInfo> selectedRequest() {
		int index = requestList.getSelectedIndex();
		return index >= 0 && index < requestRows.size() ? Optional.of(requestRows.get(index)) : Optional.empty();
	}

	private void pressTalk() {
		WalkieClient live = client;
		if (live == null) {
			return;
		}
		TalkControl control = talkControl(live.snapshot());
		if (!control.enabled()) {
			return;
		}
		held = control.hold();
		// One call either way: toggleTalk is state-driven, so it grabs a free floor, claims a reserved turn, or flips
		// the full-duplex mic. The gesture decides WHETHER to act, never WHAT to send.
		live.toggleTalk();
	}

	private void releaseTalk() {
		if (!held) {
			return;   // a tap has nothing to end
		}
		held = false;
		// Only if we are actually live: a press that merely CLAIMED a turn opens the mic on the server's grant, which
		// may not have arrived — releasing then would send a release for a floor we were never given.
		withClient(live -> {
			if (live.snapshot().transmitting()) {
				live.toggleTalk();
			}
		});
	}

	// --- WalkieUi ---------------------------------------------------------------------------------------------------

	@Override
	public void status(String line) {
		append(LocalTime.now().format(LOG_TIME) + "  " + line);
	}

	@Override
	public void note(String text) {
		append(text);
	}

	@Override
	public String gesture(Cue cue) {
		return gestureFor(cue);
	}

	/// What the user's hands actually do here, as a static function of the cue — so a test can reach it without a
	/// display, like every other derivation in this class.
	///
	/// The queue cues name the Raise-hand control, which is the only thing that joins or leaves a line: holding Talk on
	/// a busy floor deliberately does nothing, so suggesting it would send the reader to a dead button.
	static String gestureFor(Cue cue) {
		return switch (cue) {
			case TALK -> "hold Talk";
			case STOP -> "let go";
			case JOIN_QUEUE -> "press Raise hand";
			case LEAVE_QUEUE -> "press Leave the line";
		};
	}

	@Override
	public void hint(String terminalAdvice) {
		// Dropped on purpose: this is the command list and the "type 't' to grab a free floor" coaching. In a window
		// the advice IS the controls, and single-letter commands have no prompt to be typed at. The FACTS those lines
		// accompanied still arrive through status().
	}

	@Override
	public void attention() {
		SwingUtilities.invokeLater(() -> {
			Toolkit.getDefaultToolkit().beep();
			// Bounce the dock icon: the whole point is to reach someone NOT looking at the window, which is exactly
			// what a terminal BEL cannot do once its window is buried.
			if (Taskbar.isTaskbarSupported()) {
				Taskbar taskbar = Taskbar.getTaskbar();
				if (taskbar.isSupported(Taskbar.Feature.USER_ATTENTION)) {
					taskbar.requestUserAttention(true, false);
				}
			}
		});
	}

	@Override
	public void sessionEnded() {
		// The difference a window makes, and why this is on the port: a console answers by stopping the process,
		// because its reader can never be satisfied again. Here the session ends and the window does not — the log
		// stays readable and Connect comes back.
		SwingUtilities.invokeLater(() -> {
			WalkieClient ended = client;
			client = null;
			if (ended != null) {
				// The client closed itself before telling us (exitGracefully closes, then reports), so this is only
				// dropping a handle — but dropping it HERE is what stops awaitClose from closing a dead session.
				session.compareAndSet(ended, null);
			}
			roster.clear();
			rosterIds.clear();
			refresh();
		});
	}

	@Override
	public void stateChanged() {
		SwingUtilities.invokeLater(this::refresh);
	}

	// --- rendering --------------------------------------------------------------------------------------------------

	/// Redraws everything from ONE snapshot. Coalescing is free — a burst of `stateChanged` calls collapses into
	/// whatever the EDT gets to — which is why the port's signal carries no payload.
	private void refresh() {
		WalkieClient live = client;
		if (live == null) {
			// The progress moved here when the button became Cancel: something has to say an attempt is under way, and a
			// button reading "Connecting…" cannot also offer to stop it.
			header.setText(connecting ? "Connecting…" : "Not connected.");
			// Forget what was rendered, so a reconnect writes the server's values into the form again rather than
			// treating whatever is left in the fields as already current.
			renderedChannel = null;
			renderedMode = null;
			renderedName = null;
			roster.clear();
			rosterIds.clear();
			requestPanel.setVisible(false);
			ownerPanel.setVisible(false);
			talk.setText("Not connected");
			talk.setEnabled(false);
			raiseHand.setVisible(false);
			refreshButtons();
			return;
		}
		ClientSnapshot view = live.snapshot();
		header.setText(headerText(view));
		// Re-select the same MEMBER across the rebuild. Clearing the model makes the JList drop its selection
		// (measured: index 1 becomes -1, and refilling the identical rows does not bring it back), and this runs on
		// every inbound message — so without this the owner's Mute and Make owner went dead between picking a row and
		// reaching for the button, which is precisely when the floor is busy and moderation is wanted. By ID and never
		// by index: rosterOrder moves a queued member to the head, so the surviving row number is a different person.
		String pickedMember = selectedMember().orElse(null);
		roster.clear();
		rosterIds.clear();
		rosterIds.addAll(rosterOrder(view));
		rosterRows(view).forEach(roster::addElement);
		if (pickedMember != null) {
			int again = rosterIds.indexOf(pickedMember);
			if (again >= 0) {
				members.setSelectedIndex(again);
			}
		}

		TalkControl control = talkControl(view);
		if (grantOutlivedHold(control, view, held)) {
			live.releaseHeldFloor();
		}
		talk.setText(control.label());
		talk.setEnabled(control.enabled());
		raiseHand.setVisible(control.raiseHandOffered());
		raiseHand.setText(raiseHandLabel(view));

		// Written FROM the snapshot the server owns, never from the click — the rule the browser's flagDisplay states,
		// which is what makes a refused toggle snap back instead of leaving the window claiming something untrue.
		settingFromModel = true;
		try {
			locked.setSelected(view.channelLocked());
			queueOn.setSelected(view.floorQueueEnabled());
			muteArrivals.setSelected(view.muteNewMembers());
			// Only when the user is not mid-edit — and only when the server actually MOVED the value. The focus check
			// is the second guard, not the only one: a half-typed channel switch has to survive a click into the
			// Passphrase field, which is exactly what typing that channel's key requires. See [#renderedChannel].
			// Each marker moves ONLY where the write happened. Assigning it on the focused branch too — where the write
			// was skipped — poisoned it permanently: the widget kept the old value while the marker claimed the new
			// one, so the "did the server move it?" test was false forever and the field never caught up. Measured on a
			// headless replay: a channel and mode change arriving while those widgets held focus never reached the form
			// again, across fifty later repaints with focus released.
			String confirmedName = view.memberNames().getOrDefault(view.selfId(), "");
			if (!displayName.isFocusOwner() && !confirmedName.equals(renderedName)) {
				displayName.setText(confirmedName);
				renderedName = confirmedName;
			}
			if (!channelField.isFocusOwner() && view.channel() != null && !view.channel().equals(renderedChannel)) {
				channelField.setText(view.channel());
				renderedChannel = view.channel();
			}
			if (!modeBox.isFocusOwner() && view.mode() != null && view.mode() != renderedMode) {
				modeBox.setSelectedItem(view.mode());
				renderedMode = view.mode();
			}
		} finally {
			settingFromModel = false;
		}
		boolean owner = view.selfId().equals(view.ownerId());
		ownerPanel.setVisible(owner);

		String pickedRequest = selectedRequest().map(JoinRequestInfo::id).orElse(null);
		requests.clear();
		requestRows.clear();
		requestRows.addAll(view.joinRequests());
		view.joinRequests().forEach(request ->
				requests.addElement(request.displayName() + " (#" + WalkieClient.shortId(request.id()) + ")"));
		// The same rebuild-survival the roster gets, and for a sharper reason: the owner's aim here decides who gets
		// into a LOCKED channel.
		if (pickedRequest != null) {
			for (int index = 0; index < requestRows.size(); index++) {
				if (requestRows.get(index).id().equals(pickedRequest)) {
					requestList.setSelectedIndex(index);
					break;
				}
			}
		}
		// Owner-gated as well as non-empty: the server sends the waiting list only to the channel's CURRENT owner, so
		// after handing the channel over the list we were shown is not ours to act on. Admitting cannot be undone.
		requestPanel.setVisible(owner && !requestRows.isEmpty());
		refreshButtons();
	}

	/// Enables exactly the buttons that would DO something. A button you can press that does nothing is worse than a
	/// disabled one: it makes the user doubt the thing they just typed rather than the button.
	private void refreshButtons() {
		WalkieClient live = client;
		boolean connected = live != null;
		serverField.setEnabled(!connected);   // the socket is already open; changing this would say nothing
		connection.setText(connectionLabel(connecting, connected));
		// Never disabled: while an attempt is in flight this button is the only way to stop it, which is precisely the
		// state it used to be dead in.
		connection.setEnabled(true);
		if (!connected) {
			rename.setEnabled(false);
			apply.setEnabled(false);
			apply.setText("Apply");
			memberActions.setVisible(false);
			return;
		}
		ClientSnapshot view = live.snapshot();
		rename.setEnabled(renameOffered(view, displayName.getText()));

		ApplyState state = applyState(view, live);
		apply.setEnabled(state != ApplyState.NOTHING);
		apply.setText(switch (state) {
			case SWITCH -> "Switch channel";
			case CHANGE_MODE, ROTATE_PASSPHRASE -> "Apply changes";
			case NOTHING -> "Apply";
		});

		boolean aRequestIsPicked = selectedRequest().isPresent();
		admitRequest.setEnabled(aRequestIsPicked);
		denyRequest.setEnabled(aRequestIsPicked);
		admitAll.setEnabled(!requestRows.isEmpty());
		denyAll.setEnabled(!requestRows.isEmpty());

		Optional<String> picked = selectedMember();
		boolean owner = view.selfId().equals(view.ownerId());
		// HIDDEN for a non-owner, not disabled — the same treatment the owner checkboxes get, so the column simply
		// does not offer moderation to someone who has none.
		memberActions.setVisible(owner);
		boolean other = picked.isPresent() && !picked.get().equals(view.selfId());
		muteMember.setEnabled(other);
		makeOwner.setEnabled(other);
		muteMember.setText(picked.filter(view.mutedMembers()::contains).isPresent() ? "Unmute" : "Mute");
	}

	/// What the adaptive Apply button would do for the form as it stands, reading the widgets once.
	private ApplyState applyState(ClientSnapshot view, WalkieClient live) {
		return applyState(view, typedChannel(), (ChannelMode) modeBox.getSelectedItem(),
				passphraseChanged(new String(passphrase.getPassword()), live.currentPassphrase()),
				live.memberRekeyPending());
	}

	/// Whether the passphrase box asks for a DIFFERENT secret than the one the channel's key came from.
	///
	/// "Changed", not "non-empty": the field is pre-filled from `--key` (see [#seedForm]), so non-empty is the state an
	/// untouched form starts in. Blank stays dead either way — encryption cannot be turned off.
	///
	/// Stripped on BOTH sides, because [WalkieClient#changePassphrase] strips before it stores. Comparing raw text
	/// against a stored stripped value left a pasted `"secret "` permanently "changed" — and a trailing space is
	/// invisible in a password field — so Apply stayed enabled after the rotation landed and every further click
	/// re-keyed the channel to the value already in use, which is the very thing this test exists to prevent.
	static boolean passphraseChanged(String typed, String inForce) {
		String wanted = typed.strip();
		return !wanted.isEmpty() && !wanted.equals(inForce == null ? "" : inForce.strip());
	}

	/// The same decision as a pure function of what the form holds — the window's second decision, and the one that
	/// went longest without a test because it used to read three widgets directly and so needed a display to reach.
	///
	/// `passphraseChanged` is deliberately a boolean rather than the passphrase itself: this decision needs to know
	/// only THAT the secret differs, and key material has no business travelling through a value that a stray log line
	/// could print.
	static ApplyState applyState(ClientSnapshot view, String typedChannel, ChannelMode typedMode,
	                             boolean passphraseChanged, boolean rekeyPending) {
		boolean owner = view.selfId().equals(view.ownerId());
		// The channel the client would ACTUALLY join, which is not always the one typed: every GLOBAL_PTT join is
		// pinned to the server's global room ([WalkieClient#switchTo] forces it), so typing another name there used to
		// light up "Switch channel" for a switch that switchTo then refused as "already in global" — an enabled button
		// that did nothing, and the only way out of the global room.
		String target = typedMode == ChannelMode.GLOBAL_PTT ? WalkieClientLauncher.GLOBAL_CHANNEL : typedChannel;
		if (!target.isEmpty() && !target.equals(view.channel())) {
			// A different channel is a SWITCH, and anyone may switch — it is a fresh join, so it carries whatever mode
			// and passphrase the form holds.
			return ApplyState.SWITCH;
		}
		// Same channel: changing its mode belongs to the owner alone.
		if (owner && typedMode != null && typedMode != view.mode()) {
			return ApplyState.CHANGE_MODE;
		}
		// So does rotating its passphrase, with ONE exception — a member adopting a rotation the owner has already
		// announced, which is the only non-owner use [WalkieClient#changePassphrase] accepts. Gating this on ownership
		// alone left the one control that can do it disabled for exactly the person the log had just told to enter a
		// new passphrase, with their transmit gate dropping every frame meanwhile.
		if (passphraseChanged && (owner || rekeyPending)) {
			return ApplyState.ROTATE_PASSPHRASE;
		}
		return ApplyState.NOTHING;
	}

	/// Whether Rename would change anything: a non-blank name that differs from the one the server has confirmed.
	static boolean renameOffered(ClientSnapshot view, String typed) {
		String trimmed = typed.strip();
		return !trimmed.isEmpty() && !trimmed.equals(view.memberNames().get(view.selfId()));
	}

	/// The three things the adaptive button can do, plus the state in which it does nothing and is disabled.
	enum ApplyState {
		SWITCH,
		CHANGE_MODE,
		ROTATE_PASSPHRASE,
		NOTHING
	}

	private ClientOptions formOptions() {
		ClientOptions base = pending;
		ChannelMode mode = (ChannelMode) modeBox.getSelectedItem();
		return new ClientOptions(
				serverField.getText().strip(),
				// The same pinning the adaptive button applies, and for the same reason: a GLOBAL_PTT join goes to the
				// server's global room whatever the channel field says. `WalkieClientLauncher.prefill` does this for the
				// console path; leaving it out here would have made Connect and Apply disagree about where GLOBAL_PTT
				// goes, which is how the button came to lie in the first place.
				mode == ChannelMode.GLOBAL_PTT ? WalkieClientLauncher.GLOBAL_CHANNEL : typedChannel(),
				mode,
				displayName.getText().strip(),
				hiFi.isSelected(),
				base == null ? null : base.inputDevice(),
				new String(passphrase.getPassword()),
				base == null ? null : base.tlsTruststore(),
				base != null && base.startMuted()
		);
	}

	private void append(String line) {
		SwingUtilities.invokeLater(() -> {
			log.append(line + System.lineSeparator());
			if (log.getLineCount() > LOG_LINES_KEPT) {
				try {
					log.getDocument().remove(0, log.getLineStartOffset(log.getLineCount() - LOG_LINES_KEPT));
				} catch (BadLocationException _) {
					// The document shrank under us — nothing to trim, and a log is not worth an exception.
				}
			}
			log.setCaretPosition(log.getDocument().getLength());
		});
	}

	// --- pure derivations, so the window's text is testable without a display ---------------------------------------

	/// The one line above the roster: where we are, in what mode, and who owns it.
	static String headerText(ClientSnapshot view) {
		return view.channel() == null
				? "Not in a channel."
				: view.channel() + " · " + view.mode() + " · "
				+ (view.selfId().equals(view.ownerId())
				? "you own this channel"
				: view.ownerId() == null ? "no owner" : "owner: " + view.memberNames().getOrDefault(view.ownerId(), view.ownerId()))
				+ (view.channelLocked() ? " · 🔒 locked" : "")
				+ (view.muteNewMembers() ? " · 🔇 arrivals muted" : "")
				+ (view.floorQueueEnabled() ? " · ✋ queue on" : "");
	}

	/// The member ids in the order the roster shows them: whoever is in line first, in the queue's own order, then
	/// everyone else by name — the same two-section shape the browser settled on, for the same reason (a queue's
	/// meaning IS its order, and a name-sorted list destroys it).
	///
	/// The ROWS and the IDS behind them come from this one method, so a selection can never name a different member
	/// than the row the user clicked. Not a tidiness point: the two actions behind that selection are "mute this
	/// person" and "give them the channel".
	static List<String> rosterOrder(ClientSnapshot view) {
		Map<String, String> names = view.memberNames();
		List<String> queue = shownQueue(view);
		return Stream.concat(
						queue.stream(),
						names.keySet().stream()
								.filter(id -> !queue.contains(id))
								.sorted((a, b) -> names.get(a).compareToIgnoreCase(names.get(b)))
				)
				.toList();
	}

	/// The queue as the roster can actually SHOW it: the waiting ids this client has a name for.
	///
	/// One method behind both the order and the numbering, because they disagreed. The order filtered out ids the
	/// roster does not know while the numbering counted the RAW list, so a single stale entry — the gap between a
	/// departing member's `MemberLeft` and the `FloorStatus` that dequeues them — shifted every position. Measured on
	/// that state: the only queued member was numbered "2.", no row was numbered 1, and although the floor was free no
	/// row said it was being offered. The browser cannot drift this way; its positions come from a CSS counter over the
	/// rows it actually drew.
	private static List<String> shownQueue(ClientSnapshot view) {
		Map<String, String> names = view.memberNames();
		return view.floor().waiting().stream().filter(names::containsKey).toList();
	}

	/// The roster as text, one row per [#rosterOrder] entry.
	static List<String> rosterRows(ClientSnapshot view) {
		List<String> queue = shownQueue(view);
		return rosterOrder(view).stream()
				.map(id -> rosterRow(view, id, queue.indexOf(id)))
				.toList();
	}

	private static String rosterRow(ClientSnapshot view, String id, int queueIndex) {
		return (queueIndex >= 0 ? (queueIndex + 1) + ". " : "   ")
				+ view.memberNames().getOrDefault(id, id)
				+ (id.equals(view.selfId()) ? " (you)" : "")
				+ (id.equals(view.ownerId()) ? " 👑" : "")
				+ (view.mutedMembers().contains(id) ? " 🔇" : "")
				+ (id.equals(view.floor().holder()) ? " 🔊 talking" : "")
				+ (queueIndex == 0 && view.floor().holder() == null ? " — offered the floor" : "");
	}

	/// What the Talk control is: whether it is a HOLD or a tap, whether it does anything at all, what it says, and
	/// whether a separate raise-hand action should be offered beside it.
	///
	/// This is the window's only decision, and it is deliberately about the GESTURE rather than the floor. Every
	/// branch delegates the ACTION to [WalkieClient#toggleTalk], which derives what to send from the same floor state
	/// the console and the server use — so a busy floor, a claim window and an owner mute are all decided in one place
	/// and this method only chooses how a pointer should behave. It is NOT a port of the browser's `talkDecision`, and
	/// should not grow into one: that would be a fourth copy of the floor rules.
	static TalkControl talkControl(ClientSnapshot view) {
		return view.channel() == null
				? new TalkControl(false, false, "Not in a channel", false)
				: view.mutedMembers().contains(view.selfId())
				? new TalkControl(false, false, "Muted by the channel owner", false)
				: view.mode() == ChannelMode.FULL_DUPLEX ?
				// No floor, so no hold: this is a mic switch, and a switch you have to keep holding would be absurd for a
				// mode whose whole point is an open channel. Mirrors the browser, whose full-duplex control is a click.
				new TalkControl(false, true, view.transmitting()
						? "Mic ON — click to mute" : "Mic OFF — click to talk", false)
				: switch (WalkieClient.floorStateFor(view.selfId(), view.floor().holder(), view.floor().waiting())) {
			case LIVE -> new TalkControl(true, true, "LIVE — release to stop", false);
			case MY_TURN -> new TalkControl(true, true, "YOUR TURN — hold to talk", false);
			// In line already: holding would do nothing, and the tap that LEAVES the line is the raise-hand button.
			case IN_LINE -> new TalkControl(false, false, "In line to talk", true);
			case IDLE -> view.floor().holder() == null && view.floor().waiting().isEmpty()
					? new TalkControl(true, true, "Hold to talk", false)
					// Busy. A hold cannot join a queue — that is a tap — so the button goes dead and, when the owner
					// has the queue on, the raise-hand action appears instead.
					: new TalkControl(false, false, "Floor busy", view.floorQueueEnabled());
		};
	}

	/// What the queue control does from here: join the line, or leave it.
	///
	/// A fixed "Raise hand" label was wrong in the second case and dangerously so, because the control is offered to a
	/// member who is ALREADY in line — [#talkControl] returns `raiseHandOffered` for IN_LINE precisely so the tap that
	/// leaves the queue has somewhere to live. Tapping what reads as "raise my hand again" sent `ReleaseFloor` and
	/// silently dropped the user's place. The browser has one Talk control that relabels itself for the same reason.
	static String raiseHandLabel(ClientSnapshot view) {
		return view.floor().waiting().contains(view.selfId()) ? "Leave the line ✋" : "Raise hand ✋";
	}

	/// What the one session button says, given the two states it has to distinguish.
	///
	/// Three meanings for one control, and it is the same question in each: am I in a session? "Not yet, and you may
	/// stop waiting" is as much an answer as the other two.
	static String connectionLabel(boolean connecting, boolean connected) {
		if (connecting) {
			return "Cancel";
		}
		return connected ? "Disconnect" : "Connect";
	}

	/// Whether a floor GRANT has outlived the hold that asked for it — an open microphone with nobody holding anything.
	///
	/// This is the window's half of the browser's `grantOpensMic(mode, talkHeld)` rule, and it exists because a press
	/// and its release can both complete inside one round trip: [#releaseTalk] finds `transmitting()` still false, so
	/// it has no floor to hand back and sends nothing, and the grant then arrives to open the mic with `held` already
	/// cleared. Nothing else would ever close it — the server's idle sweep spares a holder who is actively sending, so
	/// the mic stayed open until the next press or the five-minute max-hold.
	///
	/// Only for a HOLD gesture: full duplex reports `hold() == false`, and its open mic is a deliberate switch that
	/// must survive every repaint.
	static boolean grantOutlivedHold(TalkControl control, ClientSnapshot view, boolean held) {
		return control.hold() && view.transmitting() && !held;
	}

	/// What the Talk control should be for one snapshot. `hold` is what makes the press/release edges meaningful;
	/// `raiseHandOffered` is the tap that joins or leaves the queue, which a hold must never do by accident.
	record TalkControl(boolean hold, boolean enabled, String label, boolean raiseHandOffered) {
	}

}
