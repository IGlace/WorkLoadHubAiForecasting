package com.workloadhub.forecast.samplehost;

import com.workloadhub.forecast.ai.CopilotGateway;
import com.workloadhub.forecast.ai.FakeGateway;
import com.workloadhub.forecast.data.ExportImporter;
import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import com.workloadhub.forecast.testing.SeededData;
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

    @Bean
    CopilotGateway copilotGateway() {
        return new FakeGateway();
    }
}
