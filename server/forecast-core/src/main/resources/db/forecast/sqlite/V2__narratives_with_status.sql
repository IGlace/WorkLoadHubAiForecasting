-- forecast_narratives was empty in every deployment before narration existed; recreate it with the
-- status columns rather than patch it: a FAILED narration keeps its cost and its last answer.
DROP TABLE forecast_narratives;
CREATE TABLE forecast_narratives (
  id                TEXT PRIMARY KEY,
  run_id            TEXT NOT NULL REFERENCES forecast_runs(id),
  language          TEXT NOT NULL,
  status            TEXT NOT NULL,
  model             TEXT,
  narrative_json    TEXT,
  raw_text          TEXT,
  verification_json TEXT NOT NULL,
  usage_json        TEXT NOT NULL,
  error             TEXT,
  attempts          INTEGER NOT NULL,
  tool_calls        INTEGER NOT NULL,
  created_at        TEXT NOT NULL
);
CREATE INDEX forecast_narratives_run_idx ON forecast_narratives (run_id, language, created_at);
