-- shortcut: idempotent script run at every startup, no migrations; switch to Flyway at the first ALTER.

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
