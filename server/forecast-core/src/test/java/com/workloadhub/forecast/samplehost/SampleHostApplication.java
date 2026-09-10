package com.workloadhub.forecast.samplehost;

import com.workloadhub.forecast.ai.CopilotGateway;
import com.workloadhub.forecast.ai.FakeGateway;
import com.workloadhub.forecast.data.ExportImporter;
import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import com.workloadhub.forecast.testing.SeededData;
import java.time.Clock;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * What the WorkloadHub server looks like to this module: a Spring Boot application with its own DataSource, the
 * module on the classpath, and (here) a scripted Copilot gateway instead of the SDK. Nothing else is configured.
 */
@SpringBootApplication
public class SampleHostApplication {

    @Bean
    DataSource dataSource() {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        new ExportImporter(ds).importAll(SeededData.envelope(), true);
        return ds;
    }

    /** The host pins the run day to the seed's end so the horizon lands where the data is; a real host has none of this. */
    @Bean
    Clock clock() {
        return Clock.fixed(SeededData.asOf().atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    @Bean
    CopilotGateway copilotGateway() {
        return new FakeGateway();
    }
}
