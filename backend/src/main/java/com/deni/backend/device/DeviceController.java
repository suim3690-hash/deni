package com.deni.backend.device;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {
	private final DeviceService service;
	public DeviceController(DeviceService service) { this.service = service; }

	@PostMapping
	ResponseEntity<DeviceService.RegisteredDevice> register(@RequestBody RegisterRequest request) {
		var result = service.register(request.childId(), request.deviceId(), request.name());
		return ResponseEntity.created(URI.create("/api/v1/devices/" + result.deviceId() + "/status")).body(result);
	}

	@GetMapping("/{deviceId}/status")
	DeviceService.DeviceStatus getStatus(@PathVariable String deviceId) { return service.getStatus(deviceId); }

	// 데모 프로필을 열면 그 아이가 이 기기의 활성 프로필이 되고, 이후 탐지는 그 아이에게 기록된다.
	@PostMapping("/{deviceId}/active-child")
	DeviceService.DeviceStatus activateChild(@PathVariable String deviceId, @RequestBody ActivateChildRequest request) {
		return service.activateChild(deviceId, request.childId());
	}

	public record RegisterRequest(UUID childId, String deviceId, String name) { }
	public record ActivateChildRequest(UUID childId) { }
}
