-- The forecast is of hours logged, one figure per member and window, so the open/new/planned split goes
-- (design 2026-09-13). The tournament goes with it: no forced model, no champion, no MASE; the backtest
-- reports MAE in hours.
ALTER TABLE forecast_runs DROP COLUMN forced_model;
ALTER TABLE forecast_runs DROP COLUMN champion_model;
ALTER TABLE forecast_runs DROP COLUMN champion_mase;
ALTER TABLE forecast_runs ADD COLUMN mae REAL;

ALTER TABLE forecast_member_windows DROP COLUMN open_hrs;
ALTER TABLE forecast_member_windows DROP COLUMN new_hrs;
ALTER TABLE forecast_member_windows DROP COLUMN planned_hrs;

ALTER TABLE forecast_member_days DROP COLUMN open_hrs;
ALTER TABLE forecast_member_days DROP COLUMN new_hrs;
ALTER TABLE forecast_member_days DROP COLUMN planned_hrs;

ALTER TABLE forecast_current_days DROP COLUMN open_hrs;
ALTER TABLE forecast_current_days DROP COLUMN new_hrs;
ALTER TABLE forecast_current_days DROP COLUMN planned_hrs;
