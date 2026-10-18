CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    roles         VARCHAR(100) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id),
    family_id   UUID        NOT NULL,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    used        BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
