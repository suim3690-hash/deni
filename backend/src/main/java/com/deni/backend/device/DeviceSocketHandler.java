package com.deni.backend.device;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

@Component
public class DeviceSocketHandler extends TextWebSocketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(DeviceSocketHandler.class);
    private final DeviceChannel channel;
    private final DeviceService devices;
    private final DeviceMessageService messages;
    private final ObjectMapper json;
    public DeviceSocketHandler(DeviceChannel channel, DeviceService devices, DeviceMessageService messages, ObjectMapper json) {
        this.channel=channel; this.devices=devices; this.messages=messages; this.json=json;
    }
    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String id=(String)session.getAttributes().get("deviceId");
        try { devices.getStatus(id); }
        catch (RuntimeException ex) { session.close(CloseStatus.POLICY_VIOLATION); return; }
        session.setTextMessageSizeLimit(16384);
        if (!channel.register(id, session)) session.close(CloseStatus.POLICY_VIOLATION);
    }
    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String id=(String)session.getAttributes().get("deviceId");
        try {
            var node=json.readTree(message.getPayload());
            if (!id.equals(node.path("deviceId").asText())) throw new IllegalArgumentException();
            UUID.fromString(node.path("messageId").asText());
            OffsetDateTime.parse(node.path("sentAt").asText());
            messages.receive(id, node.path("type").asText(), node.path("payload"));
            synchronized(session) { session.sendMessage(new TextMessage(json.writeValueAsString(
                Map.of("type","RECEIPT","messageId",node.path("messageId").asText(),"accepted",true)))); }
        } catch (RuntimeException ex) {
            // 기기 메시지를 거부하면서 이유를 남기지 않으면 현장에서 원인을 찾을 수 없다.
            LOG.warn("Rejected device message: device={} type={} cause={}: {}", id,
                    message.getPayload().length() > 400 ? "(oversized)" : payloadType(message),
                    ex.getClass().getSimpleName(), ex.getMessage());
            synchronized(session) { session.sendMessage(new TextMessage("{\"type\":\"ERROR\",\"code\":\"INVALID_MESSAGE\"}")); }
        }
    }

    private String payloadType(TextMessage message) {
        try { return json.readTree(message.getPayload()).path("type").asText("(none)"); }
        catch (RuntimeException ex) { return "(unparsable)"; }
    }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        channel.remove((String)session.getAttributes().get("deviceId"),session);
    }
}
