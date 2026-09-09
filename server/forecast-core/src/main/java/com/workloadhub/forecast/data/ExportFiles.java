package com.workloadhub.forecast.data;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Reads and writes the export envelope. Numbers become Long or Double, nothing else. */
public final class ExportFiles {

    /**
     * "\n" line endings regardless of platform, so the written file is byte-identical on Windows and
     * Linux: the default pretty printer's indenter otherwise uses {@code line.separator}.
     */
    private static final DefaultIndenter LF_INDENTER = new DefaultIndenter("  ", "\n");

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .defaultPrettyPrinter(new DefaultPrettyPrinter()
                    .withObjectIndenter(LF_INDENTER)
                    .withArrayIndenter(LF_INDENTER))
            .build();

    private ExportFiles() {
    }

    public static JsonMapper mapper() {
        return MAPPER;
    }

    /** Reads a file as UTF-8, or as Windows-1252 when the bytes are not valid UTF-8 (the real export is). */
    public static ExportEnvelope read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            text = new String(bytes, Charset.forName("windows-1252"));
        }
        return parse(text);
    }

    public static ExportEnvelope parse(String json) {
        JsonNode root = MAPPER.readTree(json);
        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data = new LinkedHashMap<>();
        JsonNode tables = root.path("data");
        for (Iterator<Map.Entry<String, JsonNode>> it = tables.properties().iterator(); it.hasNext();) {
            Map.Entry<String, JsonNode> entry = it.next();
            List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
            for (JsonNode row : entry.getValue()) {
                LinkedHashMap<String, Object> map = new LinkedHashMap<>();
                for (Iterator<Map.Entry<String, JsonNode>> f = row.properties().iterator(); f.hasNext();) {
                    Map.Entry<String, JsonNode> field = f.next();
                    map.put(field.getKey(), scalar(field.getValue()));
                }
                rows.add(map);
            }
            data.put(entry.getKey(), rows);
        }
        List<String> excluded = new ArrayList<>();
        for (JsonNode n : root.path("excluded_tables")) {
            excluded.add(n.asString());
        }
        return new ExportEnvelope(
                text(root, "database"), text(root, "schema"), text(root, "exported_at"), excluded, data);
    }

    static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return n == null || n.isNull() ? null : n.asString();
    }

    static Object scalar(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isIntegralNumber()) {
            return n.asLong();
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        return n.asString();
    }

    public static String toJson(ExportEnvelope envelope) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("database", envelope.database());
        root.put("schema", envelope.schema());
        root.put("exported_at", envelope.exportedAt());
        root.put("excluded_tables", envelope.excludedTables());
        root.put("data", envelope.data());
        return MAPPER.writeValueAsString(root);
    }

    public static void write(Path file, ExportEnvelope envelope) throws IOException {
        Files.writeString(file, toJson(envelope), StandardCharsets.UTF_8);
    }
}
