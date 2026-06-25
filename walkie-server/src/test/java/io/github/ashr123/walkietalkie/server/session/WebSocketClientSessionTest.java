package io.github.ashr123.walkietalkie.server.session;

import io.github.ashr123.walkietalkie.server.protocol.MessageCodec;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/// A failing underlying [WebSocketSession] turns the checked [IOException] from a send into an
/// [UncheckedIOException] carrying the session id — the error path that a healthy integration socket never
/// takes, so it is driven here with a mock whose `sendMessage` throws.
class WebSocketClientSessionTest {

	@Test
	void aFailedControlSendIsWrappedAsUncheckedWithTheSessionId() throws IOException {
		WebSocketSession ws = mock(WebSocketSession.class);
		when(ws.getId()).thenReturn("sess-1");
		doThrow(new IOException("socket down")).when(ws).sendMessage(any());
		MessageCodec codec = mock(MessageCodec.class);
		when(codec.encode(any())).thenReturn("{}");

		WebSocketClientSession session = new WebSocketClientSession(ws, codec, Transport.AUDIO_RELAY);

		UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
				() -> session.send(new ServerMessage.FloorIdle()));
		assertTrue(thrown.getMessage().contains("sess-1"), "the wrapped error names the failed session");
	}

	@Test
	void aFailedAudioSendIsWrappedAsUncheckedWithTheSessionId() throws IOException {
		WebSocketSession ws = mock(WebSocketSession.class);
		when(ws.getId()).thenReturn("sess-2");
		doThrow(new IOException("socket down")).when(ws).sendMessage(any());

		WebSocketClientSession session = new WebSocketClientSession(ws, mock(MessageCodec.class), Transport.AUDIO_RELAY);

		UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
				() -> session.sendAudio(new byte[]{1, 2, 3}));
		assertTrue(thrown.getMessage().contains("sess-2"));
	}
}
