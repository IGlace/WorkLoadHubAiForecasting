package com.workloadhub.forecast;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Every whf.* property, with the defaults of the design. */
@ConfigurationProperties(prefix = "whf")
public class ForecastProperties {

    private String tokenKey;
    private String workDir = System.getProperty("user.home") + "/.workloadhub-forecast";
    private double defaultWeeklyHours = 40.0;
    private int runThreads = 2;
    private final PlannedWork plannedWork = new PlannedWork();
    private final Copilot copilot = new Copilot();
    private final Web web = new Web();
    private final Flyway flyway = new Flyway();

    public String getTokenKey() { return tokenKey; }
    public void setTokenKey(String tokenKey) { this.tokenKey = tokenKey; }
    public String getWorkDir() { return workDir; }
    public void setWorkDir(String workDir) { this.workDir = workDir; }
    public double getDefaultWeeklyHours() { return defaultWeeklyHours; }
    public void setDefaultWeeklyHours(double v) { this.defaultWeeklyHours = v; }
    public int getRunThreads() { return runThreads; }
    public void setRunThreads(int runThreads) { this.runThreads = runThreads; }
    public PlannedWork getPlannedWork() { return plannedWork; }
    public Copilot getCopilot() { return copilot; }
    public Web getWeb() { return web; }
    public Flyway getFlyway() { return flyway; }

    public static class PlannedWork {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Copilot {
        private String model = "";
        private String cliPath = "";
        private int timeoutSeconds = 300;
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getCliPath() { return cliPath; }
        public void setCliPath(String cliPath) { this.cliPath = cliPath; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public static class Web {
        private boolean enabled = false;
        private String basePath = "/api/forecast";
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBasePath() { return basePath; }
        public void setBasePath(String basePath) { this.basePath = basePath; }
    }

    public static class Flyway {
        private boolean enabled = true;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
