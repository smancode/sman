import type { ParseResult, CommandResult } from './types.js';

/** System commands and their aliases */
const COMMAND_ALIASES: Record<string, CommandResult['command']> = {
  cd: 'cd',
  pwd: 'pwd',
  workspaces: 'workspaces',
  wss: 'workspaces',
  status: 'status',
  sts: 'status',
  help: 'help',
  add: 'add',
  new: 'new',
};

export function parseChatCommand(input: string): ParseResult {
  const trimmed = input.trim();
  if (!trimmed.startsWith('//')) {
    return { isCommand: false };
  }

  const spaceIndex = trimmed.indexOf(' ');
  const cmdStr = spaceIndex === -1
    ? trimmed.substring(2)
    : trimmed.substring(2, spaceIndex);

  const command = cmdStr.toLowerCase();

  // Check if it's a known command (directly or via alias)
  const resolvedCommand = COMMAND_ALIASES[command];
  if (!resolvedCommand) {
    return { isCommand: true, command: undefined };
  }

  const args = spaceIndex === -1 ? '' : trimmed.substring(spaceIndex + 1).trim();

  return {
    isCommand: true,
    command: { command: resolvedCommand, args, rawCommand: command },
  };
}
