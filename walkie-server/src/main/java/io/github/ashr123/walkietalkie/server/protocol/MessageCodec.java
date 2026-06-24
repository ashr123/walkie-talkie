package io.github.ashr123.walkietalkie.server.protocol;

import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/// Translates between the JSON wire format and the typed protocol records, using Spring Boot's
/// auto-configured Jackson 3 [JsonMapper]. Jackson 3 throws unchecked exceptions, so decode
/// failures surface as runtime exceptions for the caller to turn into an error reply.
@Component
public class MessageCodec {

	private final JsonMapper jsonMapper;

	public MessageCodec(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	public ClientMessage decode(String json) {
		return jsonMapper.readValue(json, ClientMessage.class);
	}

	public String encode(ServerMessage message) {
		return jsonMapper.writeValueAsString(message);
	}
}
