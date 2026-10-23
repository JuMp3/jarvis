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

-- Every message ever exchanged, append only: chat_message keeps just the window the model sees and rewrites it
-- at each turn, and clearing the conversation empties it. The archive is the source of long-term memory (RAG).
CREATE TABLE IF NOT EXISTS chat_archive (
    id              BIGSERIAL   PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    type            VARCHAR(16) NOT NULL,
    content         TEXT,
    tool_calls      JSONB,
    tool_responses  JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS chat_archive_conversation_idx ON chat_archive (conversation_id, id);

-- Once, when the archive is introduced: what is still in the windows is all that is left of the past
INSERT INTO chat_archive (conversation_id, type, content, tool_calls, tool_responses)
SELECT conversation_id, type, content, tool_calls, tool_responses FROM chat_message
WHERE NOT EXISTS (SELECT 1 FROM chat_archive)
ORDER BY conversation_id, seq;

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

-- Phase 5: long-term memory (LongTermMemory). Passages of the archive and of the task results with their embedding
-- (multilingual-e5-small, 384 dimensions), in the layout of Spring AI's PgVectorStore; memory_progress says where
-- the indexing got to for each source.
CREATE TABLE IF NOT EXISTS memory_vector (
    id        UUID PRIMARY KEY,
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(384)
);

CREATE INDEX IF NOT EXISTS memory_vector_embedding_idx ON memory_vector USING hnsw (embedding vector_cosine_ops);

-- The documents indexed, with the modification time seen and how many passages they gave (to replace them)
CREATE TABLE IF NOT EXISTS memory_document (
    path     TEXT    PRIMARY KEY,
    modified BIGINT  NOT NULL,
    passages INT     NOT NULL
);

CREATE TABLE IF NOT EXISTS memory_progress (
    source   VARCHAR(16) PRIMARY KEY,
    position BIGINT      NOT NULL
);

-- Phase 3: coder tasks work on a repository, report live progress and their cost
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS workspace TEXT;
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS progress  TEXT;
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS cost_usd  DOUBLE PRECISION;
-- Finished tasks the user took off the Squadra panel: kept, their results serve long-term memory
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS hidden    BOOLEAN NOT NULL DEFAULT false;
-- Phase 6: a part of a task delegated by its specialist to another one
ALTER TABLE agent_task ADD COLUMN IF NOT EXISTS parent_id VARCHAR(36);

-- Phase 3b: settings saved by the wizard and the Settings section; secrets encrypted (AES-256-GCM, key outside
-- the database). Read at startup by SettingsEnvironmentPostProcessor, before application.yml and .env.
CREATE TABLE IF NOT EXISTS setting (
    name       VARCHAR(200) PRIMARY KEY,
    kind       VARCHAR(8)   NOT NULL CHECK (kind IN ('text', 'secret', 'list')),
    value      TEXT         NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Phase 6: routines, run by Jarvis by himself at their time (dev.jarvis.routine). Configuration, not history:
-- removing one deletes it; what it did stays in agent_task and in the conversation
CREATE TABLE IF NOT EXISTS routine (
    id           VARCHAR(36)  PRIMARY KEY,
    name         VARCHAR(200) NOT NULL,
    at_time      TIME         NOT NULL,
    days         VARCHAR(40)  NOT NULL,
    instructions TEXT         NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT true,
    last_run     DATE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
