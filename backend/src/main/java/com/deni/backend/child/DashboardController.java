package com.deni.backend.child;

import com.deni.backend.hazard.HazardService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dashboard")
@CrossOrigin(origins = {"http://localhost:5173", "http://127.0.0.1:5173"})
public class DashboardController {

	private final ChildService childService;
	private final HazardService hazardService;

	public DashboardController(ChildService childService, HazardService hazardService) {
		this.childService = childService;
		this.hazardService = hazardService;
	}

	@GetMapping
	DashboardResponse getDashboard(@RequestParam UUID childId) {
		ChildService.DashboardChildState state = childService.getDashboardChild(childId);
		// 기기 등록·명령 API와 리포트 집계는 아직 없으므로 값을 만들어 내지 않고 null로 둔다.
		return new DashboardResponse(
				new ChildSummary(state.childId(), state.name()),
				null,
				new CurrentProfile(state.status(), state.stage(), state.ageMonths()),
				hazardService.findActiveHazardsForChild(childId).stream()
						.map(hazard -> new HazardSummary(hazard.hazardId(), hazard.objectName(),
								hazard.riskLevel(), hazard.locationLabel(), hazard.detectedAt()))
						.toList(),
				null);
	}

	public record DashboardResponse(ChildSummary child, DeviceSummary device, CurrentProfile currentProfile,
			List<HazardSummary> activeHazards, ReportSummary reportSummary) {
	}

	public record ChildSummary(UUID childId, String name) {
	}

	public record CurrentProfile(ProfileStatus status, GrowthStage stage, int ageMonths) {
	}

	public record DeviceSummary(String deviceId, String connectionState, String operationState,
			Integer batteryPercent, OffsetDateTime lastSeenAt) {
	}

	public record HazardSummary(UUID hazardId, String objectName, String riskLevel, String locationLabel,
			OffsetDateTime detectedAt) {
	}

	public record ReportSummary(String reportId, String month, boolean available) {
	}
}
