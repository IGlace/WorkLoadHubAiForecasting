CREATE TABLE forecast_runs (
  id              TEXT PRIMARY KEY,
  team_id         TEXT NOT NULL,
  requested_by    TEXT NOT NULL,
  as_of           TEXT NOT NULL,
  status          TEXT NOT NULL,
  forced_model    TEXT,
  champion_model  TEXT,
  champion_mase   REAL,
  backtest_json   TEXT,
  error           TEXT,
  created_at      TEXT NOT NULL,
  finished_at     TEXT
);
CREATE INDEX forecast_runs_team_idx ON forecast_runs (team_id, created_at);

CREATE TABLE forecast_member_weeks (
  run_id          TEXT NOT NULL REFERENCES forecast_runs(id),
  user_id         TEXT NOT NULL,
  week_start      TEXT NOT NULL,
  open_hrs        REAL NOT NULL,
  new_hrs         REAL NOT NULL,
  planned_hrs     REAL NOT NULL,
  low_hrs         REAL NOT NULL,
  high_hrs        REAL NOT NULL,
  capacity_hrs    REAL NOT NULL,
  overload_hrs    REAL NOT NULL,
  working_days    INTEGER NOT NULL,
  absence_hrs     REAL NOT NULL,
  PRIMARY KEY (run_id, user_id, week_start)
);

CREATE TABLE forecast_facts (
  run_id          TEXT PRIMARY KEY REFERENCES forecast_runs(id),
  facts_json      TEXT NOT NULL,
  created_at      TEXT NOT NULL
);

CREATE TABLE forecast_narratives (
  id                TEXT PRIMARY KEY,
  run_id            TEXT NOT NULL REFERENCES forecast_runs(id),
  language          TEXT NOT NULL,
  model             TEXT,
  narrative_json    TEXT NOT NULL,
  verification_json TEXT NOT NULL,
  usage_json        TEXT NOT NULL,
  created_at        TEXT NOT NULL
);
CREATE INDEX forecast_narratives_run_idx ON forecast_narratives (run_id, language, created_at);

ALTER TABLE users ADD COLUMN github_token TEXT;
ALTER TABLE users ADD COLUMN github_token_updated_at TEXT;
