package sn.coophabitat.common;

import tools.jackson.databind.json.JsonMapper;

/** Sérialisation JSON utilitaire (instantanés, détails d'audit). */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }

    public static JsonMapper mapper() {
        return MAPPER;
    }
}
