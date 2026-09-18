CREATE TABLE forecast_runs (
  id            uuid PRIMARY KEY,
  team_id       uuid NOT NULL,
  requested_by  uuid NOT NULL,
  as_of         date NOT NULL,
  status        varchar(16) NOT NULL,
  mae           double precision,
  backtest_json text,
  error         text,
  created_at    timestamp NOT NULL,
  finished_at   timestamp
);
CREATE INDEX forecast_runs_team_idx ON forecast_runs (team_id, created_at);
CREATE INDEX forecast_runs_requested_by_idx ON forecast_runs (requested_by, created_at DESC);

CREATE TABLE forecast_member_windows (
  run_id             uuid NOT NULL REFERENCES forecast_runs(id),
  user_id            uuid NOT NULL,
  window_index       integer NOT NULL,
  window_start       date NOT NULL,
  window_end         date NOT NULL,
  demand_hrs         double precision NOT NULL,
  low_hrs            double precision NOT NULL,
  high_hrs           double precision NOT NULL,
  capacity_hrs       double precision NOT NULL,
  overload_hrs       double precision NOT NULL,
  working_days       integer NOT NULL,
  absence_hrs        double precision NOT NULL,
  backlog_excess_hrs double precision NOT NULL,
  due_excess_hrs     double precision NOT NULL,
  PRIMARY KEY (run_id, user_id, window_index)
);

CREATE TABLE forecast_member_days (
  run_id        uuid NOT NULL REFERENCES forecast_runs(id),
  user_id       uuid NOT NULL,
  day           date NOT NULL,
  window_index  integer NOT NULL,
  demand_hrs    double precision NOT NULL,
  capacity_hrs  double precision NOT NULL,
  overload_hrs  double precision NOT NULL,
  working_day   boolean NOT NULL,
  PRIMARY KEY (run_id, user_id, day)
);

CREATE TABLE forecast_current_days (
  team_id       uuid NOT NULL,
  user_id       uuid NOT NULL,
  day           date NOT NULL,
  run_id        uuid NOT NULL REFERENCES forecast_runs(id),
  demand_hrs    double precision NOT NULL,
  capacity_hrs  double precision NOT NULL,
  overload_hrs  double precision NOT NULL,
  forecast_at   timestamp NOT NULL,
  PRIMARY KEY (team_id, user_id, day)
);
CREATE INDEX forecast_current_days_team_idx ON forecast_current_days (team_id, day);

CREATE TABLE forecast_facts (
  run_id      uuid PRIMARY KEY REFERENCES forecast_runs(id),
  facts_json  text NOT NULL,
  created_at  timestamp NOT NULL
);

CREATE TABLE forecast_narratives (
  id                uuid PRIMARY KEY,
  run_id            uuid NOT NULL REFERENCES forecast_runs(id),
  language          varchar(2) NOT NULL,
  status            varchar(16) NOT NULL,
  model             text,
  narrative_json    text,
  raw_text          text,
  verification_json text NOT NULL,
  usage_json        text NOT NULL,
  error             text,
  attempts          integer NOT NULL,
  tool_calls        integer NOT NULL,
  created_at        timestamp NOT NULL
);
CREATE INDEX forecast_narratives_run_idx ON forecast_narratives (run_id, language, created_at);

ALTER TABLE users ADD COLUMN github_token varchar(512);
ALTER TABLE users ADD COLUMN github_token_updated_at timestamp;
