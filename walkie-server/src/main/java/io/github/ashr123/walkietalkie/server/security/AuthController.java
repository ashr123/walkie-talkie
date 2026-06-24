package io.github.ashr123.walkietalkie.server.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/// Login endpoint that hands out a bearer token for use on the WebSocket endpoints.
/// This is the only unauthenticated application endpoint; everything else requires the token.
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/login")
	public LoginResponse login(@Valid @RequestBody LoginRequest request) {
		return new LoginResponse(request.username(), authService.issueToken(request.username()));
	}

	public record LoginRequest(
			@NotBlank
			@Pattern(regexp = "[A-Za-z0-9_.-]{1,32}", message = "username must be 1-32 chars of [A-Za-z0-9_.-]")
			String username) {
	}

	public record LoginResponse(String userId, String token) {
	}
}
