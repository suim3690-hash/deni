package com.deni.backend.operation;

import com.deni.backend.common.ApiException;
import com.deni.backend.common.IdempotencyGuard;
import com.deni.backend.device.DeviceService;
import com.deni.backend.hazard.HazardService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

@Service
public class OperationService {
	@org.springframework.beans.factory.annotation.Autowired(required = false)
	private org.springframework.jdbc.core.JdbcTemplate deliveryDb;
	private final OperationRequestRepository requests;
	private final DeviceService devices;
	private final HazardService hazards;
	private final IdempotencyGuard guard;

	public OperationService(OperationRequestRepository requests, DeviceService devices, HazardService hazards,
			IdempotencyGuard guard) {
		this.requests = requests;
		this.devices = devices;
		this.hazards = hazards;
		this.guard = guard;
	}

	@Transactional
	public CommandReceipt requestCommand(String deviceId, String command, UUID key) {
		requireKey(key);
		if (!"pause".equals(command) && !"resume".equals(command)) {
			throw ApiException.validation("지원하지 않는 명령입니다.", Map.of("command", "pause 또는 resume만 지원합니다."));
		}
		String id = normalizedDeviceId(deviceId);
		guard.lock("device", id);
		var device = devices.getStatus(id);
		String kind = command.equals("pause") ? "PAUSE" : "RESUME";
		OperationRequest existing = replay(id, key, kind, null);
		if (existing != null) return new CommandReceipt(existing.getId(), existing.getStatus(), deliveryState(existing.getId()));
		if (command.equals("resume")) {
			if (!hazards.findActiveHazardsForChild(devices.getLinkedChildId(id)).isEmpty()) {
				throw ApiException.conflict("HAZARD_UNRESOLVED", "미처리 위험물이 있어 청소를 재개할 수 없습니다.");
			}
			throw ApiException.conflict("SAFETY_CONFIRMATION_REQUIRED", "안전 확인과 기기 재개 연동이 구현되기 전에는 재개 요청을 접수할 수 없습니다.");
		}
		requireOnline(device);
		OperationRequest saved = requests.saveAndFlush(new OperationRequest(id, null, key, kind, now()));
		if (device.commandsAvailable() && deliveryDb != null) {
			deliveryDb.update("INSERT INTO device_command_delivery(command_id,device_id,expires_at) VALUES (?,?,?)",
					saved.getId(),id,now().plusSeconds(10));
		}
		return new CommandReceipt(saved.getId(), saved.getStatus(), deliveryState(saved.getId()));
	}

	@Transactional(readOnly = true)
	public CommandResult getCommand(String deviceId, UUID commandId) {
		String id = normalizedDeviceId(deviceId);
		devices.getStatus(id);
		OperationRequest request = findRequest(commandId, "COMMAND_NOT_FOUND");
		if (!request.getDeviceId().equals(id) || !request.getKind().equals("PAUSE")) {
			throw ApiException.notFound("COMMAND_NOT_FOUND", "해당 기기의 명령을 찾을 수 없습니다.");
		}
		// 기기 현재 상태가 PAUSED여도 이 명령의 성공 증거가 아니므로 확인 결과는 UNKNOWN이다.
		if (deliveryDb != null) {
			var result = deliveryDb.query("SELECT status,completed_at FROM device_command_delivery WHERE command_id=?",
					(rs,n) -> new CommandResult(request.getId(),
							java.util.Set.of("QUEUED","SENT","DELIVERED").contains(rs.getString("status")) ? "REQUESTED" : rs.getString("status"),
							rs.getString("status").equals("SUCCEEDED") ? "PAUSED" : "UNKNOWN",
							rs.getObject("completed_at", OffsetDateTime.class), request.getCreatedAt(),rs.getString("status")),commandId);
			if (!result.isEmpty()) return result.getFirst();
		}
		return new CommandResult(request.getId(), request.getStatus(), "UNKNOWN", null,
				request.getCreatedAt(), "NOT_CONNECTED");
	}

	@Transactional
	public ActionReceipt requestRemovalCheck(UUID hazardId, UUID key) {
		requireKey(key);
		if (hazardId == null) throw ApiException.validation("위험 ID는 필수입니다.", Map.of("hazardId", "필수입니다."));
		var hazard = hazards.getHazard(hazardId);
		String id = normalizedDeviceId(hazard.deviceId());
		guard.lock("device", id);
		var device = devices.getStatus(id);
		OperationRequest existing = replay(id, key, "DIRECT_REMOVAL_CHECK", hazardId);
		if (existing != null) return new ActionReceipt(existing.getId(), existing.getKind(), existing.getStatus(), "NOT_CONNECTED");
		hazards.requireActiveAssociation(hazardId, devices.getLinkedChildId(id), id);
		requireOnline(device);
		if (!device.operationState().equals("PAUSED")) {
			throw ApiException.conflict("DEVICE_NOT_PAUSED", "일시정지 상태가 확인된 기기만 재확인 요청을 접수할 수 있습니다.");
		}
		OperationRequest saved = requests.saveAndFlush(new OperationRequest(id, hazardId, key, "DIRECT_REMOVAL_CHECK", now()));
		return new ActionReceipt(saved.getId(), saved.getKind(), saved.getStatus(), "NOT_CONNECTED");
	}

	@Transactional(readOnly = true)
	public ActionResult getAction(UUID actionId) {
		OperationRequest request = findRequest(actionId, "SAFETY_ACTION_NOT_FOUND");
		if (!request.getKind().equals("DIRECT_REMOVAL_CHECK")) {
			throw ApiException.notFound("SAFETY_ACTION_NOT_FOUND", "안전 처리 요청을 찾을 수 없습니다.");
		}
		return new ActionResult(request.getId(), request.getHazardId(), request.getKind(), request.getStatus(),
				null, "PENDING", "UNKNOWN", null, request.getCreatedAt(), "NOT_CONNECTED");
	}

	@Transactional(readOnly = true)
	public void rejectRelocation(UUID hazardId, UUID key) {
		requireKey(key);
		if (hazardId == null) throw ApiException.validation("위험 ID는 필수입니다.", Map.of("hazardId", "필수입니다."));
		hazards.getHazard(hazardId);
		throw ApiException.conflict("RELOCATION_NOT_CONFIGURED", "이송 가능한 물체·안전 위치·기기 전달 계약이 확정되지 않았습니다.");
	}

	private OperationRequest replay(String deviceId, UUID key, String kind, UUID hazardId) {
		OperationRequest existing = requests.findByDeviceIdAndIdempotencyKey(deviceId, key).orElse(null);
		if (existing != null && !existing.matches(kind, hazardId)) {
			throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "동일 기기의 요청 식별키를 다른 명령 또는 위험 건에 재사용할 수 없습니다.");
		}
		return existing;
	}

	private OperationRequest findRequest(UUID id, String code) {
		if (id == null) throw ApiException.validation("요청 ID는 필수입니다.", Map.of("requestId", "필수입니다."));
		return requests.findById(id).orElseThrow(() -> ApiException.notFound(code, "요청 기록을 찾을 수 없습니다."));
	}
	private void requireKey(UUID key) {
		if (key == null) throw ApiException.validation("요청 식별키는 필수입니다.", Map.of("Idempotency-Key", "UUID가 필요합니다."));
	}
	private String normalizedDeviceId(String id) {
		if (id == null || !id.trim().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) {
			throw ApiException.validation("기기 ID를 확인해 주세요.", Map.of("deviceId", "지원하지 않는 기기 ID입니다."));
		}
		return id.trim();
	}
	private void requireOnline(DeviceService.DeviceStatus device) {
		if (!device.connectionState().equals("ONLINE")) {
			throw ApiException.conflict("DEVICE_NOT_ONLINE", "최근 온라인 보고가 있는 기기만 요청을 접수할 수 있습니다.");
		}
	}
	private OffsetDateTime now() { return OffsetDateTime.now(ZoneId.of("Asia/Seoul")); }
	private String deliveryState(UUID id) {
		if (deliveryDb == null) return "NOT_CONNECTED";
		var rows=deliveryDb.queryForList("SELECT status FROM device_command_delivery WHERE command_id=?",String.class,id);
		return rows.isEmpty()?"NOT_CONNECTED":rows.getFirst();
	}

	public record CommandReceipt(UUID commandId, String status, String deliveryState) { }
	public record CommandResult(UUID commandId, String status, String deviceOperationState, OffsetDateTime confirmedAt,
			OffsetDateTime requestedAt, String deliveryState) { }
	public record ActionReceipt(UUID actionId, String type, String status, String deliveryState) { }
	public record ActionResult(UUID actionId, UUID hazardId, String type, String status, Boolean hazardPresent,
			String treatmentStatus, String deviceOperationState, OffsetDateTime completedAt,
			OffsetDateTime requestedAt, String deliveryState) { }
}
