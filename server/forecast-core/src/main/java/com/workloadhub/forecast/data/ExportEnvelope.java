package com.workloadhub.forecast.data;

import java.util.LinkedHashMap;
import java.util.List;

/** The WorkloadHub JSON export: metadata plus every table's rows, in file order. */
public record ExportEnvelope(
        String database,
        String schema,
        String exportedAt,
        List<String> excludedTables,
        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data) {

    public ExportEnvelope {
        excludedTables = excludedTables == null ? List.of() : List.copyOf(excludedTables);
        data = data == null ? new LinkedHashMap<>() : data;
    }

    /** The rows of one table, or an empty list when the export has none. */
    public List<LinkedHashMap<String, Object>> rows(String table) {
        return data.getOrDefault(table, List.of());
    }

    public ExportEnvelope withData(LinkedHashMap<String, List<LinkedHashMap<String, Object>>> newData) {
        return new ExportEnvelope(database, schema, exportedAt, excludedTables, newData);
    }
}
