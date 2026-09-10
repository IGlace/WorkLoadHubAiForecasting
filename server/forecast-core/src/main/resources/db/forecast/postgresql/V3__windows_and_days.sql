-- The horizon became two windows of five weekdays starting the first weekday after the run
-- (design 2026-09-10). No deployment stored member weeks: the table is dropped, not migrated.
DROP TABLE forecast_member_weeks;

CREATE TABLE forecast_member_windows (
  run_id          uuid NOT NULL REFERENCES forecast_runs(id),
  user_id         uuid NOT NULL,
  window_index    integer NOT NULL,
  window_start    date NOT NULL,
  window_end      date NOT NULL,
  open_hrs        double precision NOT NULL,
  new_hrs         double precision NOT NULL,
  planned_hrs     double precision NOT NULL,
  demand_hrs      double precision NOT NULL,
  low_hrs         double precision NOT NULL,
  high_hrs        double precision NOT NULL,
  capacity_hrs    double precision NOT NULL,
  overload_hrs    double precision NOT NULL,
  working_days    integer NOT NULL,
  absence_hrs     double precision NOT NULL,
  PRIMARY KEY (run_id, user_id, window_index)
);

CREATE TABLE forecast_member_days (
  run_id          uuid NOT NULL REFERENCES forecast_runs(id),
  user_id         uuid NOT NULL,
  day             date NOT NULL,
  window_index    integer NOT NULL,
  open_hrs        double precision NOT NULL,
  new_hrs         double precision NOT NULL,
  planned_hrs     double precision NOT NULL,
  demand_hrs      double precision NOT NULL,
  capacity_hrs    double precision NOT NULL,
  overload_hrs    double precision NOT NULL,
  working_day     boolean NOT NULL,
  PRIMARY KEY (run_id, user_id, day)
);

-- The current forecast: each run upserts its ten days per member, so days ahead are overwritten
-- and days that have arrived keep the last forecast made before them.
CREATE TABLE forecast_current_days (
  team_id         uuid NOT NULL,
  user_id         uuid NOT NULL,
  day             date NOT NULL,
  run_id          uuid NOT NULL REFERENCES forecast_runs(id),
  open_hrs        double precision NOT NULL,
  new_hrs         double precision NOT NULL,
  planned_hrs     double precision NOT NULL,
  demand_hrs      double precision NOT NULL,
  capacity_hrs    double precision NOT NULL,
  overload_hrs    double precision NOT NULL,
  forecast_at     timestamp NOT NULL,
  PRIMARY KEY (team_id, user_id, day)
);
CREATE INDEX forecast_current_days_team_idx ON forecast_current_days (team_id, day);
