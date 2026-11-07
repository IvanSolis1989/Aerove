-- Fixed schema22 declarations from caf40edfdd957af5324bb5ee524c3bbaa0c42065

-- Source transcription, not an old-binary SQLite capture. No schema23 declarations.

CREATE TABLE "profiles" (
  "id" INTEGER NOT NULL,
  "type" TEXT NOT NULL,
  "label" TEXT NOT NULL,
  "current_group_name" TEXT,
  "url" TEXT NOT NULL,
  "age_secret_key" TEXT,
  "last_update_date" INTEGER,
  "last_update_date_millis" INTEGER,
  "extend_type" TEXT NOT NULL,
  "script_id" INTEGER,
  "match_target" TEXT,
  "auto_update_duration_millis" INTEGER NOT NULL,
  "subscription_info" TEXT,
  "auto_update" INTEGER NOT NULL CHECK ("auto_update" IN (0, 1)),
  "selected_map" TEXT NOT NULL,
  "unfold_set" TEXT NOT NULL,
  "order" INTEGER,
  "overrides" TEXT,
  PRIMARY KEY ("id")
);

CREATE TABLE "scripts" (
  "id" INTEGER NOT NULL,
  "label" TEXT NOT NULL,
  "last_update_time" INTEGER NOT NULL,
  "last_update_time_millis" INTEGER,
  "url" TEXT,
  "order" INTEGER,
  PRIMARY KEY ("id")
);

CREATE TABLE "rules" (
  "id" INTEGER NOT NULL,
  "profile_id" INTEGER REFERENCES profiles (id) ON DELETE CASCADE,
  "rule_action" TEXT NOT NULL,
  "content" TEXT,
  "rule_target" TEXT,
  "rule_provider" TEXT,
  "sub_rule" TEXT,
  "no_resolve" INTEGER NOT NULL CHECK ("no_resolve" IN (0, 1)) DEFAULT 0,
  "src" INTEGER NOT NULL CHECK ("src" IN (0, 1)) DEFAULT 0,
  "order" TEXT,
  PRIMARY KEY ("id")
);

CREATE TABLE "disabled_rules" (
  "profile_id" INTEGER NOT NULL REFERENCES profiles (id) ON DELETE CASCADE,
  "rule_id" INTEGER NOT NULL REFERENCES rules (id) ON DELETE CASCADE,
  PRIMARY KEY ("profile_id", "rule_id")
);

CREATE TABLE "proxy_groups" (
  "id" INTEGER NOT NULL,
  "profile_id" INTEGER NOT NULL REFERENCES profiles (id) ON DELETE CASCADE,
  "name" TEXT NOT NULL,
  "type" TEXT NOT NULL,
  "proxies" TEXT,
  "use" TEXT,
  "definition" TEXT NOT NULL DEFAULT '{}',
  "order" TEXT,
  PRIMARY KEY ("id")
);

CREATE TABLE "icon_records" (
  "url" TEXT NOT NULL,
  "last_accessed" INTEGER NOT NULL,
  PRIMARY KEY ("url")
);

CREATE TABLE "icon_sets" (
  "id" INTEGER NOT NULL,
  "name" TEXT NOT NULL,
  "url" TEXT NOT NULL,
  "icons" TEXT NOT NULL,
  "last_update_time" INTEGER,
  "last_update_time_millis" INTEGER,
  "order" INTEGER,
  PRIMARY KEY ("id")
);

CREATE TABLE "clash_providers" (
  "id" INTEGER NOT NULL,
  "kind" TEXT NOT NULL,
  "label" TEXT NOT NULL,
  "source_definition" TEXT,
  "url" TEXT NOT NULL,
  "behavior" TEXT,
  "format" TEXT,
  "order" INTEGER,
  PRIMARY KEY ("id")
);

CREATE TABLE "custom_proxies" (
  "id" INTEGER NOT NULL,
  "definition" TEXT NOT NULL,
  "order" TEXT,
  "empty_options" TEXT,
  PRIMARY KEY ("id")
);

CREATE TABLE "proxy_dialers" (
  "profile_id" INTEGER NOT NULL REFERENCES profiles (id) ON DELETE CASCADE,
  "proxy_id" INTEGER NOT NULL REFERENCES custom_proxies (id) ON DELETE CASCADE,
  "target" TEXT NOT NULL,
  PRIMARY KEY ("profile_id", "proxy_id")
);

CREATE TABLE "ip_qualities" (
  "ip" TEXT NOT NULL,
  "quality" TEXT NOT NULL,
  "checked_at" INTEGER NOT NULL,
  PRIMARY KEY ("ip")
);

PRAGMA user_version=22;
