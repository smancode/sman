import { describe, it, expect } from 'vitest';
import { parseChatCommand } from '../../../server/chatbot/chat-command-parser.js';

describe('parseChatCommand', () => {
  it('should parse /cd command with absolute path', () => {
    const result = parseChatCommand('/cd /data/projectA');
    expect(result.isCommand).toBe(true);
    expect(result.command!.command).toBe('cd');
    expect(result.command!.args).toBe('/data/projectA');
  });

  it('should parse /cd command with project name', () => {
    const result = parseChatCommand('/cd hello-halo');
    expect(result.isCommand).toBe(true);
    expect(result.command!.command).toBe('cd');
    expect(result.command!.args).toBe('hello-halo');
  });

  it('should parse /pwd command', () => {
    const result = parseChatCommand('/pwd');
    expect(result.isCommand).toBe(true);
    expect(result.command!.command).toBe('pwd');
    expect(result.command!.args).toBe('');
  });

  it('should parse /workspaces command', () => {
    const result = parseChatCommand('/workspaces');
    expect(result.isCommand).toBe(true);
    expect(result.command!.command).toBe('workspaces');
  });

  it('should parse /add command with path', () => {
    const result = parseChatCommand('/add /Users/nasakim/projects/new');
    expect(result.isCommand).toBe(true);
    expect(result.command!.command).toBe('add');
    expect(result.command!.args).toBe('/Users/nasakim/projects/new');
  });

  it('should parse /help command', () => {
    const result = parseChatCommand('/help');
    expect(result.isCommand).toBe(true);
    expect(result.command!.command).toBe('help');
  });

  it('should parse /status command', () => {
    const result = parseChatCommand('/status');
    expect(result.isCommand).toBe(true);
    expect(result.command!.command).toBe('status');
  });

  it('should not treat plain text as command', () => {
    const result = parseChatCommand('hello how are you');
    expect(result.isCommand).toBe(false);
    expect(result.command).toBeUndefined();
  });

  it('should not treat /unknown as a valid command', () => {
    const result = parseChatCommand('/unknown something');
    expect(result.isCommand).toBe(false);
  });

  it('should trim whitespace', () => {
    const result = parseChatCommand('  /pwd  ');
    expect(result.isCommand).toBe(true);
    expect(result.command!.command).toBe('pwd');
  });

  it('should handle /cd with extra spaces', () => {
    const result = parseChatCommand('/cd   hello-halo  ');
    expect(result.isCommand).toBe(true);
    expect(result.command!.command).toBe('cd');
    expect(result.command!.args).toBe('hello-halo');
  });
});
