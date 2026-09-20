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
    public DeviceMessageService(JdbcTemplate jdbc, DeviceService devices) { this.jdbc=jdbc; this.devices=devices; }
    @Transactional public void receive(String id, String type, JsonNode payload) {
        if ("ROBOT_STATE".equals(type)) {
            String operation=payload.path("operationState").asText();
            String movement=payload.path("movementState").asText();
            if (!Set.of("RUNNING","PAUSED","RELOCATING","UNKNOWN").contains(operation)) throw new IllegalArgumentException();
            if (!Set.of("FORWARD","TURNING","BACKWARD","STOPPED","UNKNOWN").contains(movement)) throw new IllegalArgumentException();
            OffsetDateTime sampled=OffsetDateTime.parse(payload.path("sampledAt").asText());
            Long duration=payload.path("movementDurationMs").isNull() || payload.path("movementDurationMs").isMissingNode()
                ? null : Long.valueOf(payload.path("movementDurationMs").asText());
            java.math.BigDecimal distance=payload.path("movementDistanceM").isNull() || payload.path("movementDistanceM").isMissingNode()
                ? null : new java.math.BigDecimal(payload.path("movementDistanceM").asText());
            Integer battery=payload.path("batteryPercent").isNull() || payload.path("batteryPercent").isMissingNode()
                ? null : Integer.valueOf(payload.path("batteryPercent").asText());
            devices.recordStatus(new DeviceService.StatusInput(id,"ONLINE",operation.equals("RELOCATING")?"UNKNOWN":operation,battery,sampled));
            jdbc.update("""
                INSERT INTO robot_live_state(device_id,operation_state,movement_state,sampled_at,movement_duration_ms,movement_distance_m)
                VALUES (?,?,?,?,?,?) ON CONFLICT(device_id) DO UPDATE SET
                operation_state=EXCLUDED.operation_state,movement_state=EXCLUDED.movement_state,
                sampled_at=EXCLUDED.sampled_at,movement_duration_ms=EXCLUDED.movement_duration_ms,
                movement_distance_m=EXCLUDED.movement_distance_m
                """,id,operation,movement,sampled,duration,distance);
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
        if (status.equals("SUCCEEDED") && !payload.path("operationState").asText().equals("PAUSED")) throw new IllegalArgumentException();
        OffsetDateTime completed=OffsetDateTime.parse(payload.path("completedAt").asText()).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(completed.isAfter(OffsetDateTime.now())) throw new IllegalArgumentException();
        var rows=jdbc.queryForList("SELECT status,sent_at,completed_at FROM device_command_delivery WHERE command_id=? AND device_id=? FOR UPDATE",command,id);
        if(rows.isEmpty()) throw new IllegalArgumentException();
        String previous=(String)rows.getFirst().get("status");
        if(previous.equals(status)) {
            OffsetDateTime original=jdbc.queryForObject("SELECT completed_at FROM device_command_delivery WHERE command_id=?",OffsetDateTime.class,command);
            if(!original.toInstant().equals(completed.truncatedTo(java.time.temporal.ChronoUnit.MICROS).toInstant())) throw new IllegalArgumentException();
            return;
        }
        if(!Set.of("SENT","DELIVERED","UNKNOWN").contains(previous)) throw new IllegalArgumentException();
        OffsetDateTime sent=jdbc.queryForObject("SELECT sent_at FROM device_command_delivery WHERE command_id=?",OffsetDateTime.class,command);
        if(completed.isBefore(sent)) throw new IllegalArgumentException();
        String error=payload.path("errorCode").isTextual()?payload.path("errorCode").asText():null;
        if(error!=null && error.length()>100) throw new IllegalArgumentException();
        jdbc.update("UPDATE device_command_delivery SET status=?,completed_at=?,error_code=? WHERE command_id=?",status,completed,error,command);
    }
}
