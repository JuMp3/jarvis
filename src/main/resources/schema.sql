-- shortcut: idempotent script run at every startup, no migrations; switch to Flyway at the first change
-- that cannot be written idempotently (renames, type changes, data moves).

-- Vector search for long-term memory and RAG (Phase 5)
CREATE EXTENSION IF NOT EXISTS vector;

-- Conversation memory, tool calls and tool results included (see PostgresChatMemoryRepository)
CREATE TABLE IF NOT EXISTS chat_message (
    conversation_id VARCHAR(64) NOT NULL,
    seq             INT         NOT NULL,
    type            VARCHAR(16) NOT NULL,
    content         TEXT,
    tool_calls      JSONB,
    tool_responses  JSONB,
    PRIMARY KEY (conversation_id, seq)
);

-- Background tasks delegated to specialists (see TaskService)
CREATE TABLE IF NOT EXISTS agent_task (
    id              VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    agent           VARCHAR(64) NOT NULL,
    title           TEXT        NOT NULL,
    instructions    TEXT        NOT NULL,
    expected_output TEXT,
    status          VARCHAR(16) NOT NULL,
    result          TEXT,
    error           TEXT,
    input_tokens    INT,
    output_tokens   INT,
    created_at      TIMESTAMPTZ NOT NULL,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS agent_task_conversation_idx ON agent_task (conversation_id, created_at);

-- Phase 3: coder tasks work on a repository, report live progress and their cost
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS workspace TEXT;
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS progress  TEXT;
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS cost_usd  DOUBLE PRECISION;

-- Phase 3b: settings saved by the wizard and the Settings section; secrets encrypted (AES-256-GCM, key outside
-- the database). Read at startup by SettingsEnvironmentPostProcessor, before application.yml and .env.
CREATE TABLE IF NOT EXISTS setting (
    name       VARCHAR(200) PRIMARY KEY,
    kind       VARCHAR(8)   NOT NULL CHECK (kind IN ('text', 'secret', 'list')),
    value      TEXT         NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
