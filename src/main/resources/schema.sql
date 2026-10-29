-- ====================================================================
-- WINTER ARC PRO — PRODUCTION POSTGRESQL SCHEMA FOR MONETIZATION & SAAS
-- ====================================================================

-- Users Table
CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(36) PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    role VARCHAR(50) DEFAULT 'USER',
    status VARCHAR(50) DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);

-- Subscriptions Table
CREATE TABLE IF NOT EXISTS subscriptions (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tier VARCHAR(50) NOT NULL, -- 'PRO_MONTHLY', 'PRO_YEARLY', 'LIFETIME', 'FREE'
    status VARCHAR(50) NOT NULL, -- 'ACTIVE', 'PAST_DUE', 'CANCELLED', 'EXPIRED'
    payment_provider VARCHAR(50) NOT NULL, -- 'STRIPE', 'RAZORPAY', 'MANUAL'
    external_subscription_id VARCHAR(255),
    external_customer_id VARCHAR(255),
    current_period_start TIMESTAMP WITH TIME ZONE NOT NULL,
    current_period_end TIMESTAMP WITH TIME ZONE NOT NULL,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_subs_user_id ON subscriptions(user_id);
CREATE INDEX IF NOT EXISTS idx_subs_ext_id ON subscriptions(external_subscription_id);

-- Payment Orders Table (Razorpay & Stripe server-side order lifecycle)
CREATE TABLE IF NOT EXISTS payment_orders (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider VARCHAR(50) NOT NULL, -- 'RAZORPAY', 'STRIPE'
    plan VARCHAR(50) NOT NULL, -- 'PRO_MONTHLY', 'PRO_YEARLY', 'LIFETIME'
    amount BIGINT NOT NULL, -- in paise / cents
    currency VARCHAR(10) NOT NULL, -- 'INR', 'USD'
    razorpay_order_id VARCHAR(100) UNIQUE,
    razorpay_payment_id VARCHAR(100),
    razorpay_signature VARCHAR(255),
    status VARCHAR(50) NOT NULL, -- 'PENDING', 'PAID', 'FAILED', 'CANCELLED'
    idempotency_key VARCHAR(100),
    receipt VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    paid_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_orders_user_id ON payment_orders(user_id);
CREATE INDEX IF NOT EXISTS idx_orders_rzp_id ON payment_orders(razorpay_order_id);
CREATE INDEX IF NOT EXISTS idx_orders_status ON payment_orders(status);

-- Payment Transactions & Webhook Idempotency Table
CREATE TABLE IF NOT EXISTS payment_transactions (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) REFERENCES users(id) ON DELETE SET NULL,
    subscription_id VARCHAR(36) REFERENCES subscriptions(id) ON DELETE SET NULL,
    provider VARCHAR(50) NOT NULL, -- 'STRIPE', 'RAZORPAY'
    event_id VARCHAR(255) NOT NULL UNIQUE, -- Crucial for webhook idempotency
    event_type VARCHAR(100) NOT NULL,
    amount_cents BIGINT NOT NULL,
    currency VARCHAR(10) NOT NULL,
    status VARCHAR(50) NOT NULL, -- 'SUCCEEDED', 'FAILED', 'REFUNDED'
    order_id VARCHAR(100),
    payment_id VARCHAR(100),
    raw_payload TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tx_event_id ON payment_transactions(event_id);
CREATE INDEX IF NOT EXISTS idx_tx_user_id ON payment_transactions(user_id);
CREATE INDEX IF NOT EXISTS idx_tx_order_id ON payment_transactions(order_id);

-- Entitlement Tokens Audit Table
CREATE TABLE IF NOT EXISTS entitlement_tokens (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tier VARCHAR(50) NOT NULL,
    issued_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    offline_grace_seconds BIGINT NOT NULL,
    signature VARCHAR(512) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_tokens_user ON entitlement_tokens(user_id);

-- Subscription Grants Table (Complimentary / Administrative Pro Access)
CREATE TABLE IF NOT EXISTS subscription_grants (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    user_email VARCHAR(255) NOT NULL,
    subscription_id VARCHAR(36) REFERENCES subscriptions(id) ON DELETE SET NULL,
    admin_id VARCHAR(36) NOT NULL,
    admin_email VARCHAR(255) NOT NULL,
    grant_type VARCHAR(50) NOT NULL DEFAULT 'COMPLIMENTARY',
    tier VARCHAR(50) NOT NULL,
    duration_days INT,
    start_date TIMESTAMP WITH TIME ZONE NOT NULL,
    expiry_date TIMESTAMP WITH TIME ZONE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    reason VARCHAR(1000) NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    revoked_by_admin_id VARCHAR(36),
    revocation_reason VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_grants_user ON subscription_grants(user_id);
CREATE INDEX IF NOT EXISTS idx_grants_status ON subscription_grants(status);

-- Admin Audit Logs Table
CREATE TABLE IF NOT EXISTS admin_audit_logs (
    id VARCHAR(36) PRIMARY KEY,
    admin_id VARCHAR(36) NOT NULL,
    admin_email VARCHAR(255) NOT NULL,
    action VARCHAR(50) NOT NULL,
    target_user_id VARCHAR(36),
    target_user_email VARCHAR(255),
    reason VARCHAR(1000),
    outcome VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    details VARCHAR(4000),
    ip_address VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_created ON admin_audit_logs(created_at);
CREATE INDEX IF NOT EXISTS idx_audit_action ON admin_audit_logs(action);
CREATE INDEX IF NOT EXISTS idx_audit_target ON admin_audit_logs(target_user_id);

-- Announcements Table
CREATE TABLE IF NOT EXISTS announcements (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(120) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    type VARCHAR(30) NOT NULL DEFAULT 'INFO',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ann_active ON announcements(active, expires_at);

-- Promo Codes Table
CREATE TABLE IF NOT EXISTS promo_codes (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    discount_percent INT NOT NULL DEFAULT 0,
    tier_grant VARCHAR(32),
    duration_days INT,
    max_redemptions INT NOT NULL DEFAULT 100,
    times_redeemed INT NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_promo_code ON promo_codes(code);
