package com.deni.backend.device;

import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class DeviceCommandDispatcher {
    private final JdbcTemplate jdbc;
    private final DeviceChannel channel;
    private final ObjectMapper json;
    private final Map<Object,Long> recoveryQueries = new java.util.concurrent.ConcurrentHashMap<>();
    public DeviceCommandDispatcher(JdbcTemplate jdbc, DeviceChannel channel, ObjectMapper json) {
        this.jdbc=jdbc; this.channel=channel; this.json=json;
    }
    @Scheduled(fixedDelay=500)
    public void dispatch() {
		// Close legacy requests accepted without a delivery row. Never execute them late.
		jdbc.update("""
			INSERT INTO device_command_delivery(command_id,device_id,status,expires_at,completed_at,error_code)
			SELECT r.id,r.device_id,'EXPIRED',clock_timestamp(),clock_timestamp(),'NOT_CONNECTED'
			FROM operation_requests r WHERE NOT EXISTS (
			  SELECT 1 FROM device_command_delivery d WHERE d.command_id=r.id)
			AND r.created_at < clock_timestamp()-interval '10 seconds'
			ON CONFLICT(command_id) DO NOTHING
			""");
        jdbc.update("UPDATE device_command_delivery SET status=CASE WHEN status='QUEUED' THEN 'EXPIRED' ELSE 'UNKNOWN' END WHERE status IN ('QUEUED','SENT','DELIVERED') AND expires_at < clock_timestamp()");
        for(var row:jdbc.queryForList("SELECT d.command_id,d.device_id,d.expires_at,r.kind,r.hazard_id,h.object_name FROM device_command_delivery d JOIN operation_requests r ON r.id=d.command_id LEFT JOIN hazards h ON h.id=r.hazard_id WHERE d.status='QUEUED' ORDER BY d.expires_at LIMIT 50")) {
            String id=(String)row.get("device_id");
            if(!channel.connected(id)) continue;
            Object command=row.get("command_id");
            if(jdbc.update("UPDATE device_command_delivery SET status='SENT',sent_at=? WHERE command_id=? AND status='QUEUED' AND expires_at>clock_timestamp()",OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),command)==0) continue;
            try {
                String expires=jdbc.queryForObject("SELECT expires_at FROM device_command_delivery WHERE command_id=?",OffsetDateTime.class,command).toString();
                String kind=(String)row.get("kind");
                var parameters=new java.util.HashMap<String,Object>();
                if(row.get("hazard_id")!=null) {
                    parameters.put("hazardId",row.get("hazard_id").toString());
                    parameters.put("objectLabel",row.get("object_name"));
                    // Acceptance expires quickly; treatment may take longer.
                    jdbc.update("UPDATE device_command_delivery SET expires_at=clock_timestamp()+interval '180 seconds' WHERE command_id=?",command);
                }
                boolean sent=channel.send(id,json.writeValueAsString(Map.of("type","COMMAND","messageId",command.toString(),
                    "deviceId",id,"sentAt",OffsetDateTime.now().toString(),"payload",Map.of("commandId",command.toString(),
                    "command",kind.equals("DIRECT_REMOVAL_CHECK")?"RECHECK_HAZARD":kind,"expiresAt",expires,"parameters",parameters))));
                if(!sent) throw new IllegalStateException();
            } catch(Exception ex) {
                jdbc.update("UPDATE device_command_delivery SET status='UNKNOWN' WHERE command_id=? AND status='SENT'",command);
            }
        }
        recoverUnknown();
    }

    private void recoverUnknown() {
        var rows=jdbc.queryForList("""
            SELECT d.command_id,d.device_id,r.hazard_id,h.object_name
            FROM device_command_delivery d JOIN operation_requests r ON r.id=d.command_id
            LEFT JOIN hazards h ON h.id=r.hazard_id
            WHERE d.status='UNKNOWN' AND d.sent_at IS NOT NULL ORDER BY d.expires_at
            """);
        var active=new java.util.HashSet<Object>();
        for(var row:rows) active.add(row.get("command_id"));
        recoveryQueries.keySet().retainAll(active);
        long now=System.nanoTime();
        int budget=50;
        for(var row:rows) {
            Object command=row.get("command_id");
            String id=(String)row.get("device_id");
            Long last=recoveryQueries.get(command);
            if(!channel.connected(id) || (last!=null && now-last<2_000_000_000L)) continue;
            if(budget--<=0) break;
            recoveryQueries.put(command,now);
            var parameters=new java.util.HashMap<String,Object>();
            if(row.get("hazard_id")!=null) {
                parameters.put("hazardId",row.get("hazard_id").toString());
                parameters.put("objectLabel",row.get("object_name"));
            }
            try {
                // A status query is deliberately not an executable COMMAND.
                channel.send(id,json.writeValueAsString(Map.of("type","COMMAND_STATUS",
                    "messageId",java.util.UUID.randomUUID().toString(),"deviceId",id,
                    "sentAt",OffsetDateTime.now().toString(),"payload",
                    Map.of("commandId",command.toString(),"parameters",parameters))));
            } catch(Exception ex) {
                // Retain UNKNOWN until the connected device provides a result.
            }
        }
    }
}
