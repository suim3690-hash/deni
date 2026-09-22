package com.deni.backend.operation;

import com.deni.backend.common.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class OperationController {
	private final OperationService service;
	public OperationController(OperationService service) { this.service = service; }

	@PostMapping("/devices/{deviceId}/commands/{command:pause|resume|power-on|power-off}")
	ResponseEntity<OperationService.CommandReceipt> command(@PathVariable String deviceId, @PathVariable String command,
			@RequestHeader("Idempotency-Key") UUID key, @RequestBody Map<String, Object> body) {
		requireEmpty(body);
		var result = service.requestCommand(deviceId, command, key);
		return ResponseEntity.accepted().location(URI.create("/api/v1/devices/" + deviceId.trim() + "/commands/" + result.commandId())).body(result);
	}

	@GetMapping("/devices/{deviceId}/commands/{commandId}")
	OperationService.CommandResult getCommand(@PathVariable String deviceId, @PathVariable UUID commandId) {
		return service.getCommand(deviceId, commandId);
	}

	@PostMapping("/hazards/{hazardId}/removal-checks")
	ResponseEntity<OperationService.ActionReceipt> removal(@PathVariable UUID hazardId,
			@RequestHeader("Idempotency-Key") UUID key, @RequestBody Map<String, Object> body) {
		requireEmpty(body);
		var result = service.requestRemovalCheck(hazardId, key);
		return ResponseEntity.accepted().location(URI.create("/api/v1/safety-actions/" + result.actionId())).body(result);
	}

	@GetMapping("/safety-actions/{actionId}")
	OperationService.ActionResult action(@PathVariable UUID actionId) { return service.getAction(actionId); }

	@PostMapping("/hazards/{hazardId}/relocations")
	ResponseEntity<OperationService.ActionReceipt> relocation(@PathVariable UUID hazardId, @RequestHeader("Idempotency-Key") UUID key,
			@RequestBody Map<String, Object> body) {
		if (body.keySet().stream().anyMatch(field -> !field.equals("safeZoneId"))) {
			throw ApiException.validation("지원하지 않는 필드입니다.", Map.of("body", "safeZoneId만 허용합니다."));
		}
		if(body.get("safeZoneId")!=null) throw ApiException.validation("현재 목적지는 ArUco ID 0입니다.",Map.of("safeZoneId","본문은 {}를 사용해 주세요."));
		var result=service.requestRelocation(hazardId,key);
		return ResponseEntity.accepted().location(URI.create("/api/v1/safety-actions/"+result.actionId())).body(result);
	}

	private void requireEmpty(Map<String, Object> body) {
		if (!body.isEmpty()) throw ApiException.validation("요청 본문은 빈 객체여야 합니다.", Map.of("body", "{}를 전달해 주세요."));
	}
}
