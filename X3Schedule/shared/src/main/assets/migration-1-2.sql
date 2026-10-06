CREATE TABLE suggestions (id TEXT PRIMARY KEY NOT NULL, plan_id TEXT NOT NULL, body TEXT NOT NULL, accepted INTEGER NOT NULL DEFAULT 0 CHECK(accepted IN (0,1)));
CREATE INDEX suggestions_plan ON suggestions(plan_id);
