package com.deni.backend.device;

import java.time.*;
import java.util.*;
import com.deni.backend.child.ChildService;
import com.deni.backend.operation.OperationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE,properties="safety.profile-refresh.enabled=false")
@EnabledIfEnvironmentVariable(named="RUN_DB_TESTS",matches="true")
@Transactional
class HardwareIntegrationTests {
    @Autowired ChildService children;
    @Autowired DeviceService devices;
    @Autowired DeviceChannel channel;
    @Autowired DeviceMessageService messages;
    @Autowired OperationService operations;
    @Autowired DeviceCommandDispatcher dispatcher;
    @Autowired DetectionUploadService uploads;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    String device() {
        var child=children.register("HW_TEST",LocalDate.now().minusMonths(6),UUID.randomUUID());
        String id="hw-test-"+UUID.randomUUID(); devices.register(child.childId(),id,"test"); return id;
    }
    void state(String id,String operation,OffsetDateTime at) {
        messages.receive(id,"ROBOT_STATE",json.valueToTree(Map.of("operationState",operation,"movementState","STOPPED","sampledAt",at.toString(),"batteryPercent",75)));
    }
    @Test void pauseTravelsToSessionAndOnlyMatchingResultConfirmsIt() throws Exception {
        String id=device(); var session=mock(WebSocketSession.class); when(session.isOpen()).thenReturn(true); channel.register(id,session);
        try {
            state(id,"RUNNING",OffsetDateTime.now().minusSeconds(1));
            assertTrue(devices.getStatus(id).commandsAvailable());
            UUID key=UUID.randomUUID(); var receipt=operations.requestCommand(id,"pause",key);
            assertEquals("QUEUED",receipt.deliveryState());
            assertEquals(receipt.commandId(),operations.requestCommand(id,"pause",key).commandId());
            dispatcher.dispatch(); verify(session).sendMessage(any());
            messages.receive(id,"COMMAND_ACK",json.valueToTree(Map.of("commandId",receipt.commandId().toString(),"status","DELIVERED")));
            assertEquals("REQUESTED",operations.getCommand(id,receipt.commandId()).status());
            var result=json.valueToTree(Map.of("commandId",receipt.commandId().toString(),"status","SUCCEEDED","operationState","PAUSED","completedAt",OffsetDateTime.now().toString()));
            assertThrows(IllegalArgumentException.class,()->messages.receive(device(),"COMMAND_RESULT",result));
            // No further DB writes after an expected transactional exception in this test.
        } finally { channel.remove(id,session); }
    }
    @Test void completedResultIsIdempotentAndDoesNotResend() throws Exception {
        String id=device(); var session=mock(WebSocketSession.class); when(session.isOpen()).thenReturn(true); channel.register(id,session);
        try {
            state(id,"RUNNING",OffsetDateTime.now().minusSeconds(1));
            var receipt=operations.requestCommand(id,"pause",UUID.randomUUID()); dispatcher.dispatch();
            var result=json.valueToTree(Map.of("commandId",receipt.commandId().toString(),"status","SUCCEEDED","operationState","PAUSED","completedAt",OffsetDateTime.now().toString()));
            messages.receive(id,"COMMAND_RESULT",result); messages.receive(id,"COMMAND_RESULT",result);
            assertEquals("SUCCEEDED",operations.getCommand(id,receipt.commandId()).status());
            assertEquals("PAUSED",operations.getCommand(id,receipt.commandId()).deviceOperationState());
            dispatcher.dispatch(); verify(session,times(1)).sendMessage(any());
        } finally { channel.remove(id,session); }
    }
    @Test void unacknowledgedTimeoutIsUnknownNotFailed() {
        String id=device(); var session=mock(WebSocketSession.class); when(session.isOpen()).thenReturn(true); channel.register(id,session);
        try {
            state(id,"RUNNING",OffsetDateTime.now().minusSeconds(1));
            var receipt=operations.requestCommand(id,"pause",UUID.randomUUID()); dispatcher.dispatch();
            jdbc.update("UPDATE device_command_delivery SET expires_at=clock_timestamp()-interval '1 second' WHERE command_id=?",receipt.commandId());
            dispatcher.dispatch(); assertEquals("UNKNOWN",operations.getCommand(id,receipt.commandId()).status());
        } finally { channel.remove(id,session); }
    }
    @Test void imageCreatesStageBasedHazardExactlyOnce() throws Exception {
        String id=device(); UUID event=UUID.randomUUID();
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image,"png",out);
        var saved=uploads.save(id,event,"HAZARD","동전",out.toByteArray());
        assertNotNull(saved.hazardId());
        assertEquals(saved,uploads.save(id,event,"HAZARD","동전",out.toByteArray()));
        assertEquals("VERY_HIGH",jdbc.queryForObject("SELECT risk_level FROM hazards WHERE id=?",String.class,saved.hazardId()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM detection_events WHERE event_id=?",Integer.class,event));
    }
    @Test void oldTelemetryCannotReplaceCurrentState() {
        String id=device(); OffsetDateTime at=OffsetDateTime.now().minusSeconds(1);
        state(id,"PAUSED",at); state(id,"RUNNING",at.minusSeconds(2));
        assertEquals("PAUSED",devices.getStatus(id).operationState());
        assertEquals("PAUSED",jdbc.queryForObject("SELECT operation_state FROM robot_live_state WHERE device_id=?",String.class,id));
    }
}
