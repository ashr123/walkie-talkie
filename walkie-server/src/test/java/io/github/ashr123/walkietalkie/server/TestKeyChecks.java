package io.github.ashr123.walkietalkie.server;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;

/// The key-check a test's `Join` has to carry, now that every channel except the server-managed `global` room is
/// end-to-end encrypted.
///
/// This exists as one shared function rather than a literal at each of the ~200 join sites because the value is
/// **conditional on the mode**, and getting that condition wrong is silent in both directions. A blanket key-check
/// breaks the ten `GLOBAL_PTT` tests that exist precisely to pin the unencrypted carve-out (they start failing with
/// `ENCRYPTION_NOT_ALLOWED`). A blanket `null` is worse than that: it does not fail the affected tests loudly so
/// much as *empty* them — `ConcurrencyStressTest`'s join/floor/rename races were measured going from a peak of four
/// live channels to zero while still reporting success, because every join was refused and its only assertions
/// ("no errors", "no channels left") are satisfied by a registry nothing ever entered.
///
/// So: derive it from the mode, in one place, and let a test that means something else pass its own value.
public final class TestKeyChecks {

	/// An arbitrary but fixed key-check. Its VALUE is meaningless to the server — it compares joiners' key-checks
	/// for equality and never derives anything from them — so one constant serves every test that only needs a
	/// channel to be encrypted. Tests about key-check *disagreement* pass their own values (`kcv-A` vs `kcv-X`)
	/// instead of using this.
	public static final String ENCRYPTED = "kcv-test";

	private TestKeyChecks() {
	}

	/// The key-check a join in `mode` must carry: none for the global room, which the server refuses to encrypt,
	/// and [#ENCRYPTED] for every other mode, which the server now refuses to leave unencrypted.
	public static String keyCheckFor(ChannelMode mode) {
		return mode == ChannelMode.GLOBAL_PTT ? null : ENCRYPTED;
	}
}
