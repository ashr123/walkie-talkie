package io.github.ashr123.walkietalkie.server.security;

import io.github.ashr123.option.Some;
import io.github.ashr123.walkietalkie.shared.protocol.LoginResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/// Login/logout endpoints for the bearer token. `/login` is the only unauthenticated application
/// endpoint; `/logout`, like everything else, requires a valid token — and revokes it.
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

	/// Revokes the bearer token presented on the request, ending its session immediately.
	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(HttpServletRequest request) {
		if (BearerTokens.extract(request.getHeader("Authorization"), request.getParameter("token")) instanceof Some(String token))
			authService.revoke(token);
	}

	public record LoginRequest(
			@NotBlank
			@Pattern(regexp = "[A-Za-z0-9_.-]{1,32}", message = "username must be 1-32 chars of [A-Za-z0-9_.-]")
			String username) {
	}
}
