package ar.edu.um.prog2.appointmentreservation.user.infrastructure.web;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ar.edu.um.prog2.appointmentreservation.user.application.model.RegisteredUser;
import ar.edu.um.prog2.appointmentreservation.user.application.model.RegisterUserCommand;
import ar.edu.um.prog2.appointmentreservation.user.application.usecase.RegisterUserUseCase;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.web.dto.RegisterUserRequest;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.web.dto.RegisterUserResponse;

@RestController
@RequestMapping("/api")
public class UserRegistrationController {

	private final RegisterUserUseCase registerUser;

	public UserRegistrationController(RegisterUserUseCase registerUser) {
		this.registerUser = registerUser;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	public RegisterUserResponse register(@Valid @RequestBody RegisterUserRequest request) {
		RegisteredUser user = registerUser.register(new RegisterUserCommand(request.login(), request.password(),
				request.firstName(), request.lastName(), request.email(), request.imageUrl(), request.langKey()));
		return new RegisterUserResponse(user.login(), user.firstName(), user.lastName(),
				user.email(), user.imageUrl(), user.langKey());
	}
}
