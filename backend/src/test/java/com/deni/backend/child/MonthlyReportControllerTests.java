package com.deni.backend.child;

import com.deni.backend.common.ApiException;
import com.deni.backend.common.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MonthlyReportControllerTests {

	private final MonthlyReportService service = mock(MonthlyReportService.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MonthlyReportController(service))
			.setControllerAdvice(new ApiExceptionHandler()).build();

	@Test
	void serializesEmptyReportAndUnavailableMetrics() throws Exception {
		UUID childId = UUID.randomUUID();
		when(service.getMonthlyReport(childId, "2026-09")).thenReturn(new MonthlyReportService.MonthlyReport(
				"report_" + childId + "_2026-09", childId, "2026-09", "김튼튼", null,
				new MonthlyReportService.Summary(0, null, null), List.of(), List.of(),
				new MonthlyReportService.NextStagePreview(null, "다음 단계 안내"), null, List.of()));

		mvc.perform(get("/api/v1/reports/monthly").param("childId", childId.toString()).param("month", "2026-09"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.childId").value(childId.toString()))
				.andExpect(jsonPath("$.summary.detectionCount").value(0))
				.andExpect(jsonPath("$.summary.avoidanceRatePercent").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.summary.safeCleanedAreaSquareMeters").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.stageChange").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.stageChanges").isEmpty())
				.andExpect(jsonPath("$.detectionsByObject").isEmpty())
				.andExpect(jsonPath("$.criteriaChanges").isEmpty());
	}

	@Test
	void rejectsMissingQueryParametersAndInvalidUuid() throws Exception {
		mvc.perform(get("/api/v1/reports/monthly").param("month", "2026-09"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		mvc.perform(get("/api/v1/reports/monthly").param("childId", UUID.randomUUID().toString()))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/reports/monthly").param("childId", "bad-id").param("month", "2026-09"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	void preservesServiceValidationAndNotFoundErrors() throws Exception {
		UUID childId = UUID.randomUUID();
		when(service.getMonthlyReport(childId, "2026-13"))
				.thenThrow(ApiException.validation("조회 월 오류", Map.of("month", "유효한 월을 입력해 주세요.")));
		when(service.getMonthlyReport(childId, "2026-09")).thenThrow(ApiException.notFound("없는 아이"));

		mvc.perform(get("/api/v1/reports/monthly").param("childId", childId.toString()).param("month", "2026-13"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.fieldErrors.month").exists())
				.andExpect(header().exists("X-Request-Id"));
		mvc.perform(get("/api/v1/reports/monthly").param("childId", childId.toString()).param("month", "2026-09"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("CHILD_NOT_FOUND"));
	}
}
