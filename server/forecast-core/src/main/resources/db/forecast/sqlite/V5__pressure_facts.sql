-- A forecast of logged hours says what a member will get through, never what is waiting for them
-- (design 2026-09-13, section 8). The default is what makes these addable: SQLite refuses ADD COLUMN
-- NOT NULL without one, and PostgreSQL refuses it on a table with rows. Every writer supplies the value;
-- zero is the truthful reading of "no pressure recorded".
ALTER TABLE forecast_member_windows ADD COLUMN backlog_excess_hrs REAL NOT NULL DEFAULT 0;
ALTER TABLE forecast_member_windows ADD COLUMN due_excess_hrs REAL NOT NULL DEFAULT 0;
