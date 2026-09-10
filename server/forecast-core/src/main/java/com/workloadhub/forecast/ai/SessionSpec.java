package com.workloadhub.forecast.ai;

import java.util.List;

/** What one narration session is made of; {@code model} null means the account's default. */
public record SessionSpec(String model, String systemMessage, List<ToolSpec> tools) {
}
