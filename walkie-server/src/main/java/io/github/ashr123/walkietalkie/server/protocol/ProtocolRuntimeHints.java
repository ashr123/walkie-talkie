package io.github.ashr123.walkietalkie.server.protocol;

import io.github.ashr123.walkietalkie.shared.protocol.*;
import org.jspecify.annotations.NonNull;
import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import java.util.stream.Stream;

/// Ahead-of-time (GraalVM native) reflection hints for the WebSocket wire protocol.
///
/// Under a native image Jackson still binds each message by reflection — instantiating the record, reading its
/// components, and resolving the polymorphic `@JsonTypeInfo`/`@JsonTypeName` subtype by name — so every wire type
/// needs a reflection entry in the image or the first frame fails with a `MissingReflectionRegistrationError`.
/// Spring's AOT engine derives those entries automatically from `@Controller` request/response types, but the
/// walkie protocol is (de)serialized inside [MessageCodec] — an ordinary `@Component` — via `ClientMessage.class`
/// and a `ServerMessage` argument, which the engine never inspects. So the whole protocol is invisible to it and
/// must be registered here (wired in by [MessageCodec]'s `@ImportRuntimeHints`). The `LoginResponse` DTO is the one
/// wire type reachable from a controller signature, so AOT already hints it — it is deliberately not repeated here.
///
/// The set is derived from each sealed root's [Class#getPermittedSubclasses()] rather than a hand-maintained list of
/// the ~30 record subtypes, so a message type added to the `permits` clause is covered automatically and the hints
/// can never silently drift out of sync with the protocol. The carried payload types (`MemberInfo`, `ChannelMode`
/// and `ErrorCode`) are **not** listed: [BindingReflectionHintsRegistrar] walks each record's components and
/// registers them transitively, so registering the messages that nest them suffices. `ProtocolRuntimeHintsTest`
/// asserts those three by name, turning that transitive reach into something verified rather than assumed.
class ProtocolRuntimeHints implements RuntimeHintsRegistrar {

	private final BindingReflectionHintsRegistrar bindingRegistrar = new BindingReflectionHintsRegistrar();

	@Override
	public void registerHints(@NonNull RuntimeHints hints, ClassLoader classLoader) {
		protocolTypes().forEach(type -> bindingRegistrar.registerReflectionHints(hints.reflection(), type));
	}

	/// The two sealed roots and all their permitted record subtypes — everything Jackson binds directly on the relay
	/// path. The carried payload types (`MemberInfo`, `ChannelMode`, `ErrorCode`) are omitted on purpose: the binding
	/// registrar reaches them transitively through the records that nest them (see the class doc). Package-visible so
	/// the test asserts hints against the exact same set the registrar registers (no second copy of the list to drift).
	static Stream<Class<?>> protocolTypes() {
		return Stream.of(ClientMessage.class, ServerMessage.class)
				.flatMap(root -> Stream.concat(
						Stream.of(root),
						Stream.of(root.getPermittedSubclasses())
				));
	}
}
