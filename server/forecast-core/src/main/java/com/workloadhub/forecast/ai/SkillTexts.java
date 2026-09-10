package com.workloadhub.forecast.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The six product skills, classpath resources read once, in the order they appear in the system message. */
final class SkillTexts {

    static final List<String> NAMES = List.of("whf-domain", "whf-forecast-interpretation", "whf-pattern-discovery", "whf-likely-work",
            "whf-rebalancing-advice", "whf-report-style");

    record Skill(String name, String text) {

        /** The Markdown body without the YAML front matter, for embedding in the system message. */
        String body() {
            if (!text.startsWith("---\n")) {
                return text;
            }
            int end = text.indexOf("\n---\n", 4);
            return end < 0 ? text : text.substring(end + 5).strip();
        }
    }

    private SkillTexts() {
    }

    static List<Skill> load() {
        List<Skill> out = new ArrayList<>();
        for (String name : NAMES) {
            out.add(new Skill(name, resource("skills/" + name + "/SKILL.md")));
        }
        return List.copyOf(out);
    }

    static String resource(String path) {
        try (InputStream in = SkillTexts.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }
}
