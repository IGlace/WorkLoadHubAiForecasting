CREATE TABLE forecast_runs (
  id              uuid PRIMARY KEY,
  team_id         uuid NOT NULL,
  requested_by    uuid NOT NULL,
  as_of           date NOT NULL,
  status          varchar(16) NOT NULL,
  forced_model    varchar(32),
  champion_model  varchar(32),
  champion_mase   double precision,
  backtest_json   text,
  error           text,
  created_at      timestamp NOT NULL,
  finished_at     timestamp
);
CREATE INDEX forecast_runs_team_idx ON forecast_runs (team_id, created_at);

CREATE TABLE forecast_member_weeks (
  run_id          uuid NOT NULL REFERENCES forecast_runs(id),
  user_id         uuid NOT NULL,
  week_start      date NOT NULL,
  open_hrs        double precision NOT NULL,
  new_hrs         double precision NOT NULL,
  planned_hrs     double precision NOT NULL,
  low_hrs         double precision NOT NULL,
  high_hrs        double precision NOT NULL,
  capacity_hrs    double precision NOT NULL,
  overload_hrs    double precision NOT NULL,
  working_days    integer NOT NULL,
  absence_hrs     double precision NOT NULL,
  PRIMARY KEY (run_id, user_id, week_start)
);

CREATE TABLE forecast_facts (
  run_id          uuid PRIMARY KEY REFERENCES forecast_runs(id),
  facts_json      text NOT NULL,
  created_at      timestamp NOT NULL
);

CREATE TABLE forecast_narratives (
  id                uuid PRIMARY KEY,
  run_id            uuid NOT NULL REFERENCES forecast_runs(id),
  language          varchar(2) NOT NULL,
  model             varchar(64),
  narrative_json    text NOT NULL,
  verification_json text NOT NULL,
  usage_json        text NOT NULL,
  created_at        timestamp NOT NULL
);
CREATE INDEX forecast_narratives_run_idx ON forecast_narratives (run_id, language, created_at);

ALTER TABLE users ADD COLUMN github_token varchar(512);
ALTER TABLE users ADD COLUMN github_token_updated_at timestamp;
