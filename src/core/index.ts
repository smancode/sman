// src/core/index.ts
/**
 * SMAN Core Module
 *
 * This module provides the context management and learning functionality
 * that makes SMAN a "context manager" rather than just an AI chat client.
 *
 * Key responsibilities:
 * - Load project context from .sman/ directory
 * - Load user habits from ~/.smanlocal/
 * - Build system prompts with progressive skill disclosure
 * - Analyze conversations for learning opportunities
 * - Persist learned content to appropriate files
 *
 * Architecture:
 * - OpenClaw Sidecar: Executes AI tasks (the "executor")
 * - SMAN Core: Manages context and learning (the "context manager")
 */

export * from "./openclaw";
export * from "./context";
export * from "./skills";
export * from "./learning";
