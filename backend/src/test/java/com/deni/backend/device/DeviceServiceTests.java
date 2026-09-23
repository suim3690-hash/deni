package com.deni.backend.device;

import com.deni.backend.child.ChildService;
import com.deni.backend.common.ApiException;
import com.deni.backend.common.IdempotencyGuard;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DeviceServiceTests {
	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-18T12:00:00+09:00");
	private final DeviceRepository devices = mock(DeviceRepository.class);
	private final ChildService children = mock(ChildService.class);
	private final IdempotencyGuard guard = mock(IdempotencyGuard.class);
	private final DeviceService service = serviceAt(NOW.toInstant());
	private final UUID childId = UUID.randomUUID();

	private DeviceService serviceAt(Instant instant) {
		return new DeviceService(devices, children, guard, 300, Clock.fixed(instant, ZoneOffset.UTC));
	}

	@Test
	void registrationUsesNormalizedInputsAndDoesNotInventDeviceStatus() {
		when(devices.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
		var result = service.register(childId, " robot-1 ", " 로봇 ");
		assertEquals("robot-1", result.deviceId());
		assertEquals("로봇", result.name());
		assertEquals("UNKNOWN", result.status().connectionState());
		assertEquals("UNKNOWN", result.status().operationState());
		assertNull(result.status().batteryPercent());
		assertNull(result.status().lastSeenAt());
		assertFalse(result.status().commandsAvailable());
		verify(children).requireRegisteredChild(childId);
		var order = inOrder(guard);
		order.verify(guard).lock("device", "robot-1");
		order.verify(guard).lock("child-device", childId.toString());
	}

	@Test
	void sameRegistrationReturnsExistingAndOnlyTheNameIsFixed() {
		Device existing = new Device("robot-1", childId, "로봇", NOW);
		when(devices.findById("robot-1")).thenReturn(Optional.of(existing));
		assertEquals(childId, service.register(childId, "robot-1", "로봇").childId());
		assertEquals("DEVICE_ALREADY_REGISTERED", assertThrows(ApiException.class,
				() -> service.register(childId, "robot-1", "다른 이름")).getCode());
		verify(devices, never()).saveAndFlush(any());
	}

	// 데모 프로필 여러 개가 로봇 한 대를 같이 쓴다. 마지막으로 연결한 프로필이 탐지를 받는다.
	@Test
	void anotherChildJoinsTheSameDeviceAndBecomesTheActiveProfile() {
		var db = mock(org.springframework.jdbc.core.JdbcTemplate.class);
		org.springframework.test.util.ReflectionTestUtils.setField(service, "jdbc", db);
		Device existing = new Device("robot-1", childId, "로봇", NOW);
		when(devices.findById("robot-1")).thenReturn(Optional.of(existing));
		UUID second = UUID.randomUUID();

		service.register(second, "robot-1", "로봇");

		verify(db).update(contains("INSERT INTO device_children"), eq("robot-1"), eq(second), any());
		assertEquals(second, existing.getChildId());
		assertEquals(second, service.getLinkedChildId("robot-1"));
	}

	@Test
	void onlyALinkedChildCanBecomeActiveAndTheSwitchIsWhatDetectionsFollow() {
		var db = mock(org.springframework.jdbc.core.JdbcTemplate.class);
		org.springframework.test.util.ReflectionTestUtils.setField(service, "jdbc", db);
		UUID second = UUID.randomUUID();
		Device existing = new Device("robot-1", second, "로봇", NOW);
		when(devices.findById("robot-1")).thenReturn(Optional.of(existing));

		assertEquals("CHILD_DEVICE_NOT_LINKED", assertThrows(ApiException.class,
				() -> service.activateChild("robot-1", childId)).getCode());
		assertThrows(ApiException.class, () -> service.activateChild("robot-1", null));

		when(db.queryForList(anyString(), eq(UUID.class), any())).thenReturn(java.util.List.of(childId, second));
		service.activateChild("robot-1", childId);
		assertEquals(childId, existing.getChildId());
		assertEquals(childId, service.getLinkedChildId("robot-1"));
	}

	@Test
	void secondDeviceForSameChildAndMissingChildAreRejected() {
		when(devices.findByChildId(childId)).thenReturn(Optional.of(new Device("other", childId, "로봇", NOW)));
		// 한 아이가 여러 기기에 붙는 것은 여전히 막는다. 공유되는 쪽은 기기이지 아이가 아니다.
		assertEquals("CHILD_DEVICE_ALREADY_LINKED", assertThrows(ApiException.class,
				() -> service.register(childId, "robot-1", "로봇")).getCode());
		doThrow(ApiException.notFound("없는 아이")).when(children).requireRegisteredChild(childId);
		assertEquals("CHILD_NOT_FOUND", assertThrows(ApiException.class,
				() -> service.register(childId, "robot-1", "로봇")).getCode());
		verify(devices, never()).saveAndFlush(any());
	}

	// 기기와 서버 시계는 밀리초 단위로 어긋난다. 그만큼 앞선 보고를 버리면 상태가 끊긴다.
	@Test
	void aReportSlightlyAheadOfTheServerClockIsStoredAtServerTime() {
		Device device = device();
		when(devices.findById("robot-1")).thenReturn(Optional.of(device));
		service.recordStatus(new DeviceService.StatusInput("robot-1", "ONLINE", "RUNNING", 80, NOW.plusNanos(500_000)));
		assertEquals(NOW.toInstant(), device.getLastReportedAt().toInstant());

		assertEquals("VALIDATION_ERROR", assertThrows(ApiException.class, () -> service.recordStatus(
				new DeviceService.StatusInput("robot-1", "ONLINE", "RUNNING", 80, NOW.plusSeconds(5)))).getCode());
		assertEquals("VALIDATION_ERROR", assertThrows(ApiException.class, () -> service.recordStatus(
				new DeviceService.StatusInput("robot-1", "ONLINE", "RUNNING", 80, null))).getCode());
	}

	@Test
	void registrationValidatesBeforeStorageAndDoesNotAllowPathCharacters() {
		assertThrows(ApiException.class, () -> service.register(null, "robot-1", "로봇"));
		for (String id : new String[] {"", "robot/a", "../a", "한글", "a".repeat(101)}) {
			assertThrows(ApiException.class, () -> service.register(childId, id, "로봇"));
		}
		assertThrows(ApiException.class, () -> service.register(childId, "robot-1", " "));
		assertThrows(ApiException.class, () -> service.register(childId, "robot-1", "a".repeat(101)));
		verifyNoInteractions(devices, guard, children);
	}

	@Test
	void statusDistinguishesMissingDeviceFromMissingChildLink() {
		assertNull(service.findStatusForChild(childId));
		assertEquals("DEVICE_NOT_FOUND", assertThrows(ApiException.class, () -> service.getStatus("robot-1")).getCode());
	}

	@Test
	void statusReportStoresServerReceiptTimeAndFreshDataThenExpiresWithoutWriting() {
		Device device = device();
		OffsetDateTime reported = NOW.minusSeconds(10);
		var result = service.recordStatus(input("ONLINE", "RUNNING", 82, reported));
		assertEquals("ONLINE", result.connectionState());
		assertEquals("RUNNING", result.operationState());
		assertEquals(82, result.batteryPercent());
		assertEquals(NOW.toInstant(), result.lastSeenAt().toInstant());
		assertEquals(reported, device.getLastReportedAt());
		assertFalse(result.commandsAvailable());
		assertEquals("ONLINE", serviceAt(NOW.plusSeconds(289).toInstant()).getStatus("robot-1").connectionState());
		var expired = serviceAt(NOW.plusSeconds(290).toInstant()).getStatus("robot-1");
		assertEquals("UNKNOWN", expired.connectionState());
		assertEquals("UNKNOWN", expired.operationState());
		assertNull(expired.batteryPercent());
		assertEquals("ONLINE", device.getConnectionState());
		verify(devices, never()).save(any());
	}

	@Test
	void duplicateAndOlderReportDoNotRefreshLastSeenOrOverwriteLatestState() {
		Device device = device();
		service.recordStatus(input("ONLINE", "PAUSED", 50, NOW.minusSeconds(20)));
		var later = serviceAt(NOW.plusSeconds(30).toInstant());
		later.recordStatus(input("ONLINE", "PAUSED", 50, NOW.minusSeconds(20).withOffsetSameInstant(ZoneOffset.UTC)));
		later.recordStatus(input("OFFLINE", "UNKNOWN", null, NOW.minusSeconds(21)));
		assertEquals(NOW.toInstant(), device.getLastSeenAt().toInstant());
		assertEquals("PAUSED", device.getOperationState());
		assertEquals("DEVICE_STATUS_REPORT_REUSED", assertThrows(ApiException.class,
				() -> later.recordStatus(input("ONLINE", "RUNNING", 50, NOW.minusSeconds(20)))).getCode());
		later.recordStatus(input("ONLINE", "RUNNING", 49, NOW.plusSeconds(5)));
		assertEquals("RUNNING", device.getOperationState());
	}

	@Test
	void delayedReportsCannotMakeDeviceOnlineAndOfflineDoesNotExposeRunningState() {
		device();
		assertEquals("UNKNOWN", service.recordStatus(input("ONLINE", "RUNNING", 100, NOW.minusSeconds(301))).connectionState());
		var offline = service.recordStatus(input("OFFLINE", "RUNNING", 0, NOW.minusSeconds(1)));
		assertEquals("OFFLINE", offline.connectionState());
		assertEquals("UNKNOWN", offline.operationState());
		assertEquals(0, offline.batteryPercent());
	}

	@Test
	void invalidReportIsRejectedBeforeTakingLockOrReadingDevice() {
		assertThrows(ApiException.class, () -> service.recordStatus(null));
		assertThrows(ApiException.class, () -> service.recordStatus(input(null, "UNKNOWN", null, NOW)));
		assertThrows(ApiException.class, () -> service.recordStatus(input("ONLINE", "INVALID", null, NOW)));
		assertThrows(ApiException.class, () -> service.recordStatus(input("ONLINE", "RUNNING", -1, NOW)));
		assertThrows(ApiException.class, () -> service.recordStatus(input("ONLINE", "RUNNING", 101, NOW)));
		assertThrows(ApiException.class, () -> service.recordStatus(input("ONLINE", "RUNNING", null, null)));
		assertThrows(ApiException.class, () -> service.recordStatus(input("ONLINE", "RUNNING", null, NOW.plusSeconds(1))));
		assertThrows(IllegalArgumentException.class, () -> new DeviceService(devices, children, guard, 0, Clock.systemUTC()));
		verifyNoInteractions(devices, guard, children);
	}

	private Device device() {
		Device device = new Device("robot-1", childId, "로봇", NOW);
		when(devices.findById("robot-1")).thenReturn(Optional.of(device));
		return device;
	}

	private DeviceService.StatusInput input(String connection, String operation, Integer battery, OffsetDateTime at) {
		return new DeviceService.StatusInput("robot-1", connection, operation, battery, at);
	}
}
