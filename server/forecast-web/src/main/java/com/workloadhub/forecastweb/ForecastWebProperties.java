package com.workloadhub.forecastweb;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Every forecast-web.* property (design 2026-09-18, section 3.4). All of it is demo wiring; the whf.* properties are the module's. */
@ConfigurationProperties(prefix = "forecast-web")
public class ForecastWebProperties {

    private String tokenKeyFile = System.getProperty("user.home") + "/.workloadhub-forecast/forecast-web/token.key";
    private String uiDir = "ui/dist";
    private final ClockSettings clock = new ClockSettings();

    public String getTokenKeyFile() { return tokenKeyFile; }
    public void setTokenKeyFile(String tokenKeyFile) { this.tokenKeyFile = tokenKeyFile; }
    public String getUiDir() { return uiDir; }
    public void setUiDir(String uiDir) { this.uiDir = uiDir; }
    public ClockSettings getClock() { return clock; }

    public static class ClockSettings {
        /** The demo clock's date; null or blank means the system date. */
        private String today = "2026-06-28";
        private boolean adjustable = true;
        public String getToday() { return today; }
        public void setToday(String today) { this.today = today; }
        public boolean isAdjustable() { return adjustable; }
        public void setAdjustable(boolean adjustable) { this.adjustable = adjustable; }
    }
}
