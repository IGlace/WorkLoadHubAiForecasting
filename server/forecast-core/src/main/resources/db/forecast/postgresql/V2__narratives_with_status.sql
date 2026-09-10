-- forecast_narratives was empty in every deployment before narration existed; recreate it with the
-- status columns rather than patch it: a FAILED narration keeps its cost and its last answer.
DROP TABLE forecast_narratives;
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
