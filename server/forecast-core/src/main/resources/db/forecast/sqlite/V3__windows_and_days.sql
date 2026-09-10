-- The horizon became two windows of five weekdays starting the first weekday after the run
-- (design 2026-09-10). No deployment stored member weeks: the table is dropped, not migrated.
DROP TABLE forecast_member_weeks;

CREATE TABLE forecast_member_windows (
  run_id          TEXT NOT NULL REFERENCES forecast_runs(id),
  user_id         TEXT NOT NULL,
  window_index    INTEGER NOT NULL,
  window_start    TEXT NOT NULL,
  window_end      TEXT NOT NULL,
  open_hrs        REAL NOT NULL,
  new_hrs         REAL NOT NULL,
  planned_hrs     REAL NOT NULL,
  demand_hrs      REAL NOT NULL,
  low_hrs         REAL NOT NULL,
  high_hrs        REAL NOT NULL,
  capacity_hrs    REAL NOT NULL,
  overload_hrs    REAL NOT NULL,
  working_days    INTEGER NOT NULL,
  absence_hrs     REAL NOT NULL,
  PRIMARY KEY (run_id, user_id, window_index)
);

CREATE TABLE forecast_member_days (
  run_id          TEXT NOT NULL REFERENCES forecast_runs(id),
  user_id         TEXT NOT NULL,
  day             TEXT NOT NULL,
  window_index    INTEGER NOT NULL,
  open_hrs        REAL NOT NULL,
  new_hrs         REAL NOT NULL,
  planned_hrs     REAL NOT NULL,
  demand_hrs      REAL NOT NULL,
  capacity_hrs    REAL NOT NULL,
  overload_hrs    REAL NOT NULL,
  working_day     INTEGER NOT NULL,
  PRIMARY KEY (run_id, user_id, day)
);

-- The current forecast: each run upserts its ten days per member, so days ahead are overwritten
-- and days that have arrived keep the last forecast made before them.
CREATE TABLE forecast_current_days (
  team_id         TEXT NOT NULL,
  user_id         TEXT NOT NULL,
  day             TEXT NOT NULL,
  run_id          TEXT NOT NULL REFERENCES forecast_runs(id),
  open_hrs        REAL NOT NULL,
  new_hrs         REAL NOT NULL,
  planned_hrs     REAL NOT NULL,
  demand_hrs      REAL NOT NULL,
  capacity_hrs    REAL NOT NULL,
  overload_hrs    REAL NOT NULL,
  forecast_at     TEXT NOT NULL,
  PRIMARY KEY (team_id, user_id, day)
);
CREATE INDEX forecast_current_days_team_idx ON forecast_current_days (team_id, day);
