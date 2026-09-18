package com.deni.backend.operation;

import com.deni.backend.common.ApiException;
import com.deni.backend.common.IdempotencyGuard;
import com.deni.backend.device.DeviceService;
import com.deni.backend.hazard.HazardService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OperationServiceTests {
	private final OperationRequestRepository repository = mock(OperationRequestRepository.class);
	private final DeviceService devices = mock(DeviceService.class);
	private final HazardService hazards = mock(HazardService.class);
	private final IdempotencyGuard guard = mock(IdempotencyGuard.class);
	private final OperationService service = new OperationService(repository, devices, hazards, guard);
	private final UUID child = UUID.randomUUID();
	private final UUID key = UUID.randomUUID();

	@Test
	void pauseStoresReceiptButDoesNotConfirmOrMutateDeviceState() {
		online("RUNNING");
		when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
		var receipt = service.requestCommand(" robot-1 ", "pause", key);
		assertEquals("REQUESTED", receipt.status());
		assertEquals("NOT_CONNECTED", receipt.deliveryState());
		verify(guard).lock("device", "robot-1");
		verify(repository).saveAndFlush(any());
		verify(devices, never()).recordStatus(any());
	}

	@Test
	void originalRetryReturnsSameCommandEvenAfterDeviceGoesOffline() {
		when(devices.getStatus("robot-1")).thenReturn(new DeviceService.DeviceStatus("robot-1", "로봇", "UNKNOWN", "UNKNOWN", null, null, false));
		var stored = new OperationRequest("robot-1", null, key, "PAUSE", OffsetDateTime.now());
		when(repository.findByDeviceIdAndIdempotencyKey("robot-1", key)).thenReturn(Optional.of(stored));
		assertEquals(stored.getId(), service.requestCommand("robot-1", "pause", key).commandId());
		assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows(ApiException.class,
				() -> service.requestCommand("robot-1", "resume", key)).getCode());
		verify(repository, never()).saveAndFlush(any());
	}

	@Test
	void resumeCannotBeAcceptedWithoutSafetyEvidenceEvenWhenNoHazardsExist() {
		online("PAUSED");
		when(devices.getLinkedChildId("robot-1")).thenReturn(child);
		when(hazards.findActiveHazardsForChild(child)).thenReturn(List.of());
		assertEquals("SAFETY_CONFIRMATION_REQUIRED", assertThrows(ApiException.class,
				() -> service.requestCommand("robot-1", "resume", key)).getCode());
		when(hazards.findActiveHazardsForChild(child)).thenReturn(List.of(new HazardService.ActiveHazardSummary(
				UUID.randomUUID(), "레고", "HIGH", null, OffsetDateTime.now())));
		assertEquals("HAZARD_UNRESOLVED", assertThrows(ApiException.class,
				() -> service.requestCommand("robot-1", "resume", key)).getCode());
		verify(repository, never()).saveAndFlush(any());
	}

	@Test
	void unknownOrOfflineDeviceCannotCreateNewPauseReceipt() {
		for (String connection : List.of("OFFLINE", "UNKNOWN")) {
			when(devices.getStatus("robot-1")).thenReturn(new DeviceService.DeviceStatus("robot-1", "로봇", connection, "UNKNOWN", null, null, false));
			assertEquals("DEVICE_NOT_ONLINE", assertThrows(ApiException.class,
					() -> service.requestCommand("robot-1", "pause", key)).getCode());
		}
		verify(repository, never()).saveAndFlush(any());
	}

	@Test
	void commandLookupIsDeviceScopedAndNeverTreatsCurrentPausedStateAsSuccess() {
		online("PAUSED");
		var stored = new OperationRequest("robot-1", null, key, "PAUSE", OffsetDateTime.now());
		when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
		var result = service.getCommand("robot-1", stored.getId());
		assertEquals("REQUESTED", result.status());
		assertEquals("UNKNOWN", result.deviceOperationState());
		assertNull(result.confirmedAt());
		assertEquals("COMMAND_NOT_FOUND", assertThrows(ApiException.class,
				() -> service.getCommand("other", stored.getId())).getCode());
		assertEquals("SAFETY_ACTION_NOT_FOUND", assertThrows(ApiException.class,
				() -> service.getAction(stored.getId())).getCode());
	}

	@Test
	void removalReceiptStaysUnknownAndDoesNotResolveHazardOrResumeDevice() {
		UUID hazard = hazard();
		online("PAUSED");
		when(devices.getLinkedChildId("robot-1")).thenReturn(child);
		when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
		var receipt = service.requestRemovalCheck(hazard, key);
		assertEquals("UNKNOWN", receipt.status());
		assertEquals("NOT_CONNECTED", receipt.deliveryState());
		verify(hazards).requireActiveAssociation(hazard, child, "robot-1");
		verify(devices, never()).recordStatus(any());
		var stored = new OperationRequest("robot-1", hazard, key, "DIRECT_REMOVAL_CHECK", OffsetDateTime.now());
		when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
		var result = service.getAction(stored.getId());
		assertEquals("PENDING", result.treatmentStatus());
		assertNull(result.hazardPresent());
		assertNull(result.completedAt());
		assertEquals("UNKNOWN", result.deviceOperationState());
	}

	@Test
	void removalRequiresPauseAndValidAssociationAndKeyCannotSwitchTargets() {
		UUID hazard = hazard();
		online("RUNNING");
		assertEquals("DEVICE_NOT_PAUSED", assertThrows(ApiException.class,
				() -> service.requestRemovalCheck(hazard, key)).getCode());
		online("PAUSED");
		when(devices.getLinkedChildId("robot-1")).thenReturn(child);
		doThrow(ApiException.conflict("HAZARD_DEVICE_MISMATCH", "아이 불일치")).when(hazards).requireActiveAssociation(hazard, child, "robot-1");
		assertEquals("HAZARD_DEVICE_MISMATCH", assertThrows(ApiException.class,
				() -> service.requestRemovalCheck(hazard, key)).getCode());
		var stored = new OperationRequest("robot-1", UUID.randomUUID(), key, "DIRECT_REMOVAL_CHECK", OffsetDateTime.now());
		when(repository.findByDeviceIdAndIdempotencyKey("robot-1", key)).thenReturn(Optional.of(stored));
		assertEquals("IDEMPOTENCY_KEY_REUSED", assertThrows(ApiException.class,
				() -> service.requestRemovalCheck(hazard, key)).getCode());
		verify(repository, never()).saveAndFlush(any());
	}

	@Test
	void relocationAndInvalidInputsNeverCreateRequests() {
		UUID hazard = hazard();
		assertEquals("RELOCATION_NOT_CONFIGURED", assertThrows(ApiException.class,
				() -> service.rejectRelocation(hazard, key)).getCode());
		assertThrows(ApiException.class, () -> service.requestCommand("robot-1", "pause", null));
		assertThrows(ApiException.class, () -> service.requestCommand("robot-1", "stop", key));
		assertThrows(ApiException.class, () -> service.requestCommand("robot/a", "pause", key));
		assertThrows(ApiException.class, () -> service.requestRemovalCheck(null, key));
		verify(repository, never()).saveAndFlush(any());
	}

	private void online(String operation) {
		when(devices.getStatus("robot-1")).thenReturn(new DeviceService.DeviceStatus("robot-1", "로봇", "ONLINE", operation, 82, OffsetDateTime.now(), false));
	}
	private UUID hazard() {
		UUID id = UUID.randomUUID();
		when(hazards.getHazard(id)).thenReturn(new HazardService.HazardDetailResult(id, "robot-1", "ACTIVE",
				new HazardService.DetectedObject("TOY_PART", "레고"), "HIGH", null, OffsetDateTime.now(), null, null, "PAUSED"));
		return id;
	}
}
