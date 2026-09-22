package com.deni.backend.device;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DetectionUploadRiskTests {

	@Test
	void swallowRiskFollowsGrowthStage() {
		assertEquals("HIGH", DetectionUploadService.riskForStage("SWALLOW", "INFANT"));
		assertEquals("VERY_HIGH", DetectionUploadService.riskForStage("SWALLOW", "TODDLER"));
		assertEquals("MEDIUM", DetectionUploadService.riskForStage("SWALLOW", "ACTIVE_CHILD"));
	}

	@Test
	void livingRiskRemainsUnchanged() {
		assertEquals("HIGH", DetectionUploadService.riskForStage("LIVING", "INFANT"));
		assertEquals("HIGH", DetectionUploadService.riskForStage("LIVING", "TODDLER"));
		assertEquals("VERY_HIGH", DetectionUploadService.riskForStage("LIVING", "ACTIVE_CHILD"));
	}
}
