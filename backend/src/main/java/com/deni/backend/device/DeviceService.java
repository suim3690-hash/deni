package com.deni.backend.device;

import com.deni.backend.child.ChildService;
import com.deni.backend.common.ApiException;
import com.deni.backend.common.IdempotencyGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class DeviceService {
	@Autowired(required = false)
	private DeviceChannel channel;
	@Autowired(required = false)
	private JdbcTemplate jdbc;
	private static final Set<String> CONNECTIONS = Set.of("ONLINE", "OFFLINE", "UNKNOWN");
	private static final Set<String> OPERATIONS = Set.of("RUNNING", "PAUSED", "STOPPING", "RESUMING", "READY_TO_RESUME", "UNKNOWN");
	private final DeviceRepository devices;
	private final ChildService children;
	private final IdempotencyGuard guard;
	private final Duration statusMaxAge;
	private final Duration controlStateMaxAge;
	private final Clock clock;

	@Autowired
	public DeviceService(DeviceRepository devices, ChildService children, IdempotencyGuard guard,
			@Value("${device.status-max-age-seconds:300}") long statusMaxAgeSeconds,
			@Value("${robot.control-status-max-age-seconds:10}") long controlStateMaxAgeSeconds) {
		this(devices, children, guard, statusMaxAgeSeconds, controlStateMaxAgeSeconds,
				Clock.system(ZoneId.of("Asia/Seoul")));
	}

	DeviceService(DeviceRepository devices, ChildService children, IdempotencyGuard guard,
			long statusMaxAgeSeconds, Clock clock) {
		this(devices, children, guard, statusMaxAgeSeconds, 10, clock);
	}

	DeviceService(DeviceRepository devices, ChildService children, IdempotencyGuard guard,
			long statusMaxAgeSeconds, long controlStateMaxAgeSeconds, Clock clock) {
		if (statusMaxAgeSeconds <= 0 || controlStateMaxAgeSeconds <= 0) {
			throw new IllegalArgumentException("Device status max ages must be positive");
		}
		this.devices = devices;
		this.children = children;
		this.guard = guard;
		this.statusMaxAge = Duration.ofSeconds(statusMaxAgeSeconds);
		this.controlStateMaxAge = Duration.ofSeconds(controlStateMaxAgeSeconds);
		this.clock = clock;
	}

	@Transactional
	public RegisteredDevice register(UUID childId, String deviceId, String name) {
		if (childId == null) throw validation("childId", "아이 ID는 필수입니다.");
		String id = deviceId(deviceId);
		String normalizedName = text(name, "name");
		children.requireRegisteredChild(childId);
		// 모든 등록은 기기 → 아이 순서로 잠근다. JVM 여러 개에서도 DB UNIQUE 제약을 보호한다.
		guard.lock("device", id);
		guard.lock("child-device", childId.toString());
		Device existing = devices.findById(id).orElse(null);
		if (existing != null) {
			if (!existing.getChildId().equals(childId) || !existing.getName().equals(normalizedName)) {
				throw ApiException.conflict("DEVICE_ALREADY_REGISTERED", "이미 등록된 기기 ID의 아이 또는 이름을 바꿀 수 없습니다.");
			}
			return registration(existing);
		}
		if (devices.findByChildId(childId).isPresent()) {
			throw ApiException.conflict("CHILD_DEVICE_ALREADY_LINKED", "해당 아이에게 이미 기기가 연결되어 있습니다.");
		}
		return registration(devices.saveAndFlush(new Device(id, childId, normalizedName, OffsetDateTime.now(clock))));
	}

	@Transactional(readOnly = true)
	public DeviceStatus getStatus(String deviceId) {
		return status(findDevice(deviceId(deviceId)));
	}

	@Transactional(readOnly = true)
	public DeviceStatus findStatusForChild(UUID childId) {
		return devices.findByChildId(childId).map(this::status).orElse(null);
	}

	@Transactional(readOnly = true)
	public UUID getLinkedChildId(String deviceId) {
		return findDevice(deviceId(deviceId)).getChildId();
	}

	/** 향후 인증된 기기 메시지 소비자만 호출한다. 프론트용 상태 입력 API는 노출하지 않는다. */
	@Transactional
	public DeviceStatus recordStatus(StatusInput input) {
		if (input == null) throw validation("status", "기기 상태는 필수입니다.");
		String id = deviceId(input.deviceId());
		if (!CONNECTIONS.contains(Objects.requireNonNullElse(input.connectionState(), ""))) {
			throw validation("connectionState", "ONLINE, OFFLINE, UNKNOWN 중 하나여야 합니다.");
		}
		if (!OPERATIONS.contains(Objects.requireNonNullElse(input.operationState(), ""))) {
			throw validation("operationState", "지원하지 않는 운행 상태입니다.");
		}
		if (input.batteryPercent() != null && (input.batteryPercent() < 0 || input.batteryPercent() > 100)) {
			throw validation("batteryPercent", "배터리는 0~100 사이여야 합니다.");
		}
		OffsetDateTime now = OffsetDateTime.now(clock);
		if (input.reportedAt() == null || input.reportedAt().isAfter(now)) {
			throw validation("reportedAt", "시간대를 포함한 현재 이전의 보고 시각이 필요합니다.");
		}
		// PostgreSQL TIMESTAMPTZ와 동일한 마이크로초 정밀도로 재전송을 비교한다.
		OffsetDateTime reportedAt = input.reportedAt().truncatedTo(ChronoUnit.MICROS);
		guard.lock("device", id);
		Device device = findDevice(id);
		if (device.getLastReportedAt() != null) {
			int order = reportedAt.toInstant().compareTo(device.getLastReportedAt().toInstant());
			if (order < 0) return status(device);
			if (order == 0) {
				if (!device.getConnectionState().equals(input.connectionState())
						|| !device.getOperationState().equals(input.operationState())
						|| !Objects.equals(device.getBatteryPercent(), input.batteryPercent())) {
					throw ApiException.conflict("DEVICE_STATUS_REPORT_REUSED", "같은 보고 시각에 다른 상태를 저장할 수 없습니다.");
				}
				return status(device); // 재전송은 lastSeenAt을 갱신하지 않는다.
			}
		}
		device.recordStatus(input.connectionState(), input.operationState(), input.batteryPercent(), reportedAt,
				now.truncatedTo(ChronoUnit.MICROS));
		return status(device);
	}

	private Device findDevice(String id) {
		return devices.findById(id).orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "등록된 기기를 찾을 수 없습니다."));
	}

	private RegisteredDevice registration(Device device) {
		return new RegisteredDevice(device.getChildId(), device.getId(), device.getName(), status(device));
	}

	private DeviceStatus status(Device device) {
		OffsetDateTime now = OffsetDateTime.now(clock);
		boolean fresh = device.getLastReportedAt() != null && device.getLastSeenAt() != null
				&& !device.getLastReportedAt().isAfter(now) && !device.getLastSeenAt().isAfter(now)
				&& Duration.between(device.getLastReportedAt(), now).compareTo(statusMaxAge) < 0
				&& Duration.between(device.getLastSeenAt(), now).compareTo(statusMaxAge) < 0;
		boolean commandsAvailable = fresh && device.getConnectionState().equals("ONLINE")
				&& channel != null && channel.connected(device.getId()) && hasControllableMovement(device.getId());
		return new DeviceStatus(device.getId(), device.getName(), fresh ? device.getConnectionState() : "UNKNOWN",
				fresh && device.getConnectionState().equals("ONLINE") ? device.getOperationState() : "UNKNOWN",
				fresh ? device.getBatteryPercent() : null, device.getLastSeenAt(), commandsAvailable);
	}

	private boolean hasControllableMovement(String deviceId) {
		if (jdbc == null) return false;
		Boolean available = jdbc.queryForObject("""
				SELECT EXISTS (
				    SELECT 1 FROM robot_live_state
				    WHERE device_id = ? AND movement_state <> 'UNKNOWN'
				      AND sampled_at <= clock_timestamp() AND received_at <= clock_timestamp()
				      AND sampled_at > clock_timestamp() - (? * INTERVAL '1 second')
				      AND received_at > clock_timestamp() - (? * INTERVAL '1 second')
				)
				""", Boolean.class, deviceId, controlStateMaxAge.toSeconds(), controlStateMaxAge.toSeconds());
		return Boolean.TRUE.equals(available);
	}

	private String text(String value, String field) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.isEmpty() || normalized.length() > 100) throw validation(field, "1~100자로 입력해 주세요.");
		return normalized;
	}

	private String deviceId(String value) {
		String id = text(value, "deviceId");
		if (!id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) {
			throw validation("deviceId", "영문·숫자로 시작하고 영문·숫자·점·밑줄·콜론·하이픈만 사용할 수 있습니다.");
		}
		return id;
	}

	private ApiException validation(String field, String message) {
		return ApiException.validation("기기 입력값을 확인해 주세요.", Map.of(field, message));
	}

	public record RegisteredDevice(UUID childId, String deviceId, String name, DeviceStatus status) { }
	public record DeviceStatus(String deviceId, String name, String connectionState, String operationState,
			Integer batteryPercent, OffsetDateTime lastSeenAt, boolean commandsAvailable) { }
	public record StatusInput(String deviceId, String connectionState, String operationState,
			Integer batteryPercent, OffsetDateTime reportedAt) { }
}
