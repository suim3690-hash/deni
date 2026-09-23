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
        state(id,operation,"STOPPED",at);
    }
    void state(String id,String operation,String movement,OffsetDateTime at) {
        messages.receive(id,"ROBOT_STATE",json.valueToTree(Map.of("operationState",operation,"movementState",movement,"sampledAt",at.toString(),"batteryPercent",75)));
    }
    @Test void cameraOnlyConnectionCannotReceiveCommandsUntilMotorMovementIsObserved() {
        String id=device(); var session=mock(WebSocketSession.class); when(session.isOpen()).thenReturn(true); channel.register(id,session);
        try {
            state(id,"UNKNOWN","STOPPED",OffsetDateTime.now().minusSeconds(11));
            assertFalse(devices.getStatus(id).commandsAvailable());
            state(id,"UNKNOWN","UNKNOWN",OffsetDateTime.now().minusSeconds(2));
            assertFalse(devices.getStatus(id).commandsAvailable());
            state(id,"UNKNOWN","STOPPED",OffsetDateTime.now().minusSeconds(1));
            assertTrue(devices.getStatus(id).commandsAvailable());
        } finally { channel.remove(id,session); }
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
        assertEquals("HIGH",jdbc.queryForObject("SELECT risk_level FROM hazards WHERE id=?",String.class,saved.hazardId()));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM detection_events WHERE event_id=?",Integer.class,event));
    }
    @Test void oldTelemetryCannotReplaceCurrentState() {
        String id=device(); OffsetDateTime at=OffsetDateTime.now().minusSeconds(1);
        state(id,"PAUSED",at); state(id,"RUNNING",at.minusSeconds(2));
        assertEquals("PAUSED",devices.getStatus(id).operationState());
        assertEquals("PAUSED",jdbc.queryForObject("SELECT operation_state FROM robot_live_state WHERE device_id=?",String.class,id));
    }

    @Test void otherProfilesHistoricalHazardsDoNotBlockActiveProfileResume() throws Exception {
        String id=device();
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image,"png",out);
        var oldHazard=uploads.save(id,UUID.randomUUID(),"OBJECT","배터리",out.toByteArray());
        var active=children.register("ACTIVE_"+UUID.randomUUID(),LocalDate.now().minusMonths(20),UUID.randomUUID());
        devices.register(active.childId(),id,"test");
        assertEquals(active.childId(),devices.getLinkedChildId(id));
        var session=mock(WebSocketSession.class); when(session.isOpen()).thenReturn(true); channel.register(id,session);
        try {
            state(id,"PAUSED",OffsetDateTime.now().minusSeconds(1));
            assertEquals("QUEUED",operations.requestCommand(id,"resume",UUID.randomUUID()).deliveryState());
            assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM hazards WHERE id=?",String.class,oldHazard.hazardId()));
        } finally { channel.remove(id,session); }
    }

    /** 이송은 물체를 안전 구역으로 옮길 뿐이므로 위험 자체를 해결로 바꾸지 않는다. */
    @Test void relocationSuccessIsTemporaryAndLeavesHazardActive() throws Exception {
        String id=device(); var session=mock(WebSocketSession.class); when(session.isOpen()).thenReturn(true); channel.register(id,session);
        try {
            var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var out=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image,"png",out);
            UUID hazard=uploads.save(id,UUID.randomUUID(),"HAZARD","동전",out.toByteArray()).hazardId();
            pausedAndPowered(id);
            var receipt=operations.requestRelocation(hazard,UUID.randomUUID());
            dispatcher.dispatch();
            messages.receive(id,"COMMAND_RESULT",json.valueToTree(Map.of("commandId",receipt.actionId().toString(),
                "status","SUCCEEDED","operationState","PAUSED","hazardId",hazard.toString(),
                "relocationCompleted",true,"completedAt",OffsetDateTime.now().toString())));
            var result=operations.getAction(receipt.actionId());
            assertEquals("SUCCEEDED",result.status());
            assertEquals("TEMPORARY_COMPLETED",result.treatmentStatus());
            assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM hazards WHERE id=?",String.class,hazard));
        } finally { channel.remove(id,session); }
    }

    /** 직접 제거는 물체가 사라진 증거이므로 이송과 달리 그 자리에서 해결로 본다. */
    @Test void directRemovalSuccessResolvesTheHazard() throws Exception {
        String id=device(); var session=mock(WebSocketSession.class); when(session.isOpen()).thenReturn(true); channel.register(id,session);
        try {
            var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var out=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image,"png",out);
            UUID hazard=uploads.save(id,UUID.randomUUID(),"HAZARD","동전",out.toByteArray()).hazardId();
            pausedAndPowered(id);
            var receipt=operations.requestRemovalCheck(hazard,UUID.randomUUID());
            dispatcher.dispatch();
            messages.receive(id,"COMMAND_RESULT",json.valueToTree(Map.of("commandId",receipt.actionId().toString(),
                "status","SUCCEEDED","operationState","PAUSED","hazardId",hazard.toString(),
                "hazardPresent",false,"absenceDurationMs",2500,"completedAt",OffsetDateTime.now().toString())));
            assertEquals("COMPLETED",operations.getAction(receipt.actionId()).treatmentStatus());
            assertEquals("RESOLVED",jdbc.queryForObject("SELECT status FROM hazards WHERE id=?",String.class,hazard));
        } finally { channel.remove(id,session); }
    }

    /** 안전 처리 요청은 기기가 보고한 전원 ON과 정지 상태를 요구한다. */
    void pausedAndPowered(String id) {
        messages.receive(id,"ROBOT_STATE",json.valueToTree(Map.of("operationState","PAUSED","movementState","STOPPED",
            "sampledAt",OffsetDateTime.now().minusSeconds(1).toString(),"batteryPercent",75,
            "powerEnabled",true,"taskState","HAZARD_PAUSED")));
    }

    @Test void activeProfilesUnresolvedSwallowHazardStillBlocksResume() throws Exception {
        String id=device();
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out=new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image,"png",out);
        uploads.save(id,UUID.randomUUID(),"OBJECT","동전",out.toByteArray());
        var error=assertThrows(com.deni.backend.common.ApiException.class,()->operations.requestCommand(id,"resume",UUID.randomUUID()));
        assertEquals("HAZARD_UNRESOLVED",error.getCode());
    }
}
