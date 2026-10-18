-- the daily cleanup deletes by expiry
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);
