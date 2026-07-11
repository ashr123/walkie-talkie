package io.github.ashr123.walkietalkie.server.protocol;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ErrorCode;
import io.github.ashr123.walkietalkie.shared.protocol.MemberInfo;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the AOT/native reflection contract for the wire protocol: without these hints a native image would fail on
/// the first frame it tries to bind. Exercised directly against a [RuntimeHints] (no native build needed), so a
/// missing hint is caught in the ordinary JVM test run rather than only at native runtime.
class ProtocolRuntimeHintsTest {

	private static RuntimeHints registeredHints() {
		RuntimeHints hints = new RuntimeHints();
		new ProtocolRuntimeHints().registerHints(hints, ProtocolRuntimeHintsTest.class.getClassLoader());
		return hints;
	}

	@Test
	void everyDeclaredProtocolTypeGetsAReflectionHint() {
		RuntimeHints hints = registeredHints();
		ProtocolRuntimeHints.protocolTypes().forEach(type ->
				assertThat(RuntimeHintsPredicates.reflection().onType(type))
						.as("reflection binding hint for %s", type.getName())
						.accepts(hints));
	}

	@Test
	void coversRepresentativePolymorphicSubtypesAndSupportingTypes() {
		// A guard against the sealed-hierarchy reflection going vacuous: the test above passes even if
		// getPermittedSubclasses() somehow yielded nothing (it would just iterate the two roots + three payload
		// types). These concrete, hand-picked entries prove the subtypes AND the nested payload types are really in
		// the hint set — a Join/SetLocked inbound record, a Joined/ErrorMessage outbound record, and the three
		// carried types (MemberInfo plus the two enums, including ErrorCode's @JsonEnumDefaultValue fallback).
		RuntimeHints hints = registeredHints();
		assertThat(RuntimeHintsPredicates.reflection().onType(ClientMessage.Join.class)).accepts(hints);
		assertThat(RuntimeHintsPredicates.reflection().onType(ClientMessage.SetLocked.class)).accepts(hints);
		assertThat(RuntimeHintsPredicates.reflection().onType(ServerMessage.Joined.class)).accepts(hints);
		assertThat(RuntimeHintsPredicates.reflection().onType(ServerMessage.ErrorMessage.class)).accepts(hints);
		assertThat(RuntimeHintsPredicates.reflection().onType(MemberInfo.class)).accepts(hints);
		assertThat(RuntimeHintsPredicates.reflection().onType(ChannelMode.class)).accepts(hints);
		assertThat(RuntimeHintsPredicates.reflection().onType(ErrorCode.class)).accepts(hints);
	}
}
