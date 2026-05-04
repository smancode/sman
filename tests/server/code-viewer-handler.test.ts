import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {
  validatePath,
  isBinaryFile,
  hasNullBytes,
  shouldHide,
  detectLanguage,
  MAX_FILE_SIZE,
} from '../../server/code-viewer-handler.js';

describe('code-viewer-handler', () => {
  describe('MAX_FILE_SIZE', () => {
    it('should be 1MB', () => {
      expect(MAX_FILE_SIZE).toBe(1_048_576);
    });
  });

  describe('validatePath', () => {
    let tmpDir: string;

    beforeEach(() => {
      tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-test-'));
    });

    afterEach(() => {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    });

    it('should resolve a valid path inside workspace', () => {
      const result = validatePath(tmpDir, 'src/index.ts');
      expect(result).toBe(path.resolve(tmpDir, 'src/index.ts'));
    });

    it('should reject path traversal with ..', () => {
      expect(() => validatePath(tmpDir, '../../../etc/passwd')).toThrow('Path is outside workspace');
    });

    it('should reject absolute path outside workspace', () => {
      expect(() => validatePath(tmpDir, '/etc/passwd')).toThrow('Path is outside workspace');
    });

    it('should reject symlink escaping workspace', () => {
      const symlinkDir = path.join(tmpDir, 'escape');
      const outsideDir = path.join(os.tmpdir(), 'sman-outside-' + Date.now());
      fs.mkdirSync(outsideDir, { recursive: true });
      // Create actual file in outside dir so realpathSync can resolve it
      fs.writeFileSync(path.join(outsideDir, 'secret.txt'), 'top secret');
      try {
        fs.symlinkSync(outsideDir, symlinkDir);
        expect(() => validatePath(tmpDir, 'escape/secret.txt')).toThrow('Symlink escapes workspace');
      } finally {
        fs.rmSync(outsideDir, { recursive: true, force: true });
      }
    });
  });

  describe('isBinaryFile', () => {
    it('should detect binary files by extension', () => {
      expect(isBinaryFile('image.png')).toBe(true);
      expect(isBinaryFile('archive.zip')).toBe(true);
      expect(isBinaryFile('data.sqlite')).toBe(true);
      expect(isBinaryFile('font.woff2')).toBe(true);
      expect(isBinaryFile('video.mp4')).toBe(true);
      expect(isBinaryFile('doc.pdf')).toBe(true);
    });

    it('should return false for text files', () => {
      expect(isBinaryFile('index.ts')).toBe(false);
      expect(isBinaryFile('README.md')).toBe(false);
      expect(isBinaryFile('style.css')).toBe(false);
      expect(isBinaryFile('app.tsx')).toBe(false);
    });
  });

  describe('hasNullBytes', () => {
    it('should detect null bytes in buffer', () => {
      const buf = Buffer.from([0x41, 0x00, 0x42]);
      expect(hasNullBytes(buf)).toBe(true);
    });

    it('should return false for buffer without null bytes', () => {
      const buf = Buffer.from('Hello, world!');
      expect(hasNullBytes(buf)).toBe(false);
    });

    it('should only check first 8192 bytes', () => {
      const buf = Buffer.alloc(10000, 0x41);
      buf[9000] = 0x00;
      expect(hasNullBytes(buf)).toBe(false);
    });
  });

  describe('shouldHide', () => {
    it('should hide known hidden directories', () => {
      expect(shouldHide('.git')).toBe(true);
      expect(shouldHide('node_modules')).toBe(true);
      expect(shouldHide('dist')).toBe(true);
      expect(shouldHide('build')).toBe(true);
      expect(shouldHide('.sman')).toBe(true);
    });

    it('should hide dot-prefixed names', () => {
      expect(shouldHide('.env')).toBe(true);
      expect(shouldHide('.vscode')).toBe(true);
    });

    it('should not hide normal names', () => {
      expect(shouldHide('src')).toBe(false);
      expect(shouldHide('package.json')).toBe(false);
      expect(shouldHide('README.md')).toBe(false);
    });
  });

  describe('detectLanguage', () => {
    it('should detect TypeScript', () => {
      expect(detectLanguage('app.ts')).toBe('typescript');
      expect(detectLanguage('App.tsx')).toBe('typescript');
    });

    it('should detect Python', () => {
      expect(detectLanguage('main.py')).toBe('python');
    });

    it('should detect JavaScript', () => {
      expect(detectLanguage('index.js')).toBe('javascript');
      expect(detectLanguage('Component.jsx')).toBe('javascript');
    });

    it('should detect other languages', () => {
      expect(detectLanguage('main.go')).toBe('go');
      expect(detectLanguage('lib.rs')).toBe('rust');
      expect(detectLanguage('App.java')).toBe('java');
      expect(detectLanguage('style.css')).toBe('css');
      expect(detectLanguage('data.json')).toBe('json');
      expect(detectLanguage('config.yaml')).toBe('yaml');
      expect(detectLanguage('README.md')).toBe('markdown');
    });

    it('should return text for unknown extensions', () => {
      expect(detectLanguage('Makefile')).toBe('text');
      expect(detectLanguage('data.xyz')).toBe('text');
    });
  });
});
