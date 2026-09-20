package com.deni.backend.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
@EnableWebSocket
public class DeviceSocketConfig implements WebSocketConfigurer {
    private final DeviceSocketHandler handler;
    private final String token;
    private final String deviceId;
    public DeviceSocketConfig(DeviceSocketHandler handler,
            @Value("${robot.device-token:}") String token, @Value("${robot.device-id:}") String deviceId) {
        this.handler = handler; this.token = token; this.deviceId = deviceId;
    }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/devices").addInterceptors(new HandshakeInterceptor() {
            public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                    WebSocketHandler socketHandler, Map<String,Object> attributes) {
                String supplied = request.getHeaders().getFirst("Authorization");
                String id = request.getHeaders().getFirst("X-Device-Id");
                boolean valid = token.length() >= 32 && !deviceId.isBlank() && deviceId.equals(id)
                    && supplied != null && MessageDigest.isEqual(
                        ("Bearer " + token).getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
                if (!valid) { response.setStatusCode(HttpStatus.UNAUTHORIZED); return false; }
                attributes.put("deviceId", id); return true;
            }
            public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                    WebSocketHandler socketHandler, Exception exception) { }
        });
    }
}
