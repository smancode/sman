import { describe, it, expect } from 'vitest';
import { parseCrontabMd } from '../../server/cron-scheduler.js';

describe('parseCrontabMd', () => {
  it('should parse valid crontab.md with cron expression on first line', () => {
    const content = '0 9 * * 1-5\n检查今天所有订单的状态';
    const result = parseCrontabMd(content);
    expect(result).not.toBeNull();
    expect(result!.expression).toBe('0 9 * * 1-5');
    expect(result!.promptContent).toBe('检查今天所有订单的状态');
  });

  it('should return null if first line is not a cron expression', () => {
    const content = '这是一个普通的提示词\n没有 cron 表达式';
    const result = parseCrontabMd(content);
    expect(result).toBeNull();
  });

  it('should handle BOM in file content', () => {
    const content = '\uFEFF*/30 * * * *\n每30分钟执行一次';
    const result = parseCrontabMd(content);
    expect(result).not.toBeNull();
    expect(result!.expression).toBe('*/30 * * * *');
  });

  it('should handle empty file', () => {
    const result = parseCrontabMd('');
    expect(result).toBeNull();
  });

  it('should handle file with only cron expression', () => {
    const content = '0 0 * * *';
    const result = parseCrontabMd(content);
    expect(result).not.toBeNull();
    expect(result!.expression).toBe('0 0 * * *');
    expect(result!.promptContent).toBe('');
  });

  it('should handle multiline prompt content', () => {
    const content = '*/5 * * * *\n第一行提示\n第二行提示\n第三行提示';
    const result = parseCrontabMd(content);
    expect(result!.promptContent).toBe('第一行提示\n第二行提示\n第三行提示');
  });

  it('should reject invalid cron expressions', () => {
    const content = 'not-a-cron expression\nsome content';
    const result = parseCrontabMd(content);
    expect(result).toBeNull();
  });

  it('should handle whitespace around cron expression', () => {
    const content = '  0 9 * * *  \n执行任务';
    const result = parseCrontabMd(content);
    expect(result).not.toBeNull();
    expect(result!.expression).toBe('0 9 * * *');
  });

  it('should parse cron expression with inline command (system crontab style)', () => {
    const content = '*/60 * * * * fetch';
    const result = parseCrontabMd(content);
    expect(result).not.toBeNull();
    expect(result!.expression).toBe('*/60 * * * *');
    expect(result!.promptContent).toBe('fetch');
  });

  it('should parse cron expression with inline command and remaining content', () => {
    const content = '0 15 * * 1-5 /zijin\n拉取资金流向数据';
    const result = parseCrontabMd(content);
    expect(result).not.toBeNull();
    expect(result!.expression).toBe('0 15 * * 1-5');
    expect(result!.promptContent).toBe('/zijin\n拉取资金流向数据');
  });

  it('should skip comment lines before cron expression', () => {
    const content = '# Zijin Skill\n# 每天下午3点\n0 15 * * 1-5\n拉取数据';
    const result = parseCrontabMd(content);
    expect(result).not.toBeNull();
    expect(result!.expression).toBe('0 15 * * 1-5');
    expect(result!.promptContent).toBe('拉取数据');
  });

  it('should skip comment lines and parse system crontab style', () => {
    const content = '# comment\n0 15 * * 1-5 /zijin';
    const result = parseCrontabMd(content);
    expect(result).not.toBeNull();
    expect(result!.expression).toBe('0 15 * * 1-5');
    expect(result!.promptContent).toBe('/zijin');
  });

  it('should return null if only comments', () => {
    const content = '# just a comment\n# another comment';
    const result = parseCrontabMd(content);
    expect(result).toBeNull();
  });
});
