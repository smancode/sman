import { describe, it, expect } from 'vitest';
import { renderTemplate, detectInterpreter } from '../../server/batch-utils.js';

describe('renderTemplate', () => {
  it('should replace single placeholder', () => {
    const result = renderTemplate('/stock-ai-analyze ${name}', { name: '贵州茅台' });
    expect(result).toBe('/stock-ai-analyze 贵州茅台');
  });

  it('should replace multiple placeholders', () => {
    const result = renderTemplate('/test ${name} --code ${code}', {
      name: '贵州茅台',
      code: '600519',
    });
    expect(result).toBe('/test 贵州茅台 --code 600519');
  });

  it('should keep unmatched placeholders as-is', () => {
    const result = renderTemplate('/test ${name} ${unknown}', { name: 'hello' });
    expect(result).toBe('/test hello ${unknown}');
  });

  it('should handle numeric values', () => {
    const result = renderTemplate('/test ${days}', { days: 30 });
    expect(result).toBe('/test 30');
  });

  it('should handle empty item', () => {
    const result = renderTemplate('/test ${name}', {});
    expect(result).toBe('/test ${name}');
  });
});

describe('detectInterpreter', () => {
  it('should detect python code', () => {
    expect(detectInterpreter('import mysql.connector\nprint("hello")')).toBe('python3');
    expect(detectInterpreter('#!/usr/bin/env python\n')).toBe('python3');
    expect(detectInterpreter('  import pymysql\n')).toBe('python3');
  });

  it('should detect node.js code', () => {
    expect(detectInterpreter('const mysql = require("mysql2")\nconsole.log("hello")')).toBe('node');
  });

  it('should detect bash scripts', () => {
    expect(detectInterpreter('#!/bin/bash\necho hello')).toBe('bash');
    expect(detectInterpreter('#!/usr/bin/env bash\nls')).toBe('bash');
    expect(detectInterpreter('#!/bin/sh\necho hello')).toBe('bash');
  });

  it('should default to node for unknown', () => {
    expect(detectInterpreter('echo "hello"')).toBe('node');
  });

  it('should detect python by library imports', () => {
    expect(detectInterpreter('from pymysql import connect\n')).toBe('python3');
    expect(detectInterpreter('import psycopg2\n')).toBe('python3');
  });
});
