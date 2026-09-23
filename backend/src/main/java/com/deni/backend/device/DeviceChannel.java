package com.deni.backend.device;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.TextMessage;

@Component
public class DeviceChannel {
    private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    public boolean connected(String id) {
        var session = sessions.get(id);
        return session != null && session.isOpen();
    }
    public boolean register(String id, WebSocketSession session) { return sessions.putIfAbsent(id, session) == null; }
    public void remove(String id, WebSocketSession session) { sessions.remove(id, session); }
    public boolean send(String id, String json) throws IOException {
        var session = sessions.get(id);
        if (session == null || !session.isOpen()) return false;
        synchronized (session) { session.sendMessage(new TextMessage(json)); }
        return true;
    }
}
