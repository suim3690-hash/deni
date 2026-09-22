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
		if (!java.util.Set.of("pause", "resume", "power-on", "power-off").contains(command)) {
			throw ApiException.validation("지원하지 않는 명령입니다.", Map.of("command", "pause, resume, power-on, power-off를 지원합니다."));
		}
		String id = normalizedDeviceId(deviceId);
		guard.lock("device", id);
		var device = devices.getStatus(id);
		String kind = command.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
		OperationRequest existing = replay(id, key, kind, null);
		if (existing != null) return new CommandReceipt(existing.getId(), existing.getStatus(), deliveryState(existing.getId()));
		if (command.equals("resume")) {
			if (deliveryDb != null && Boolean.TRUE.equals(deliveryDb.queryForObject(
					"SELECT EXISTS(SELECT 1 FROM hazards WHERE device_id=? AND child_id=? AND status='ACTIVE' AND object_type='SWALLOW')", Boolean.class, id, devices.getLinkedChildId(id)))) {
				throw ApiException.conflict("HAZARD_UNRESOLVED", "미처리 위험물이 있어 청소를 재개할 수 없습니다.");
			}
			if (deliveryDb == null) throw ApiException.conflict("SAFETY_CONFIRMATION_REQUIRED", "기기 재개 전달 기능이 연결되지 않았습니다.");
		}
		requireOnline(device);
		if (!command.equals("pause")) requireDelivery(device);
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
		if (!request.getDeviceId().equals(id) || request.getHazardId() != null) {
			throw ApiException.notFound("COMMAND_NOT_FOUND", "해당 기기의 명령을 찾을 수 없습니다.");
		}
		// 기기 현재 상태가 PAUSED여도 이 명령의 성공 증거가 아니므로 확인 결과는 UNKNOWN이다.
		if (deliveryDb != null) {
			var result = deliveryDb.query("SELECT status,completed_at,result_operation_state FROM device_command_delivery WHERE command_id=?",
					(rs,n) -> new CommandResult(request.getId(),
							java.util.Set.of("QUEUED","SENT","DELIVERED").contains(rs.getString("status")) ? "REQUESTED" : rs.getString("status"),
							rs.getString("status").equals("SUCCEEDED") ? java.util.Objects.requireNonNullElse(rs.getString("result_operation_state"), "PAUSED") : "UNKNOWN",
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
		if (existing != null) return new ActionReceipt(existing.getId(), existing.getKind(), existing.getStatus(), deliveryState(existing.getId()));
		if (!"SWALLOW".equals(hazard.object().type())) {
			throw ApiException.conflict("REMOVAL_CHECK_NOT_SUPPORTED", "삼킴 위험물만 로봇에 제거 재확인을 요청할 수 있습니다.");
		}
		hazards.requireActiveAssociation(hazardId, devices.getLinkedChildId(id), id);
		requireOnline(device);
		if (!device.operationState().equals("PAUSED")) {
			throw ApiException.conflict("DEVICE_NOT_PAUSED", "일시정지 상태가 확인된 기기만 재확인 요청을 접수할 수 있습니다.");
		}
		requirePoweredPause(id);
		OperationRequest saved = requests.saveAndFlush(new OperationRequest(id, hazardId, key, "DIRECT_REMOVAL_CHECK", now()));
		queueAction(saved, device);
		return new ActionReceipt(saved.getId(), saved.getKind(), saved.getStatus(), deliveryState(saved.getId()));
	}

	@Transactional(readOnly = true)
	public ActionResult getAction(UUID actionId) {
		OperationRequest request = findRequest(actionId, "SAFETY_ACTION_NOT_FOUND");
		if (!java.util.Set.of("DIRECT_REMOVAL_CHECK", "RELOCATE").contains(request.getKind())) {
			throw ApiException.notFound("SAFETY_ACTION_NOT_FOUND", "안전 처리 요청을 찾을 수 없습니다.");
		}
		if (deliveryDb != null) {
			var result = deliveryDb.query("SELECT status,completed_at,result_operation_state,hazard_present FROM device_command_delivery WHERE command_id=?",
				(rs,n) -> {
					String state=rs.getString("status");
					boolean success=state.equals("SUCCEEDED");
					String treatment=success ? (request.getKind().equals("RELOCATE") ? "TEMPORARY_COMPLETED" : "COMPLETED") : "PENDING";
					return new ActionResult(request.getId(), request.getHazardId(), request.getKind(),
						java.util.Set.of("QUEUED","SENT","DELIVERED").contains(state) ? "REQUESTED" : state,
						rs.getObject("hazard_present",Boolean.class), treatment,
						java.util.Objects.requireNonNullElse(rs.getString("result_operation_state"),"UNKNOWN"),
						rs.getObject("completed_at",OffsetDateTime.class),request.getCreatedAt(),state);
				},request.getId());
			if (!result.isEmpty()) return result.getFirst();
		}
		return new ActionResult(request.getId(), request.getHazardId(), request.getKind(), request.getStatus(),
				null, "PENDING", "UNKNOWN", null, request.getCreatedAt(), "NOT_CONNECTED");
	}

	@Transactional
	public ActionReceipt requestRelocation(UUID hazardId, UUID key) {
		requireKey(key);
		if (hazardId == null) throw ApiException.validation("위험 ID는 필수입니다.", Map.of("hazardId", "필수입니다."));
		var hazard=hazards.getHazard(hazardId);
		String id=hazard.deviceId();
		guard.lock("device",id);
		var existing=replay(id,key,"RELOCATE",hazardId);
		if(existing!=null) return new ActionReceipt(existing.getId(),existing.getKind(),existing.getStatus(),deliveryState(existing.getId()));
		if(!hazard.object().type().equals("SWALLOW"))
			throw ApiException.conflict("RELOCATION_NOT_SUPPORTED","삼킴 위험물만 이송할 수 있습니다.");
		hazards.requireActiveAssociation(hazardId,devices.getLinkedChildId(id),id);
		var device=devices.getStatus(id);
		requireOnline(device); requireDelivery(device);
		if(!device.operationState().equals("PAUSED")) throw ApiException.conflict("DEVICE_NOT_PAUSED","정지 상태에서 이송을 요청해 주세요.");
		requirePoweredPause(id);
		var saved=requests.saveAndFlush(new OperationRequest(id,hazardId,key,"RELOCATE",now()));
		queueAction(saved,device);
		return new ActionReceipt(saved.getId(),saved.getKind(),saved.getStatus(),deliveryState(saved.getId()));
	}

	private void queueAction(OperationRequest request, DeviceService.DeviceStatus device) {
		if(deliveryDb==null || !device.commandsAvailable()) return;
		Integer busy=deliveryDb.queryForObject("SELECT count(*) FROM device_command_delivery d JOIN operation_requests r ON r.id=d.command_id WHERE d.device_id=? AND r.hazard_id IS NOT NULL AND d.status IN ('QUEUED','SENT','DELIVERED','UNKNOWN')",Integer.class,request.getDeviceId());
		if(busy!=null && busy>0) throw ApiException.conflict("ACTION_IN_PROGRESS","이미 진행 중이거나 결과 확인이 필요한 처리 요청이 있습니다.");
		deliveryDb.update("INSERT INTO device_command_delivery(command_id,device_id,expires_at) VALUES (?,?,?)",request.getId(),request.getDeviceId(),now().plusSeconds(10));
	}
	private void requireDelivery(DeviceService.DeviceStatus device) {
		if(deliveryDb==null || !device.commandsAvailable()) throw ApiException.conflict("DEVICE_NOT_CONTROLLABLE","기기 연결과 최신 모터 상태를 확인해 주세요.");
	}
	private void requirePoweredPause(String deviceId) {
		if (deliveryDb == null) return;
		var rows = deliveryDb.queryForList("""
				SELECT power_enabled, task_state FROM robot_live_state
				WHERE device_id = ? AND sampled_at <= clock_timestamp() AND received_at <= clock_timestamp()
				  AND sampled_at > clock_timestamp() - INTERVAL '10 seconds'
				  AND received_at > clock_timestamp() - INTERVAL '10 seconds'
				""", deviceId);
		if (rows.isEmpty()) throw ApiException.conflict("DEVICE_STATE_STALE", "기기의 최신 전원·작업 상태를 확인한 뒤 다시 시도해 주세요.");
		var state = rows.getFirst();
		if (!Boolean.TRUE.equals(state.get("power_enabled"))) {
			throw ApiException.conflict("DEVICE_POWERED_OFF", "로봇 전원이 꺼져 있습니다. 전원을 켠 뒤 위험물 제거를 확인해 주세요.");
		}
		Object task = state.get("task_state");
		if (!"HAZARD_PAUSED".equals(task) && !"PAUSED".equals(task)) {
			throw ApiException.conflict("DEVICE_NOT_PAUSED", "로봇이 위험물 앞에 멈춘 상태인지 확인한 뒤 다시 시도해 주세요.");
		}
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
