package com.workloadhub.forecast.eval;

import com.workloadhub.forecast.api.AccuracyResult;
import com.workloadhub.forecast.api.AccuracyRow;
import com.workloadhub.forecast.api.AccuracyScore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** accuracy.csv (one row per member and day) and summary.md (the scores by scope). */
public final class AccuracyReport {

    static final String CSV_HEADER = "member_id,day,run_id,lead,forecast,truth,capacity,forecast_overload,actual_overload";
    static final String TABLE_HEADER = "| scope | key | n | mae | bias | mase | mase_n | overload_precision | overload_recall |";

    private AccuracyReport() {
    }

    public static Path write(AccuracyResult result, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        StringBuilder csv = new StringBuilder(CSV_HEADER).append('\n');
        for (AccuracyRow r : result.current()) {
            csv.append(r.userId()).append(',').append(r.day()).append(',').append(r.runId()).append(',').append(r.lead()).append(',')
                    .append(Report.csv(r.forecastHrs())).append(',').append(Report.csv(r.loggedHrs())).append(',').append(Report.csv(r.capacityHrs()))
                    .append(',').append(r.forecastOverload() ? 1 : 0).append(',').append(r.actualOverload() ? 1 : 0).append('\n');
        }
        Files.writeString(outDir.resolve("accuracy.csv"), csv.toString(), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("summary.md"), summary(result), StandardCharsets.UTF_8);
        return outDir;
    }

    static String summary(AccuracyResult result) {
        StringBuilder md = new StringBuilder();
        md.append("# Forecast accuracy, team ").append(result.teamId()).append(", ").append(result.from()).append(" to ").append(result.to())
                .append(", evaluated ").append(result.evaluatedAt()).append("\n\n");
        md.append("Truth: ").append(Truth.SOURCE).append(", summed per member and weekday; a weekday without a log counts as zero hours. Forecast: the")
                .append(" current forecast made before each day (team and member rows) and every finished run's day rows (lead rows; lead 1 is the")
                .append(" first weekday after the run day). Overload precision and recall compare forecast overload with logged hours above capacity.")
                .append(" Hours are often logged days late: the most recent days read low and the bias there is the logs', not the forecast's.")
                .append(" MASE here is daily, each day against the same weekday one week earlier, scored over the `mase_n` rows that have such a log;")
                .append(" it is not the weekly arrival MASE the run and the backtest report. ").append(result.nonWorkingDays())
                .append(" weekdays were holidays or full absences and are not scored. The team and member rows read the current forecast while the")
                .append(" lead rows pool every finished run's day rows, so a day counts once per run there; `accuracy.csv` holds the current rows only.")
                .append("\n\n");
        md.append(TABLE_HEADER).append('\n').append("|---|---|---|---|---|---|---|---|---|\n");
        for (AccuracyScore s : result.scores()) {
            md.append("| ").append(s.scope()).append(" | ").append(s.key()).append(" | ").append(s.n()).append(" | ").append(Report.csv(s.mae()))
                    .append(" | ").append(Report.csv(s.bias())).append(" | ").append(Report.csv(s.mase())).append(" | ").append(s.maseN())
                    .append(" | ").append(Report.csv(s.overloadPrecision())).append(" | ").append(Report.csv(s.overloadRecall())).append(" |\n");
        }
        return md.toString();
    }
}
