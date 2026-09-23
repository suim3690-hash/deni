package com.deni.backend.device;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import com.deni.backend.child.ChildService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "robot.device-id=socket-test-device", "robot.device-token=integration-test-only-token-32-characters",
    "app.cors.allowed-origins=http://frontend.test:5173",
    "safety.profile-refresh.enabled=false"})
@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
class DeviceSocketConnectionTests {
    @LocalServerPort int port;
    @Autowired ChildService children;
    @Autowired DeviceService devices;
    @Autowired JdbcTemplate jdbc;
    @Autowired tools.jackson.databind.ObjectMapper json;
    @Test void corsAllowsConfiguredFrontendAndRejectsUnknownOrigin() throws Exception {
        var client=HttpClient.newHttpClient();
        URI uri=URI.create("http://localhost:"+port+"/api/v1/dashboard?childId="+UUID.randomUUID());
        var allowed=client.send(HttpRequest.newBuilder(uri)
            .header("Origin","http://frontend.test:5173")
            .header("Access-Control-Request-Method","GET")
            .method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());
        assertEquals(200,allowed.statusCode());
        assertEquals("http://frontend.test:5173",allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        var rejected=client.send(HttpRequest.newBuilder(uri)
            .header("Origin","http://unknown.test:5173")
            .header("Access-Control-Request-Method","GET")
            .method("OPTIONS",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding());
        assertEquals(403,rejected.statusCode());
    }
    @Test void realWebSocketAuthenticatesAndReceivesState() throws Exception {
        URI uri=URI.create("ws://localhost:"+port+"/ws/devices");
        var client=HttpClient.newHttpClient();
        assertThrows(ExecutionException.class,()->client.newWebSocketBuilder().buildAsync(uri,new WebSocket.Listener(){}).get(5,TimeUnit.SECONDS));
        var child=children.register("SOCKET_TEST",LocalDate.now().minusMonths(8),UUID.randomUUID());
        boolean registered=false;
        WebSocket socket=null;
        try {
            devices.register(child.childId(),"socket-test-device","test"); registered=true;
            var replies=new LinkedBlockingQueue<String>();
            socket=client.newWebSocketBuilder().header("X-Device-Id","socket-test-device")
                .header("Authorization","Bearer integration-test-only-token-32-characters")
                .buildAsync(uri,new WebSocket.Listener() {
                    public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last) {
                        replies.add(data.toString()); ws.request(1); return null;
                    }
                }).get(5,TimeUnit.SECONDS);
            socket.sendText("""
                {"type":"ROBOT_STATE","messageId":"%s","deviceId":"socket-test-device","sentAt":"%s",
                 "payload":{"operationState":"RUNNING","movementState":"FORWARD","sampledAt":"%s","batteryPercent":80}}
                """.formatted(UUID.randomUUID(),OffsetDateTime.now(),OffsetDateTime.now().minusSeconds(1)),true).get();
            assertTrue(replies.poll(5,TimeUnit.SECONDS).contains("RECEIPT"));
            assertEquals("RUNNING",devices.getStatus("socket-test-device").operationState());
            assertTrue(devices.getStatus("socket-test-device").commandsAvailable());
            var receipt=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/devices/socket-test-device/commands/pause"))
                .header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(202,receipt.statusCode());
            String commandId=json.readTree(receipt.body()).path("commandId").asText();
            var command=json.readTree(replies.poll(5,TimeUnit.SECONDS));
            assertEquals("COMMAND",command.path("type").asText());
            assertEquals(commandId,command.path("payload").path("commandId").asText());
            socket.sendText("""
                {"type":"COMMAND_RESULT","messageId":"%s","deviceId":"socket-test-device","sentAt":"%s",
                "payload":{"commandId":"%s","status":"SUCCEEDED","operationState":"PAUSED","completedAt":"%s"}}
                """.formatted(UUID.randomUUID(),OffsetDateTime.now(),commandId,OffsetDateTime.now()),true).get();
            assertTrue(replies.poll(5,TimeUnit.SECONDS).contains("RECEIPT"));
            var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1/devices/socket-test-device/commands/"+commandId)).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());
            assertEquals("SUCCEEDED",json.readTree(response.body()).path("status").asText());
        } finally {
            if(socket!=null) socket.sendClose(WebSocket.NORMAL_CLOSURE,"done").get(5,TimeUnit.SECONDS);
            if(registered) {
                jdbc.update("DELETE FROM device_command_delivery WHERE device_id='socket-test-device'");
                jdbc.update("DELETE FROM operation_requests WHERE device_id='socket-test-device'");
                jdbc.update("DELETE FROM robot_live_state WHERE device_id='socket-test-device'");
                jdbc.update("DELETE FROM devices WHERE id='socket-test-device' AND child_id=?",child.childId());
            }
            jdbc.update("DELETE FROM profile_history WHERE child_id=?",child.childId());
            jdbc.update("DELETE FROM children WHERE id=?",child.childId());
        }
    }
}
