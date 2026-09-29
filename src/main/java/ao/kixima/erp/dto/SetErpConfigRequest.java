package ao.kixima.erp.dto;

import java.util.Map;

public record SetErpConfigRequest(String erp, Map<String, Object> config) {
}
