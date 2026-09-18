package com.workloadhub.forecast;

import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one Jackson mapper of the module: the facts, the narratives, the usage and the backtest JSON are all
 * written with it. Indented, with "\n" line endings regardless of platform, so a written file is byte-identical
 * on Windows and Linux: the default pretty printer's indenter otherwise uses {@code line.separator}.
 */
public final class Json {

    private static final DefaultIndenter LF_INDENTER = new DefaultIndenter("  ", "\n");
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .defaultPrettyPrinter(new DefaultPrettyPrinter()
                    .withObjectIndenter(LF_INDENTER)
                    .withArrayIndenter(LF_INDENTER))
            .build();

    private Json() {
    }

    public static JsonMapper mapper() {
        return MAPPER;
    }
}
