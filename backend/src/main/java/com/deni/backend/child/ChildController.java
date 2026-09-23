package com.deni.backend.child;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/children")
public class ChildController {

	private final ChildService childService;

	public ChildController(ChildService childService) {
		this.childService = childService;
	}

	@PostMapping
	ResponseEntity<ChildService.ChildResult> register(@Valid @RequestBody CreateChildRequest request,
			@RequestHeader("Idempotency-Key") UUID idempotencyKey) {
		ChildService.ChildResult result = childService.register(request.name(), request.birthDate(), idempotencyKey);
		return ResponseEntity.created(URI.create("/api/v1/children/" + result.childId())).body(result);
	}

	@PatchMapping("/{childId}")
	ChildService.ChildResult update(@PathVariable UUID childId, @Valid @RequestBody UpdateChildRequest request) {
		return childService.update(childId, request.name(), request.birthDate());
	}

	@GetMapping("/{childId}/safety-profile")
	ChildService.SafetyProfileResult getSafetyProfile(@PathVariable UUID childId) {
		return childService.getSafetyProfile(childId);
	}

	public record CreateChildRequest(
			@NotBlank(message = "이름은 필수입니다.")
			@Size(max = 50, message = "이름은 50자 이하여야 합니다.")
			String name,
			@NotNull(message = "생년월일은 필수입니다.")
			@PastOrPresent(message = "미래 날짜는 입력할 수 없습니다.")
			LocalDate birthDate) {
	}

	public record UpdateChildRequest(
			@Size(max = 50, message = "이름은 50자 이하여야 합니다.")
			String name,
			@PastOrPresent(message = "미래 날짜는 입력할 수 없습니다.")
			LocalDate birthDate) {
	}
}
