package com.deni.backend.child;

import com.deni.backend.hazard.HazardService;
import com.deni.backend.device.DeviceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

	private final ChildService childService;
	private final HazardService hazardService;
	private final MonthlyReportService monthlyReportService;
	private final DeviceService deviceService;

	public DashboardController(ChildService childService, HazardService hazardService,
			MonthlyReportService monthlyReportService, DeviceService deviceService) {
		this.childService = childService;
		this.hazardService = hazardService;
		this.monthlyReportService = monthlyReportService;
		this.deviceService = deviceService;
	}

	@GetMapping
	public DashboardResponse getDashboard(@RequestParam UUID childId) {
		ChildService.DashboardChildState state = childService.getDashboardChild(childId);
		// 등록된 기기는 저장 상태를 제공한다. 미등록 기기는 null, 미보고 상태는 UNKNOWN이다.
		return new DashboardResponse(
				new ChildSummary(state.childId(), state.name()),
				deviceService.findStatusForChild(childId),
				new CurrentProfile(state.status(), state.stage(), state.ageMonths()),
				hazardService.findActiveHazardsForChild(childId).stream()
						.map(hazard -> new HazardSummary(hazard.hazardId(), hazard.objectName(),
						hazard.riskLevel(), hazard.locationLabel(), hazard.detectedAt(), hazard.acknowledgedAt()))
						.toList(),
				monthlyReportService.getCurrentSummary(childId));
	}

	public record DashboardResponse(ChildSummary child, DeviceService.DeviceStatus device, CurrentProfile currentProfile,
			List<HazardSummary> activeHazards, MonthlyReportService.ReportSummary reportSummary) {
	}

	public record ChildSummary(UUID childId, String name) {
	}

	public record CurrentProfile(ProfileStatus status, GrowthStage stage, int ageMonths) {
	}

	public record HazardSummary(UUID hazardId, String objectName, String riskLevel, String locationLabel,
			OffsetDateTime detectedAt, OffsetDateTime acknowledgedAt) {
	}

}
