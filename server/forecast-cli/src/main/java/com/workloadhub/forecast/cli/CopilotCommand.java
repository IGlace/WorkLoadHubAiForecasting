package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.api.CopilotStatus;
import com.workloadhub.forecast.api.ForecastException;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "copilot", description = "Copilot access: token presence, runtime, sign-in and quota.", subcommands = CopilotCommand.Status.class)
public class CopilotCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("usage: copilot status --user <name or id> [--db file]");
        return 2;
    }

    @Command(name = "status", description = "Whether this user can narrate: token, runtime, sign-in and quota.")
    public static class Status implements Callable<Integer> {

        @Mixin DbOptions db;

        @Option(names = "--user", required = true, description = "User, name or id")
        String user;

        @Override
        public Integer call() throws Exception {
            try (Services s = Services.open(db.dataSource())) {
                UUID userId;
                try {
                    userId = TeamArg.resolveUser(s.jdbc(), s.dialect(), user);
                } catch (IllegalArgumentException e) {
                    System.err.println("error: " + e.getMessage());
                    return 2;
                }
                CopilotStatus st;
                try {
                    st = s.service().copilotStatus(userId);
                } catch (ForecastException e) {
                    System.err.println("error: " + e.code() + ": " + e.getMessage());
                    return e.code().equals("TOKEN_KEY_MISSING") ? 2 : 1;
                }
                System.out.println("user: " + st.userId());
                System.out.println("hasToken: " + st.hasToken());
                System.out.println("runtimeAvailable: " + st.runtimeAvailable());
                System.out.println("runtimePath: " + (st.runtimePath() == null ? "-" : st.runtimePath()));
                System.out.println("runtimeVersion: " + st.runtimeVersion());
                System.out.println("authenticated: " + (st.authenticated() == null ? "-" : st.authenticated()));
                System.out.println("login: " + (st.login() == null ? "-" : st.login()));
                System.out.println("quota: " + (st.quotaJson() == null ? "-" : st.quotaJson()));
                System.out.println("message: " + st.message());
                return 0;
            }
        }
    }
}
