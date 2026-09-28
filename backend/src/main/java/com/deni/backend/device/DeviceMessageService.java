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
    private static final int CLEARED_LIMIT=32;
    /** 로봇이 더는 보지 못하는 위험은 사용자 확인 없이 종료한다. 로봇이 다시 보면 새 건으로 올라온다. */
    private void resolveAbsent(String id, JsonNode instances, JsonNode labels) {
        var ids=new java.util.ArrayList<UUID>();
        if(instances.isArray()) for(JsonNode node:instances) ids.add(UUID.fromString(node.asText()));
        var names=new java.util.ArrayList<String>();
        if(labels.isArray()) for(JsonNode node:labels) names.add(node.asText());
        if(ids.size()>CLEARED_LIMIT || names.size()>CLEARED_LIMIT) throw new IllegalArgumentException();
        if(ids.isEmpty() && names.isEmpty()) return;
        guard.lock("device",id);
        // 아직 결과가 오지 않은 처리 요청의 대상은 그 요청이 끝낸다.
        String pending=" AND NOT EXISTS (SELECT 1 FROM operation_requests r JOIN device_command_delivery d"
            +" ON d.command_id=r.id WHERE r.hazard_id=h.id AND d.status IN ('QUEUED','SENT','DELIVERED','UNKNOWN'))";
        String resolve="UPDATE hazards h SET status='RESOLVED',updated_at=clock_timestamp(),version=h.version+1"
            +" WHERE h.device_id=? AND h.status='ACTIVE'";
        if(!ids.isEmpty()) {
            var arguments=new java.util.ArrayList<Object>(); arguments.add(id); arguments.addAll(ids);
            jdbc.update(resolve+" AND h.object_instance_id IN ("+placeholders(ids.size())+")"+pending,arguments.toArray());
        }
        // 개체 식별자가 없는 과거 기록은 종류로만 정리할 수 있다. 식별된 건은 위에서 이미 처리했다.
        if(!names.isEmpty()) {
            var arguments=new java.util.ArrayList<Object>(); arguments.add(id); arguments.addAll(names);
            jdbc.update(resolve+" AND h.object_instance_id IS NULL AND h.object_name IN ("
                +placeholders(names.size())+")"+pending,arguments.toArray());
        }
    }

    private static String placeholders(int count) {
        return String.join(",",java.util.Collections.nCopies(count,"?"));
    }

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
            resolveAbsent(id,payload.path("clearedObjectInstanceIds"),payload.path("clearedObjectLabels"));
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
            // 이송 성공은 임시 완료다. 물체는 안전 구역으로 옮겨졌을 뿐 치워지지 않았으므로 위험은 ACTIVE로 두고
            // 다음 백엔드 시작의 시연 정책이 마무리한다. 직접 제거만 그 자리에서 해결로 본다.
            String hazardSql=kind.equals("RELOCATE")
                ? "UPDATE hazards SET device_operation_state=?,updated_at=clock_timestamp(),version=version+1 WHERE id=? AND device_id=? AND status='ACTIVE'"
                : "UPDATE hazards SET status='RESOLVED',device_operation_state=?,updated_at=clock_timestamp(),version=version+1 WHERE id=? AND device_id=? AND status='ACTIVE'";
            jdbc.update(hazardSql,operation,rows.getFirst().get("hazard_id"),id);
        }
    }
}
