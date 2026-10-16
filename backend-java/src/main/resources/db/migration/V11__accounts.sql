CREATE TABLE app_users (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    workspace_schema VARCHAR(64) NOT NULL UNIQUE,
    is_admin BOOLEAN NOT NULL DEFAULT FALSE,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at BIGINT NOT NULL
);
CREATE TABLE app_sessions (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    expires_at BIGINT NOT NULL
);
CREATE INDEX idx_app_sessions_user ON app_sessions(user_id);
CREATE INDEX idx_app_sessions_expiry ON app_sessions(expires_at);
