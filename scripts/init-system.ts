/**
 * Business System initialization template.
 *
 * Creates the directory structure and template files for a new business system.
 * Run: npx tsx scripts/init-system.ts <systemId> <name> <workspace>
 */

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

function main(): void {
  const args = process.argv.slice(2);
  if (args.length < 3) {
    console.log('Usage: npx tsx scripts/init-system.ts <systemId> <name> <workspace>');
    process.exit(1);
  }

  const [systemId, name, workspace] = args;

  if (!fs.existsSync(workspace)) {
    console.log(`Workspace does not exist: ${workspace}`);
    console.log('Creating directory...');
    fs.mkdirSync(workspace, { recursive: true });
  }

  // Create directory structure
  const dirs = ['docs/tasks', 'docs/donetasks', 'docs/plans', 'src', 'tests'];
  for (const dir of dirs) {
    fs.mkdirSync(path.join(workspace, dir), { recursive: true });
  }

  // Create .gitkeep files
  fs.writeFileSync(path.join(workspace, 'docs/tasks/.gitkeep'), '', 'utf-8');
  fs.writeFileSync(path.join(workspace, 'docs/donetasks/.gitkeep'), '', 'utf-8');

  // Create CLAUDE.md
  const claudeMd = `# ${name}

${name} - Business System

## Project Structure
- \`src/\` - Source code
- \`tests/\` - Test files
- \`docs/plans/\` - Implementation plans
- \`docs/tasks/\` - Task queue
- \`docs/donetasks/\` - Completed tasks

## Guidelines
- Always respond in Simplified Chinese unless explicitly asked otherwise
- Focus on the business domain of this system
`;

  const claudeMdPath = path.join(workspace, 'CLAUDE.md');
  if (!fs.existsSync(claudeMdPath)) {
    fs.writeFileSync(claudeMdPath, claudeMd, 'utf-8');
    console.log(`Created ${claudeMdPath}`);
  }

  console.log(`Business system "${name}" initialized at ${workspace}`);
  console.log(`  - systemId: ${systemId}`);
  console.log(`  - Directories: ${dirs.join(', ')}`);
  console.log(`  - CLAUDE.md template created`);
}

main();
