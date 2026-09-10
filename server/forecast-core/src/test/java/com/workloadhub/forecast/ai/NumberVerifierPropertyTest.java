package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.ai.NumberVerifier.Report;
import com.workloadhub.forecast.data.ExportFiles;
import java.util.List;
import java.util.StringJoiner;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.Size;
import tools.jackson.databind.JsonNode;

class NumberVerifierPropertyTest {

    static JsonNode facts(List<Double> values) {
        StringJoiner totals = new StringJoiner(",");
        for (double v : values) {
            totals.add("{\"week\": \"2026-09-07\", \"demand\": " + v + "}");
        }
        return ExportFiles.mapper().readTree("{\"run\": {\"weeks\": [\"2026-09-07\"]}, \"team\": {\"totals\": [" + totals + "]}, \"members\": []}");
    }

    static NarrativeContract.Narrative withSummary(String runSummary) {
        return NarrativeContract.parse("{\"run_summary\": \"" + runSummary + "\", \"members\": []}");
    }

    @Property
    void everyFactWrittenWithOneDecimalInATeamFieldIsVerified(@ForAll @Size(min = 1, max = 8) List<@DoubleRange(min = 0, max = 500) Double> values) {
        StringJoiner text = new StringJoiner(", ");
        for (double v : values) {
            text.add(Double.toString(NumberVerifier.round1(v)) + " h");
        }
        Report r = NumberVerifier.verify(withSummary("Totals: " + text + "."), facts(values));
        assertTrue(r.ok(), r.unverified().toString());
        assertTrue(r.checked() == values.size());
    }

    @Property
    void aNumberAbsentFromTheFactsIsReportedWithItsField(@ForAll @Size(min = 1, max = 8) List<@DoubleRange(min = 0, max = 500) Double> values) {
        double invented = NumberVerifier.round1(values.stream().mapToDouble(Double::doubleValue).max().orElse(0)) + 7.3;
        Report r = NumberVerifier.verify(withSummary("Demand will reach " + Double.toString(NumberVerifier.round1(invented)) + " h."), facts(values));
        assertTrue(!r.ok(), "invented " + invented);
        assertTrue(r.unverified().get(0).startsWith("run_summary:"), r.unverified().toString());
    }
}
