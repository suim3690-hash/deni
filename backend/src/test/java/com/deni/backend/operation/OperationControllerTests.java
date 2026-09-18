package com.deni.backend.operation;

import com.deni.backend.common.ApiException;
import com.deni.backend.common.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OperationControllerTests {
	private final OperationService service = mock(OperationService.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new OperationController(service))
			.setControllerAdvice(new ApiExceptionHandler()).build();

	@Test
	void acceptsPauseReceiptAndSerializesUnknownConfirmation() throws Exception {
		UUID key = UUID.randomUUID();
		UUID id = UUID.randomUUID();
		when(service.requestCommand("robot-1", "pause", key)).thenReturn(new OperationService.CommandReceipt(id, "REQUESTED", "NOT_CONNECTED"));
		mvc.perform(post("/api/v1/devices/robot-1/commands/pause").header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.commandId").value(id.toString()))
				.andExpect(jsonPath("$.deliveryState").value("NOT_CONNECTED"));
		when(service.getCommand("robot-1", id)).thenReturn(new OperationService.CommandResult(id, "REQUESTED", "UNKNOWN", null, null, "NOT_CONNECTED"));
		mvc.perform(get("/api/v1/devices/robot-1/commands/" + id)).andExpect(status().isOk())
				.andExpect(jsonPath("$.deviceOperationState").value("UNKNOWN"))
				.andExpect(jsonPath("$.confirmedAt").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	void acceptsRemovalIntentButNeverClaimsCheckingOrCompleted() throws Exception {
		UUID key = UUID.randomUUID();
		UUID hazard = UUID.randomUUID();
		UUID action = UUID.randomUUID();
		when(service.requestRemovalCheck(hazard, key)).thenReturn(new OperationService.ActionReceipt(action, "DIRECT_REMOVAL_CHECK", "UNKNOWN", "NOT_CONNECTED"));
		mvc.perform(post("/api/v1/hazards/" + hazard + "/removal-checks").header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/v1/safety-actions/" + action))
				.andExpect(jsonPath("$.status").value("UNKNOWN"));
		when(service.getAction(action)).thenReturn(new OperationService.ActionResult(action, hazard, "DIRECT_REMOVAL_CHECK", "UNKNOWN", null, "PENDING", "UNKNOWN", null, null, "NOT_CONNECTED"));
		mvc.perform(get("/api/v1/safety-actions/" + action)).andExpect(status().isOk())
				.andExpect(jsonPath("$.hazardPresent").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.treatmentStatus").value("PENDING"));
	}

	@Test
	void requiresUuidKeyAndEmptyObjectAndRejectsUnknownBodyFields() throws Exception {
		String path = "/api/v1/devices/robot-1/commands/pause";
		mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post(path).header("Idempotency-Key", "bad").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post(path).header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUCCEEDED\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post(path).header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("[]"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	void resumeAndRelocationReturnExplicitConflictWithoutSuccessResponse() throws Exception {
		UUID key = UUID.randomUUID();
		UUID hazard = UUID.randomUUID();
		when(service.requestCommand("robot-1", "resume", key)).thenThrow(ApiException.conflict("SAFETY_CONFIRMATION_REQUIRED", "안전 확인 없음"));
		doThrow(ApiException.conflict("RELOCATION_NOT_CONFIGURED", "안전 위치 없음")).when(service).rejectRelocation(hazard, key);
		mvc.perform(post("/api/v1/devices/robot-1/commands/resume").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SAFETY_CONFIRMATION_REQUIRED"));
		mvc.perform(post("/api/v1/hazards/" + hazard + "/relocations").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("RELOCATION_NOT_CONFIGURED"));
	}
}
