package ar.edu.um.prog2.appointmentreservation.user.infrastructure.web;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ar.edu.um.prog2.appointmentreservation.user.application.model.AuthenticationCommand;
import ar.edu.um.prog2.appointmentreservation.user.application.usecase.AuthenticateUserUseCase;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.web.dto.AuthenticationRequest;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.web.dto.AuthenticationResponse;

@RestController
@RequestMapping("/api")
public class UserAuthenticationController {
	private final AuthenticateUserUseCase authenticateUser;

	public UserAuthenticationController(AuthenticateUserUseCase authenticateUser) { this.authenticateUser = authenticateUser; }

	@PostMapping("/authenticate")
	public ResponseEntity<AuthenticationResponse> authenticate(@Valid @RequestBody AuthenticationRequest request) {
		var token = authenticateUser.authenticate(new AuthenticationCommand(request.username(), request.password(), request.rememberMe()));
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(token.value());
		return ResponseEntity.ok().headers(headers).cacheControl(CacheControl.noStore())
				.body(new AuthenticationResponse(token.value()));
	}
}
