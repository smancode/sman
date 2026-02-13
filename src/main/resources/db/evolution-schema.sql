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
