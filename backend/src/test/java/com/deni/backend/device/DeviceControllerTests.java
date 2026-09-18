package com.deni.backend.device;

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

class DeviceControllerTests {
	private final DeviceService service = mock(DeviceService.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DeviceController(service))
			.setControllerAdvice(new ApiExceptionHandler()).build();

	@Test
	void registrationReturnsCreatedAndUnknownStatus() throws Exception {
		UUID child = UUID.randomUUID();
		var status = new DeviceService.DeviceStatus("robot-1", "로봇", "UNKNOWN", "UNKNOWN", null, null, false);
		when(service.register(child, "robot-1", "로봇")).thenReturn(new DeviceService.RegisteredDevice(child, "robot-1", "로봇", status));
		mvc.perform(post("/api/v1/devices").contentType(MediaType.APPLICATION_JSON)
				.content("{\"childId\":\"" + child + "\",\"deviceId\":\"robot-1\",\"name\":\"로봇\"}"))
				.andExpect(status().isCreated()).andExpect(header().string("Location", "/api/v1/devices/robot-1/status"))
				.andExpect(jsonPath("$.status.connectionState").value("UNKNOWN"))
				.andExpect(jsonPath("$.status.commandsAvailable").value(false));
	}

	@Test
	void statusIsReadableButFrontendCannotPostTelemetry() throws Exception {
		when(service.getStatus("robot-1")).thenReturn(new DeviceService.DeviceStatus("robot-1", "로봇", "UNKNOWN", "UNKNOWN", null, null, false));
		mvc.perform(get("/api/v1/devices/robot-1/status")).andExpect(status().isOk())
				.andExpect(jsonPath("$.deviceId").value("robot-1"));
		mvc.perform(post("/api/v1/devices/robot-1/status").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isMethodNotAllowed());
		verify(service, never()).recordStatus(any());
	}

	@Test
	void invalidUuidAndMissingDevicePreserveErrorContract() throws Exception {
		mvc.perform(post("/api/v1/devices").contentType(MediaType.APPLICATION_JSON)
				.content("{\"childId\":\"bad\",\"deviceId\":\"robot-1\",\"name\":\"로봇\"}"))
				.andExpect(status().isBadRequest());
		when(service.getStatus("missing")).thenThrow(ApiException.notFound("DEVICE_NOT_FOUND", "없는 기기"));
		mvc.perform(get("/api/v1/devices/missing/status")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error.code").value("DEVICE_NOT_FOUND"));
	}
}
