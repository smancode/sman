# Contributing to Sman

Thanks for your interest in contributing to Sman! This guide will help you get started.

## Quick Links

- [README](./README.md)
- [Security Policy](./SECURITY.md)
- [License](./LICENSE)

## How to Contribute

### Bug Reports

1. Search [existing issues](https://github.com/smancode/sman/issues) to avoid duplicates
2. Open a new issue with:
   - Clear title and description
   - Steps to reproduce
   - Expected vs actual behavior
   - Environment info (OS, Node.js version, Sman version)

### Feature Requests

1. Check [existing issues](https://github.com/smancode/sman/issues) first
2. Open an issue with the `feature` label
3. Describe the use case and expected behavior

### Pull Requests

1. Fork the repository
2. Create a feature branch from `master`
   ```bash
   git checkout -b feature/your-feature-name
   ```
3. Make your changes
4. Ensure tests pass
   ```bash
   pnpm test
   ```
5. Ensure the build succeeds
   ```bash
   pnpm build
   ```
6. Submit a PR with a clear description of the changes

### Before You PR

- [ ] Code compiles without errors (`pnpm build`)
- [ ] Tests pass (`pnpm test`)
- [ ] No hardcoded user-facing text — use `t('key')` for i18n
- [ ] Files stay under 500 lines
- [ ] Commit messages are clear and descriptive

## Development Setup

### Prerequisites

- **Node.js 22 LTS**
- **pnpm** package manager

### Getting Started

```bash
# Install dependencies
pnpm install

# Start development (frontend + backend + Electron)
./dev.sh

# Or start separately
pnpm dev           # Frontend (5881)
pnpm dev:server    # Backend (5880)
```

### Project Structure

```
sman/
├── electron/           # Electron main process
├── server/             # Express + WebSocket backend
│   ├── init/           # Session initialization pipeline
│   └── services/       # Business logic services
├── src/                # React frontend
│   ├── components/     # UI components
│   ├── locales/        # i18n translations (zh-CN, en-US)
│   ├── stores/         # Zustand state management
│   └── ...             # Other frontend modules
├── tests/              # Test files
└── docs/               # Documentation
```

### Tech Stack

| Layer | Technology |
|-------|-----------|
| Frontend | React 19, TypeScript, TailwindCSS, Zustand, CodeMirror 6 |
| Backend | Node.js, Express, WebSocket (ws), SQLite |
| Desktop | Electron |
| AI | Claude Agent SDK |

## Coding Guidelines

### Core Principles

- **Keep it simple**: Don't add unnecessary abstractions or features
- **Strict parameters**: No default values, no silent fallbacks — validate and throw
- **i18n first**: All user-facing text must use `t('key')`, never hardcode
- **Under 500 lines**: Split files that exceed the limit

### Key Rules

1. **No hardcoded text** in JSX — use `import { t } from '@/locales'`
2. **Strict parameter validation** — missing params throw, never silently default
3. **Test your changes** — write tests for new functionality
4. **One responsibility per file** — keep modules focused

## Code of Conduct

Be respectful, constructive, and inclusive. We're all here to build something great together.

## License

By contributing to Sman, you agree that your contributions will be licensed under the [MIT License](./LICENSE).
