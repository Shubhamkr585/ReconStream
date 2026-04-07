-- Flyway Migration V4: Users and Refresh Tokens for RBAC & Cookie JWT Authentication

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens(user_id);

-- Seed demo accounts with BCrypt hash for 'password123'
INSERT INTO users (id, email, password_hash, role) VALUES
    ('00000000-0000-0000-0000-000000000001', 'admin@reconstream.dev', '$2a$10$mYQByljc2dZBzf9HIWMH2eF5tzQ4zxxYvSOL1yKrEoB2GBTlA9mA6', 'ADMIN'),
    ('00000000-0000-0000-0000-000000000002', 'auditor@reconstream.dev', '$2a$10$mYQByljc2dZBzf9HIWMH2eF5tzQ4zxxYvSOL1yKrEoB2GBTlA9mA6', 'AUDITOR'),
    ('00000000-0000-0000-0000-000000000003', 'analyst@reconstream.dev', '$2a$10$mYQByljc2dZBzf9HIWMH2eF5tzQ4zxxYvSOL1yKrEoB2GBTlA9mA6', 'ANALYST')
ON CONFLICT (email) DO NOTHING;
