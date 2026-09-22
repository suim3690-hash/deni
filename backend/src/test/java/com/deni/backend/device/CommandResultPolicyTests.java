package com.deni.backend.device;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class CommandResultPolicyTests {
    private final ObjectMapper json=new ObjectMapper();
    @Test void pauseCannotClaimRunningAndResumeCannotClaimPaused() {
        assertThrows(IllegalArgumentException.class,()->CommandResultPolicy.validate("PAUSE",json.valueToTree(Map.of("status","SUCCEEDED","operationState","RUNNING"))));
        assertThrows(IllegalArgumentException.class,()->CommandResultPolicy.validate("RESUME",json.valueToTree(Map.of("status","SUCCEEDED","operationState","PAUSED"))));
        CommandResultPolicy.validate("RESUME",json.valueToTree(Map.of("status","SUCCEEDED","operationState","RUNNING")));
    }
    @Test void removalNeedsAbsentObjectAndFullObservationWindow() {
        for(int milliseconds:new int[]{0,1999})
            assertThrows(IllegalArgumentException.class,()->CommandResultPolicy.validate("DIRECT_REMOVAL_CHECK",json.valueToTree(Map.of("status","SUCCEEDED","operationState","RUNNING","hazardPresent",false,"absenceDurationMs",milliseconds))));
        assertThrows(IllegalArgumentException.class,()->CommandResultPolicy.validate("DIRECT_REMOVAL_CHECK",json.valueToTree(Map.of("status","SUCCEEDED","operationState","RUNNING","hazardPresent",true,"absenceDurationMs",2000))));
        CommandResultPolicy.validate("DIRECT_REMOVAL_CHECK",json.valueToTree(Map.of("status","SUCCEEDED","operationState","RUNNING","hazardPresent",false,"absenceDurationMs",2000)));
    }
    @Test void relocationNeedsExplicitCompletionEvidence() {
        assertThrows(IllegalArgumentException.class,()->CommandResultPolicy.validate("RELOCATE",json.valueToTree(Map.of("status","SUCCEEDED","operationState","RUNNING"))));
        CommandResultPolicy.validate("RELOCATE",json.valueToTree(Map.of("status","SUCCEEDED","operationState","PAUSED","relocationCompleted",true)));
    }
}
