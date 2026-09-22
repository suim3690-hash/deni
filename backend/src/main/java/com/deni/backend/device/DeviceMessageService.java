package com.deni.backend.device;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class DeviceMessageService {
    private final JdbcTemplate jdbc;
    private final DeviceService devices;
    @org.springframework.beans.factory.annotation.Autowired
    private com.deni.backend.common.IdempotencyGuard guard;
    public DeviceMessageService(JdbcTemplate jdbc, DeviceService devices) { this.jdbc=jdbc; this.devices=devices; }
    @Transactional public void receive(String id, String type, JsonNode payload) {
        if ("ROBOT_STATE".equals(type)) {
            String operation=payload.path("operationState").asText();
            String movement=payload.path("movementState").asText();
            if (!Set.of("RUNNING","PAUSED","RELOCATING","UNKNOWN").contains(operation)) throw new IllegalArgumentException();
            if (!Set.of("FORWARD","TURNING","BACKWARD","STOPPED","UNKNOWN").contains(movement)) throw new IllegalArgumentException();
            // robot_live_state 트리거도 미래 시각을 거부하므로 저장 전에 같은 기준으로 맞춘다.
            OffsetDateTime sampled=devices.alignReportTime(OffsetDateTime.parse(payload.path("sampledAt").asText()));
            Long duration=payload.path("movementDurationMs").isNull() || payload.path("movementDurationMs").isMissingNode()
                ? null : Long.valueOf(payload.path("movementDurationMs").asText());
            java.math.BigDecimal distance=payload.path("movementDistanceM").isNull() || payload.path("movementDistanceM").isMissingNode()
                ? null : new java.math.BigDecimal(payload.path("movementDistanceM").asText());
            Integer battery=payload.path("batteryPercent").isNull() || payload.path("batteryPercent").isMissingNode()
                ? null : Integer.valueOf(payload.path("batteryPercent").asText());
            Boolean power=payload.path("powerEnabled").isBoolean()?payload.path("powerEnabled").asBoolean():null;
            String task=payload.path("taskState").isTextual()?payload.path("taskState").asText():null;
            if(task!=null && task.length()>40) throw new IllegalArgumentException();
            devices.recordStatus(new DeviceService.StatusInput(id,"ONLINE",operation.equals("RELOCATING")?"UNKNOWN":operation,battery,sampled));
            jdbc.update("""
                INSERT INTO robot_live_state(device_id,operation_state,movement_state,sampled_at,movement_duration_ms,movement_distance_m,power_enabled,task_state)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET
                operation_state=EXCLUDED.operation_state,movement_state=EXCLUDED.movement_state,
                sampled_at=EXCLUDED.sampled_at,movement_duration_ms=EXCLUDED.movement_duration_ms,
                movement_distance_m=EXCLUDED.movement_distance_m,power_enabled=EXCLUDED.power_enabled,task_state=EXCLUDED.task_state
                """,id,operation,movement,sampled,duration,distance,power,task);
            return;
        }
        UUID command=UUID.fromString(payload.path("commandId").asText());
        if ("COMMAND_ACK".equals(type) && "DELIVERED".equals(payload.path("status").asText())) {
            int updated=jdbc.update("UPDATE device_command_delivery SET status='DELIVERED' WHERE command_id=? AND device_id=? AND status IN ('SENT','DELIVERED')",command,id);
            if(updated==0) throw new IllegalArgumentException();
            return;
        }
        if (!"COMMAND_RESULT".equals(type)) throw new IllegalArgumentException();
        String status=payload.path("status").asText();
        if (!Set.of("SUCCEEDED","FAILED").contains(status)) throw new IllegalArgumentException();
        OffsetDateTime completed=OffsetDateTime.parse(payload.path("completedAt").asText()).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(completed.isAfter(OffsetDateTime.now())) throw new IllegalArgumentException();
        guard.lock("device",id);
        var rows=jdbc.queryForList("SELECT d.status,d.sent_at,d.completed_at,d.result_payload,r.kind,r.hazard_id FROM device_command_delivery d JOIN operation_requests r ON r.id=d.command_id WHERE d.command_id=? AND d.device_id=? FOR UPDATE OF d",command,id);
        if(rows.isEmpty()) throw new IllegalArgumentException();
        String kind=(String)rows.getFirst().get("kind");
        CommandResultPolicy.validate(kind,payload);
        String previous=(String)rows.getFirst().get("status");
        if(previous.equals(status)) {
            OffsetDateTime original=jdbc.queryForObject("SELECT completed_at FROM device_command_delivery WHERE command_id=?",OffsetDateTime.class,command);
            if(!original.toInstant().equals(completed.truncatedTo(java.time.temporal.ChronoUnit.MICROS).toInstant())) throw new IllegalArgumentException();
            if(rows.getFirst().get("result_payload")!=null && !rows.getFirst().get("result_payload").equals(payload.toString())) throw new IllegalArgumentException();
            return;
        }
        if(!Set.of("SENT","DELIVERED","UNKNOWN").contains(previous)) throw new IllegalArgumentException();
        OffsetDateTime sent=jdbc.queryForObject("SELECT sent_at FROM device_command_delivery WHERE command_id=?",OffsetDateTime.class,command);
        if(completed.isBefore(sent)) throw new IllegalArgumentException();
        String error=payload.path("errorCode").isTextual()?payload.path("errorCode").asText():null;
        if(error!=null && error.length()>100) throw new IllegalArgumentException();
        String operation=payload.path("operationState").asText("UNKNOWN");
        Boolean present=payload.path("hazardPresent").isBoolean()?payload.path("hazardPresent").asBoolean():null;
        jdbc.update("UPDATE device_command_delivery SET status=?,completed_at=?,error_code=?,result_operation_state=?,hazard_present=?,result_payload=? WHERE command_id=?",status,completed,error,operation,present,payload.toString(),command);
        if(status.equals("SUCCEEDED") && rows.getFirst().get("hazard_id")!=null) {
            if(!payload.path("hazardId").asText().equals(rows.getFirst().get("hazard_id").toString())) throw new IllegalArgumentException();
            jdbc.update("UPDATE hazards SET status='RESOLVED',device_operation_state=?,updated_at=clock_timestamp(),version=version+1 WHERE id=? AND device_id=? AND status='ACTIVE'",operation,rows.getFirst().get("hazard_id"),id);
        }
    }
}
