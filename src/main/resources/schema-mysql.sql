CREATE TABLE IF NOT EXISTS accounts (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    industry VARCHAR(100) NOT NULL,
    licensed_seats INT NOT NULL,
    active_seats INT NOT NULL,
    sponsor_name VARCHAR(100) NOT NULL,
    sponsor_last_login_days INT NOT NULL,
    renewal_days INT NOT NULL,
    archetype VARCHAR(64) NOT NULL,
    annual_revenue DECIMAL(14,2) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS usage_daily (
    account_id VARCHAR(64) NOT NULL,
    usage_date DATE NOT NULL,
    active_users INT NOT NULL,
    feature_adoption DOUBLE NOT NULL,
    usage_events BIGINT NOT NULL,
    admin_active BOOLEAN NOT NULL,
    PRIMARY KEY (account_id, usage_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tickets (
    id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL,
    priority VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    satisfaction INT NOT NULL,
    opened_at TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS invoices (
    id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL,
    amount DECIMAL(14,2) NOT NULL,
    due_date DATE NOT NULL,
    status VARCHAR(32) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS risk_candidates (
    account_id VARCHAR(64) PRIMARY KEY,
    score DOUBLE NOT NULL,
    severity VARCHAR(16) NOT NULL,
    factors_json TEXT NOT NULL,
    scanned_at TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS investigations (
    id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL,
    question TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    risk_score DOUBLE,
    summary TEXT,
    root_cause TEXT,
    action_json TEXT,
    error TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_steps (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    investigation_id VARCHAR(64) NOT NULL,
    seq_no INT NOT NULL,
    step_type VARCHAR(32) NOT NULL,
    title VARCHAR(300) NOT NULL,
    detail TEXT,
    status VARCHAR(32) NOT NULL,
    duration_ms BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS evidence (
    id VARCHAR(64) PRIMARY KEY,
    investigation_id VARCHAR(64) NOT NULL,
    source VARCHAR(100) NOT NULL,
    label VARCHAR(300) NOT NULL,
    detail TEXT NOT NULL,
    data_json TEXT,
    created_at TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS internal_tasks (
    id VARCHAR(64) PRIMARY KEY,
    investigation_id VARCHAR(64) NOT NULL UNIQUE,
    account_id VARCHAR(64) NOT NULL,
    title VARCHAR(300) NOT NULL,
    owner_role VARCHAR(64) NOT NULL,
    priority VARCHAR(16) NOT NULL,
    steps_json TEXT NOT NULL,
    due_at TIMESTAMP NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS followups (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL,
    result VARCHAR(32) NOT NULL,
    detail TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS investigation_telemetry (
    investigation_id VARCHAR(64) PRIMARY KEY,
    trace_id VARCHAR(64),
    root_observation_id VARCHAR(64),
    tool_calls INT NOT NULL,
    input_tokens BIGINT NOT NULL,
    output_tokens BIGINT NOT NULL,
    duration_ms BIGINT NOT NULL,
    langfuse_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS evaluation_runs (
    id VARCHAR(64) PRIMARY KEY,
    dataset_version VARCHAR(100) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    judge_status VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    thresholds_json TEXT NOT NULL,
    summary_json TEXT,
    previous_run_id VARCHAR(64),
    passed BOOLEAN,
    engine_version VARCHAR(64) NOT NULL,
    langfuse_experiment_id VARCHAR(100) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS evaluation_items (
    id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    case_id VARCHAR(100) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    expected_root_cause VARCHAR(64) NOT NULL,
    actual_root_cause VARCHAR(64),
    expected_owner_role VARCHAR(64) NOT NULL,
    actual_owner_role VARCHAR(64),
    expected_tools_json TEXT NOT NULL,
    actual_tools_json TEXT NOT NULL,
    expected_evidence_json TEXT NOT NULL,
    actual_evidence_json TEXT NOT NULL,
    scores_json TEXT NOT NULL,
    passed BOOLEAN NOT NULL,
    critical_violation BOOLEAN NOT NULL,
    trace_id VARCHAR(64),
    root_observation_id VARCHAR(64),
    duration_ms BIGINT NOT NULL,
    input_tokens BIGINT NOT NULL,
    output_tokens BIGINT NOT NULL,
    judge_input_json TEXT,
    judge_scores_json TEXT,
    error TEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
