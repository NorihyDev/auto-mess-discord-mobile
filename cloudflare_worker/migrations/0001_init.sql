CREATE TABLE licenses (
  code_hash TEXT PRIMARY KEY,
  label TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  revoked_at INTEGER,
  device_id TEXT,
  public_key TEXT,
  model TEXT,
  first_seen INTEGER,
  last_seen INTEGER
);
CREATE TABLE nonces (
  nonce TEXT PRIMARY KEY,
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_nonces_created_at ON nonces(created_at);
