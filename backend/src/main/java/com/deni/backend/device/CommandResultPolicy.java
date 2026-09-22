package com.deni.backend.device;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Validate completion evidence by command, independently of current telemetry. */
final class CommandResultPolicy {
    static void validate(String kind, JsonNode result) {
        String status=result.path("status").asText();
        if(!Set.of("SUCCEEDED","FAILED").contains(status)) throw new IllegalArgumentException("Invalid result status");
        if(!status.equals("SUCCEEDED")) return;
        String state=result.path("operationState").asText();
        boolean valid=switch(kind) {
            case "PAUSE", "POWER_OFF" -> state.equals("PAUSED");
            case "RESUME" -> state.equals("RUNNING");
            case "POWER_ON", "DIRECT_REMOVAL_CHECK", "RELOCATE" -> Set.of("RUNNING","PAUSED").contains(state);
            default -> false;
        };
        if(!valid) throw new IllegalArgumentException("Command result state mismatch");
        if(kind.equals("DIRECT_REMOVAL_CHECK")) {
            if(!result.path("hazardPresent").isBoolean() || result.path("hazardPresent").asBoolean()
                    || result.path("absenceDurationMs").asLong(0)<2000)
                throw new IllegalArgumentException("Removal requires two seconds of valid absence observations");
        }
        if(kind.equals("RELOCATE") && !result.path("relocationCompleted").asBoolean(false))
            throw new IllegalArgumentException("Relocation not completed");
    }
}
