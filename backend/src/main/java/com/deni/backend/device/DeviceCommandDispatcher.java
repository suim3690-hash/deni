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
    public DeviceCommandDispatcher(JdbcTemplate jdbc, DeviceChannel channel, ObjectMapper json) {
        this.jdbc=jdbc; this.channel=channel; this.json=json;
    }
    @Scheduled(fixedDelay=500)
    public void dispatch() {
        jdbc.update("UPDATE device_command_delivery SET status=CASE WHEN status='QUEUED' THEN 'EXPIRED' ELSE 'UNKNOWN' END WHERE status IN ('QUEUED','SENT','DELIVERED') AND expires_at < clock_timestamp()");
        for(var row:jdbc.queryForList("SELECT command_id,device_id,expires_at FROM device_command_delivery WHERE status='QUEUED' ORDER BY expires_at LIMIT 50")) {
            String id=(String)row.get("device_id");
            if(!channel.connected(id)) continue;
            Object command=row.get("command_id");
            if(jdbc.update("UPDATE device_command_delivery SET status='SENT',sent_at=? WHERE command_id=? AND status='QUEUED' AND expires_at>clock_timestamp()",OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),command)==0) continue;
            try {
                String expires=jdbc.queryForObject("SELECT expires_at FROM device_command_delivery WHERE command_id=?",OffsetDateTime.class,command).toString();
                boolean sent=channel.send(id,json.writeValueAsString(Map.of("type","COMMAND","messageId",command.toString(),
                    "deviceId",id,"sentAt",OffsetDateTime.now().toString(),"payload",Map.of("commandId",command.toString(),
                    "command","PAUSE","expiresAt",expires,"parameters",Map.of()))));
                if(!sent) throw new IllegalStateException();
            } catch(Exception ex) {
                jdbc.update("UPDATE device_command_delivery SET status='UNKNOWN' WHERE command_id=? AND status='SENT'",command);
            }
        }
    }
}
