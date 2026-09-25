package com.deni.backend.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/hardware")
public class DetectionUploadController {
    private final DetectionUploadService service;
    private final String token;
    private final String configuredDevice;
    public DetectionUploadController(DetectionUploadService service,
            @Value("${robot.device-token:}") String token,@Value("${robot.device-id:}") String configuredDevice) {
        this.service=service; this.token=token; this.configuredDevice=configuredDevice;
    }
    @PostMapping(value="/detections",consumes="multipart/form-data")
    public DetectionUploadService.Receipt upload(@RequestHeader(value="Authorization",defaultValue="") String authorization,
            @RequestHeader("X-Device-Id") String deviceId,@RequestParam UUID eventId,
            @RequestParam String modelType,@RequestParam String objectLabel,@RequestParam MultipartFile image,
            @RequestParam(required=false) @org.springframework.format.annotation.DateTimeFormat(
                    iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.OffsetDateTime capturedAt)
            throws java.io.IOException {
        if(token.length()<32 || !configuredDevice.equals(deviceId) || !MessageDigest.isEqual(
                ("Bearer "+token).getBytes(StandardCharsets.UTF_8),authorization.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return service.save(deviceId,eventId,modelType,objectLabel,image.getBytes(),capturedAt);
    }
}
