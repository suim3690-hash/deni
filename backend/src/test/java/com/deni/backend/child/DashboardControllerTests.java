package com.deni.backend.child;

import com.deni.backend.hazard.HazardService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardControllerTests {

	@Test
	void returnsStoredChildProfileWithoutInventingUnavailableDeviceData() {
		UUID childId = UUID.randomUUID();
		ChildService childService = mock(ChildService.class);
		HazardService hazardService = mock(HazardService.class);
		when(childService.getDashboardChild(childId)).thenReturn(new ChildService.DashboardChildState(
				childId, "김튼튼", ProfileStatus.APPLIED, GrowthStage.TODDLER, 30));
		when(hazardService.findActiveHazardsForChild(childId)).thenReturn(List.of());

		DashboardController.DashboardResponse response = new DashboardController(childService, hazardService)
				.getDashboard(childId);

		assertEquals(childId, response.child().childId());
		assertEquals("김튼튼", response.child().name());
		assertEquals(ProfileStatus.APPLIED, response.currentProfile().status());
		assertEquals(GrowthStage.TODDLER, response.currentProfile().stage());
		assertEquals(30, response.currentProfile().ageMonths());
		assertNull(response.device());
		assertTrue(response.activeHazards().isEmpty());
		assertNull(response.reportSummary());
	}

	@Test
	void exposesStoredActiveHazardsSoTheHomeScreenCanOpenTheDetail() {
		UUID childId = UUID.randomUUID();
		UUID hazardId = UUID.randomUUID();
		OffsetDateTime detectedAt = OffsetDateTime.parse("2026-09-17T10:14:00+09:00");
		ChildService childService = mock(ChildService.class);
		HazardService hazardService = mock(HazardService.class);
		when(childService.getDashboardChild(childId)).thenReturn(new ChildService.DashboardChildState(
				childId, "김튼튼", ProfileStatus.APPLIED, GrowthStage.TODDLER, 30));
		when(hazardService.findActiveHazardsForChild(childId)).thenReturn(List.of(
				new HazardService.ActiveHazardSummary(hazardId, "레고 브릭", "VERY_HIGH", "거실 러그 위", detectedAt)));

		DashboardController.DashboardResponse response = new DashboardController(childService, hazardService)
				.getDashboard(childId);

		assertEquals(1, response.activeHazards().size());
		DashboardController.HazardSummary hazard = response.activeHazards().getFirst();
		assertEquals(hazardId, hazard.hazardId());
		assertEquals("레고 브릭", hazard.objectName());
		assertEquals("VERY_HIGH", hazard.riskLevel());
		assertEquals("거실 러그 위", hazard.locationLabel());
		assertEquals(detectedAt, hazard.detectedAt());
	}
}
