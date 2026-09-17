package com.deni.backend.hazard;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/hazards")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class HazardController {

	private final HazardService hazardService;

	public HazardController(HazardService hazardService) {
		this.hazardService = hazardService;
	}

	@GetMapping
	HazardItemsResponse getHazards(@RequestParam String deviceId, @RequestParam HazardStatus status) {
		return new HazardItemsResponse(hazardService.findHazards(deviceId, status));
	}

	@GetMapping("/{hazardId}")
	HazardService.HazardDetailResult getHazard(@PathVariable UUID hazardId) {
		return hazardService.getHazard(hazardId);
	}

	public record HazardItemsResponse(List<HazardService.HazardListItem> items) {
	}
}
