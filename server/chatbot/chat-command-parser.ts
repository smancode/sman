import type { ParseResult, CommandResult } from './types';

const VALID_COMMANDS = new Set<string>(['cd', 'pwd', 'workspaces', 'add', 'help', 'status']);

export function parseChatCommand(input: string): ParseResult {
  const trimmed = input.trim();
  if (!trimmed.startsWith('/')) {
    return { isCommand: false };
  }

  const spaceIndex = trimmed.indexOf(' ');
  const cmdStr = spaceIndex === -1
    ? trimmed.substring(1)
    : trimmed.substring(1, spaceIndex);

  const command = cmdStr.toLowerCase();

  if (!VALID_COMMANDS.has(command)) {
    return { isCommand: false };
  }

  const args = spaceIndex === -1 ? '' : trimmed.substring(spaceIndex + 1).trim();

  return {
    isCommand: true,
    command: { command: command as CommandResult['command'], args },
  };
}
