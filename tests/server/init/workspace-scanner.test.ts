import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'fs';
import path from 'path';
import os from 'os';
import { scanWorkspace } from '../../../server/init/workspace-scanner.js';

describe('WorkspaceScanner', () => {
  let tmpDir: string;

  beforeEach(() => {
    tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-scan-test-'));
  });

  afterEach(() => {
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  it('detects empty directory', () => {
    const result = scanWorkspace(tmpDir);
    expect(result.types).toContain('empty');
    expect(result.fileCount).toBe(0);
    expect(result.isGitRepo).toBe(false);
    expect(result.hasClaudeMd).toBe(false);
  });

  it('detects Node.js project from package.json', () => {
    fs.writeFileSync(path.join(tmpDir, 'package.json'), JSON.stringify({
      name: 'test-app',
      scripts: { dev: 'vite', build: 'tsc' },
      dependencies: { express: '^4.0.0' },
    }));
    fs.writeFileSync(path.join(tmpDir, 'server.ts'), '');
    fs.writeFileSync(path.join(tmpDir, 'index.ts'), '');

    const result = scanWorkspace(tmpDir);
    expect(result.types).toContain('node');
    expect(result.packageJson).toBeDefined();
    expect(result.packageJson!.name).toBe('test-app');
    expect(result.languages['.ts']).toBe(2);
  });

  it('detects React project from package.json deps', () => {
    fs.writeFileSync(path.join(tmpDir, 'package.json'), JSON.stringify({
      name: 'react-app',
      dependencies: { react: '^19.0.0', 'react-dom': '^19.0.0' },
    }));
    fs.writeFileSync(path.join(tmpDir, 'App.tsx'), '');

    const result = scanWorkspace(tmpDir);
    expect(result.types).toContain('react');
  });

  it('detects Java project from pom.xml', () => {
    fs.writeFileSync(path.join(tmpDir, 'pom.xml'), `
      <project>
        <groupId>com.example</groupId>
        <artifactId>my-service</artifactId>
        <dependencies>
          <dependency><artifactId>spring-boot-starter</artifactId></dependency>
        </dependencies>
      </project>
    `);
    fs.mkdirSync(path.join(tmpDir, 'src', 'main', 'java'), { recursive: true });
    fs.writeFileSync(path.join(tmpDir, 'src', 'main', 'java', 'App.java'), '');

    const result = scanWorkspace(tmpDir);
    expect(result.types).toContain('java');
    expect(result.pomXml).toBeDefined();
    expect(result.pomXml!.artifactId).toBe('my-service');
  });

  it('detects git repo', () => {
    fs.mkdirSync(path.join(tmpDir, '.git'));
    const result = scanWorkspace(tmpDir);
    expect(result.isGitRepo).toBe(true);
  });

  it('detects CLAUDE.md', () => {
    fs.writeFileSync(path.join(tmpDir, 'CLAUDE.md'), '# My Project');
    const result = scanWorkspace(tmpDir);
    expect(result.hasClaudeMd).toBe(true);
  });

  it('detects Go project from go.mod', () => {
    fs.writeFileSync(path.join(tmpDir, 'go.mod'), 'module github.com/example/app\ngo 1.22');
    fs.writeFileSync(path.join(tmpDir, 'main.go'), '');
    const result = scanWorkspace(tmpDir);
    expect(result.types).toContain('go');
  });

  it('detects Python project from requirements.txt', () => {
    fs.writeFileSync(path.join(tmpDir, 'requirements.txt'), 'flask==3.0\nrequests');
    fs.writeFileSync(path.join(tmpDir, 'app.py'), '');
    const result = scanWorkspace(tmpDir);
    expect(result.types).toContain('python');
  });

  it('detects Rust project from Cargo.toml', () => {
    fs.writeFileSync(path.join(tmpDir, 'Cargo.toml'), '[package]\nname = "test"');
    fs.writeFileSync(path.join(tmpDir, 'main.rs'), '');
    const result = scanWorkspace(tmpDir);
    expect(result.types).toContain('rust');
  });

  it('detects docs-only directory', () => {
    fs.writeFileSync(path.join(tmpDir, 'README.md'), '# Docs');
    fs.writeFileSync(path.join(tmpDir, 'guide.md'), '');
    fs.writeFileSync(path.join(tmpDir, 'api.md'), '');
    const result = scanWorkspace(tmpDir);
    expect(result.types).toContain('docs');
  });

  it('excludes noise directories from topDirs', () => {
    for (const dir of ['node_modules', '.git', 'dist', 'build', 'target', '.next']) {
      fs.mkdirSync(path.join(tmpDir, dir), { recursive: true });
    }
    fs.mkdirSync(path.join(tmpDir, 'src'), { recursive: true });
    fs.writeFileSync(path.join(tmpDir, 'src', 'index.ts'), '');

    const result = scanWorkspace(tmpDir);
    expect(result.topDirs).toContain('src');
    expect(result.topDirs).not.toContain('node_modules');
    expect(result.topDirs).not.toContain('.git');
  });
});
