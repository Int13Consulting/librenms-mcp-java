package ca.int13.librenmsmcp.json;

import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The shared Jackson mapper and small helpers. Serialization is compact, like upstream's tool text. */
public final class Json {
    public static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private Json() {}

    public static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    public static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    public static JsonNode parse(byte[] json) {
        return MAPPER.readTree(json);
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static JsonNode toNode(Object value) {
        return MAPPER.valueToTree(value);
    }

    public static Map<String, Object> toMap(JsonNode node) {
        return MAPPER.convertValue(node, MAP);
    }
}
