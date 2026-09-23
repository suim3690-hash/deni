package com.deni.backend.device;

import java.time.OffsetDateTime;
import java.util.*;
import com.deni.backend.child.ChildService;
import com.deni.backend.common.ApiException;
import com.deni.backend.common.IdempotencyGuard;
import com.deni.backend.hazard.HazardService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DetectionUploadService {
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final DeviceService devices;
    private final ChildService children;
    private final HazardService hazards;
    private final IdempotencyGuard guard;
    public DetectionUploadService(JdbcTemplate jdbc,DeviceService devices,ChildService children,HazardService hazards,IdempotencyGuard guard) {
        this.jdbc=jdbc; this.devices=devices; this.children=children; this.hazards=hazards; this.guard=guard;
    }
    @Transactional public Receipt save(String id,UUID event,String model,String label,byte[] bytes) {
        if(event==null || !Set.of("HAZARD","OBJECT").contains(model) || label==null || label.isBlank() || label.length()>100
            || bytes==null || bytes.length==0 || bytes.length>5242880) throw invalid();
        String mime;
        if(bytes.length>=8 && Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10})) mime="image/png";
        else if(bytes.length>=3 && bytes[0]==(byte)255 && bytes[1]==(byte)216 && bytes[2]==(byte)255) mime="image/jpeg";
        else throw invalid();
        // Fully decode before accepting a frame; signature alone is insufficient.
        try { if(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes))==null) throw invalid(); }
        catch(java.io.IOException ex) { throw invalid(); }
        UUID child=devices.getLinkedChildId(id);
        guard.lock("raw-detection",event.toString());
        var existing=jdbc.queryForList("SELECT device_id,model_type,object_label,frame_image FROM detection_events WHERE event_id=?",event);
        if(!existing.isEmpty()) {
            var row=existing.getFirst();
            if(!id.equals(row.get("device_id")) || !model.equals(row.get("model_type")) || !label.equals(row.get("object_label"))
                || !Arrays.equals(bytes,(byte[])row.get("frame_image"))) throw ApiException.conflict("DETECTION_EVENT_REUSED","동일 이벤트에 다른 데이터가 있습니다.");
            UUID hazardId=jdbc.queryForObject("SELECT hazard_id FROM detection_events WHERE event_id=?",UUID.class,event);
            return new Receipt(event,hazardId);
        }
        jdbc.update("INSERT INTO detection_events(event_id,device_id,model_type,object_label,frame_image,image_content_type) VALUES (?,?,?,?,?,?)",event,id,model,label,bytes,mime);
        String category=List.of("전선","콘센트").stream().anyMatch(label::contains)?"LIVING":
            List.of("구슬","동전","배터리","주사위").stream().anyMatch(label::contains)?"SWALLOW":null;
        var profile=children.getSafetyProfile(child);
        if(category==null || profile.stage()==null) return new Receipt(event,null);
        String stage=String.valueOf(profile.stage());
        String risk=riskForStage(category,stage);
        OffsetDateTime detected=jdbc.queryForObject("SELECT detected_at FROM detection_events WHERE event_id=?",OffsetDateTime.class,event);
        var hazard=hazards.recordDetection(new HazardService.DetectionInput(child,id,category,label,risk,
            "성장단계별 "+category+" 분류 기준 (v2)",detected,null,null,null,null,
            "/api/v1/devices/"+id+"/detections/"+event+"/image","UNKNOWN",event.toString()));
        entityManager.flush();
        jdbc.update("UPDATE detection_events SET hazard_id=? WHERE event_id=?",hazard.hazardId(),event);
        return new Receipt(event,hazard.hazardId());
    }
    static String riskForStage(String category,String stage) {
        if(category.equals("SWALLOW")) return switch(stage) {
            case "INFANT" -> "HIGH";
            case "TODDLER" -> "VERY_HIGH";
            case "ACTIVE_CHILD" -> "MEDIUM";
            default -> throw new IllegalArgumentException("Unsupported growth stage");
        };
        if(category.equals("LIVING")) return stage.equals("ACTIVE_CHILD")?"VERY_HIGH":"HIGH";
        throw new IllegalArgumentException("Unsupported hazard category");
    }
    private ApiException invalid() { return ApiException.validation("탐지 값 또는 JPEG/PNG 이미지를 확인하세요.",Map.of("detection","유효한 라벨·모델·5MiB 이하 이미지가 필요합니다.")); }
    public record Receipt(UUID eventId,UUID hazardId) { }
}
