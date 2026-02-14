-- ============================================================
-- Sman Evolution Schema
-- 自进化系统的 H2 数据库表结构
-- ============================================================

-- 学习记录表
-- 存储后台自问自答产生的知识
CREATE TABLE learning_records (
    id VARCHAR(64) PRIMARY KEY,
    project_key VARCHAR(128) NOT NULL,
    created_at BIGINT NOT NULL,

    -- 问题与答案
    question TEXT NOT NULL,
    question_type VARCHAR(32) NOT NULL,
    answer TEXT NOT NULL,

    -- 探索路径 (JSON)
    exploration_path TEXT,

    -- 元数据
    confidence DOUBLE NOT NULL,
    source_files TEXT,           -- JSON 数组
    tags TEXT,                   -- JSON 数组
    domain VARCHAR(64)
);

-- 学习记录索引
CREATE INDEX idx_lr_project_key ON learning_records(project_key);
CREATE INDEX idx_lr_domain ON learning_records(domain);
CREATE INDEX idx_lr_created_at ON learning_records(created_at);


-- 失败记录表
-- 用于死循环防护和学习
CREATE TABLE failure_records (
    id VARCHAR(64) PRIMARY KEY,
    project_key VARCHAR(128) NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    operation TEXT NOT NULL,
    error TEXT NOT NULL,
    context TEXT,                       -- JSON 对象
    timestamp BIGINT NOT NULL,
    retry_count INT DEFAULT 0,
    status VARCHAR(16) DEFAULT 'PENDING',
    avoid_strategy TEXT
);

-- 失败记录索引
CREATE INDEX idx_fr_project_key ON failure_records(project_key);
CREATE INDEX idx_fr_status ON failure_records(status);
CREATE INDEX idx_fr_timestamp ON failure_records(timestamp);


-- ============================================================
-- 断点续传状态表
-- 存储进化循环运行状态，用于 IDEA 重启后恢复
-- ============================================================

-- 进化循环运行状态表（含 ING 状态）
CREATE TABLE IF NOT EXISTS evolution_loop_state (
    project_key VARCHAR(255) PRIMARY KEY,
    enabled BOOLEAN DEFAULT TRUE,
    current_phase VARCHAR(50) DEFAULT 'IDLE',

    -- 统计信息
    total_iterations BIGINT DEFAULT 0,
    successful_iterations BIGINT DEFAULT 0,
    consecutive_duplicate_count INT DEFAULT 0,

    -- ING 状态（当前正在进行的操作）
    current_question TEXT,
    current_question_hash VARCHAR(64),
    exploration_progress INT DEFAULT 0,
    partial_steps JSON,  -- 已完成的工具调用步骤
    started_at BIGINT,   -- 当前阶段开始时间

    -- 智能控制
    last_generated_question_hash VARCHAR(64),
    last_project_md5 VARCHAR(64),
    stop_reason VARCHAR(50),

    -- 时间戳
    last_updated_at BIGINT NOT NULL
);

-- 退避状态表
CREATE TABLE IF NOT EXISTS backoff_state (
    project_key VARCHAR(255) PRIMARY KEY,
    consecutive_errors INT DEFAULT 0,
    last_error_time BIGINT,
    backoff_until BIGINT,
    last_updated_at BIGINT NOT NULL
);

-- 每日配额表
CREATE TABLE IF NOT EXISTS daily_quota (
    project_key VARCHAR(255) PRIMARY KEY,
    questions_today INT DEFAULT 0,
    explorations_today INT DEFAULT 0,
    last_reset_date VARCHAR(10),
    last_updated_at BIGINT NOT NULL
);
