package com.workloadhub.forecast.cli;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IFactory;

/** Entry point: `java -jar workloadhub-forecast-cli.jar <command> [options]`. */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class ForecastCli {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(ForecastCli.class, args)));
    }

    @Command(name = "forecast", mixinStandardHelpOptions = true, version = "0.1.0",
            description = "WorkloadHub forecast: database, seed, runs and narration from the terminal.",
            subcommands = {InitDbCommand.class, ImportCommand.class, ExportCommand.class})
    @Component
    public static class Root {
    }

    @Component
    public static class Runner implements CommandLineRunner, ExitCodeGenerator {
        private final IFactory factory;
        private final Root root;
        private int exitCode;

        public Runner(IFactory factory, Root root) {
            this.factory = factory;
            this.root = root;
        }

        @Override
        public void run(String... args) {
            exitCode = new CommandLine(root, factory).execute(args);
        }

        @Override
        public int getExitCode() {
            return exitCode;
        }
    }
}
