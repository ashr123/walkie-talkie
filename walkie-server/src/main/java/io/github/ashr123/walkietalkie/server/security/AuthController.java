package io.github.ashr123.walkietalkie.server.security;

import io.github.ashr123.walkietalkie.shared.protocol.LoginResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Login endpoint for the bearer token. `/login` is the only unauthenticated application endpoint; it
/// takes no input and mints a fresh, signed, short-lived token (see [AuthService]). There is no `/logout`:
/// the token is stateless and self-expiring, so ending a session is simply closing the WebSocket.
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/login")
	public LoginResponse login() {
		return new LoginResponse(authService.issueToken());
	}
}
