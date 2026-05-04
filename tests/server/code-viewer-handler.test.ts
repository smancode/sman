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
  handleListDir,
  handleReadFile,
  handleSearchSymbols,
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

  describe('handleListDir', () => {
    let tmpDir: string;

    beforeEach(() => {
      tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-listdir-'));
    });

    afterEach(() => {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    });

    it('should list entries excluding hidden dirs', () => {
      fs.mkdirSync(path.join(tmpDir, 'src'));
      fs.mkdirSync(path.join(tmpDir, '.git'));
      fs.mkdirSync(path.join(tmpDir, 'node_modules'));
      fs.writeFileSync(path.join(tmpDir, 'README.md'), 'hello');
      fs.writeFileSync(path.join(tmpDir, 'package.json'), '{}');

      const result = handleListDir(tmpDir, '');

      expect(result.path).toBe(tmpDir);
      const names = result.entries.map(e => e.name);
      expect(names).toContain('src');
      expect(names).toContain('README.md');
      expect(names).toContain('package.json');
      expect(names).not.toContain('.git');
      expect(names).not.toContain('node_modules');
    });

    it('should list nested directory', () => {
      fs.mkdirSync(path.join(tmpDir, 'src', 'components'), { recursive: true });
      fs.writeFileSync(path.join(tmpDir, 'src', 'index.ts'), 'export {}');
      fs.writeFileSync(path.join(tmpDir, 'src', 'components', 'App.tsx'), '<App />');

      const result = handleListDir(tmpDir, 'src');

      expect(result.path).toBe(path.resolve(tmpDir, 'src'));
      const names = result.entries.map(e => e.name);
      expect(names).toContain('components');
      expect(names).toContain('index.ts');
    });

    it('should sort directories first then files, both alphabetical', () => {
      fs.mkdirSync(path.join(tmpDir, 'beta'));
      fs.mkdirSync(path.join(tmpDir, 'alpha'));
      fs.writeFileSync(path.join(tmpDir, 'z.txt'), 'z');
      fs.writeFileSync(path.join(tmpDir, 'a.txt'), 'a');

      const result = handleListDir(tmpDir, '');

      const names = result.entries.map(e => e.name);
      expect(names).toEqual(['alpha', 'beta', 'a.txt', 'z.txt']);
    });

    it('should include size for files', () => {
      fs.writeFileSync(path.join(tmpDir, 'hello.txt'), 'hello');

      const result = handleListDir(tmpDir, '');
      const file = result.entries.find(e => e.name === 'hello.txt');
      expect(file).toBeDefined();
      expect(file!.size).toBe(5);
    });

    it('should throw NOT_FOUND for missing directory', () => {
      expect(() => handleListDir(tmpDir, 'nonexistent')).toThrow('Directory not found');
    });

    it('should throw NOT_FOUND for a file path', () => {
      fs.writeFileSync(path.join(tmpDir, 'file.txt'), 'content');

      expect(() => handleListDir(tmpDir, 'file.txt')).toThrow('Directory not found');
    });
  });

  describe('handleReadFile', () => {
    let tmpDir: string;

    beforeEach(() => {
      tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-readfile-'));
    });

    afterEach(() => {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    });

    it('should read text file content', () => {
      fs.writeFileSync(path.join(tmpDir, 'hello.ts'), 'const x = 1;\nconsole.log(x);\n');

      const result = handleReadFile(tmpDir, 'hello.ts');

      if (result.type === 'binary') {
        throw new Error('Expected text result, got binary');
      }
      expect(result.content).toBe('const x = 1;\nconsole.log(x);\n');
      expect(result.language).toBe('typescript');
      expect(result.totalLines).toBe(3);
      expect(result.truncated).toBe(false);
      expect(result.totalSize).toBe(Buffer.byteLength('const x = 1;\nconsole.log(x);\n'));
    });

    it('should throw NOT_FOUND for missing file', () => {
      expect(() => handleReadFile(tmpDir, 'missing.ts')).toThrow('File not found');
    });

    it('should return binary info for .png file', () => {
      const pngBuffer = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00]);
      fs.writeFileSync(path.join(tmpDir, 'image.png'), pngBuffer);

      const result = handleReadFile(tmpDir, 'image.png');

      expect(result.type).toBe('binary');
      if (result.type === 'binary') {
        expect(result.mimeType).toBe('image/png');
        expect(result.size).toBe(pngBuffer.length);
        expect(result.fileName).toBe('image.png');
      }
    });

    it('should truncate large files', () => {
      const largeContent = 'x'.repeat(MAX_FILE_SIZE + 100);
      fs.writeFileSync(path.join(tmpDir, 'large.txt'), largeContent);

      const result = handleReadFile(tmpDir, 'large.txt');

      if (result.type === 'binary') {
        throw new Error('Expected text result, got binary');
      }
      expect(result.truncated).toBe(true);
      expect(result.content.length).toBeLessThanOrEqual(MAX_FILE_SIZE);
      expect(result.totalSize).toBe(largeContent.length);
    });
  });

  describe('handleSearchSymbols', () => {
    let tmpDir: string;

    beforeEach(() => {
      tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sman-search-'));
      fs.mkdirSync(path.join(tmpDir, 'src'), { recursive: true });
      fs.writeFileSync(
        path.join(tmpDir, 'src', 'app.ts'),
        'function myFunction() {\n  const myVar = 42;\n  return myVar;\n}\n'
      );
      fs.writeFileSync(
        path.join(tmpDir, 'src', 'utils.ts'),
        'export const myVar = "hello";\nexport function myFunction() {}\n'
      );
      // Hidden dir that should be skipped
      fs.mkdirSync(path.join(tmpDir, 'node_modules'), { recursive: true });
      fs.writeFileSync(
        path.join(tmpDir, 'node_modules', 'lib.ts'),
        'const myVar = "hidden";\n'
      );
    });

    afterEach(() => {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    });

    it('should find matches across files', () => {
      const result = handleSearchSymbols(tmpDir, 'myVar');

      expect(result.symbol).toBe('myVar');
      expect(result.matches.length).toBeGreaterThanOrEqual(2);
      const paths = result.matches.map(m => m.filePath);
      expect(paths.some(p => p.includes('app.ts'))).toBe(true);
      expect(paths.some(p => p.includes('utils.ts'))).toBe(true);
    });

    it('should skip hidden directories', () => {
      const result = handleSearchSymbols(tmpDir, 'myVar');

      const paths = result.matches.map(m => m.filePath);
      expect(paths.some(p => p.includes('node_modules'))).toBe(false);
    });

    it('should respect maxResults limit', () => {
      // Create multiple files with the symbol
      for (let i = 0; i < 30; i++) {
        fs.writeFileSync(path.join(tmpDir, `file${i}.ts`), `const myVar = ${i};\n`);
      }

      const result = handleSearchSymbols(tmpDir, 'myVar', undefined, 5);

      expect(result.matches.length).toBeLessThanOrEqual(5);
    });

    it('should sanitize symbol to alphanumeric and underscore', () => {
      const result = handleSearchSymbols(tmpDir, 'myVar(); DROP TABLE');

      // Should still search for just "myVar" after sanitization
      expect(result.symbol).toBe('myVar_DROP_TABLE');
      expect(result.matches.length).toBe(0);
    });

    it('should filter by file extension', () => {
      fs.writeFileSync(path.join(tmpDir, 'src', 'data.py'), 'myVar = 42\n');

      const resultTs = handleSearchSymbols(tmpDir, 'myVar', '.ts');
      const resultPy = handleSearchSymbols(tmpDir, 'myVar', '.py');

      expect(resultTs.matches.every(m => m.filePath.endsWith('.ts'))).toBe(true);
      expect(resultPy.matches.every(m => m.filePath.endsWith('.py'))).toBe(true);
    });

    it('should include line number and context in matches', () => {
      const result = handleSearchSymbols(tmpDir, 'myVar');

      const appMatch = result.matches.find(m => m.filePath.includes('app.ts'));
      expect(appMatch).toBeDefined();
      expect(typeof appMatch!.line).toBe('number');
      expect(appMatch!.lineContent).toContain('myVar');
    });

    it('should use word boundary matching', () => {
      fs.writeFileSync(path.join(tmpDir, 'src', 'edge.ts'), 'const myVariable = 1;\nconst myVar = 2;\n');

      const result = handleSearchSymbols(tmpDir, 'myVar');

      const edgeMatches = result.matches.filter(m => m.filePath.includes('edge.ts'));
      // Should only match myVar, not myVariable
      expect(edgeMatches.length).toBe(1);
      expect(edgeMatches[0].lineContent).toContain('myVar = 2');
    });
  });
});
