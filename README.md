# SmanWeb - OpenClaw Web Interface

A modern web interface for OpenClaw built with React, TypeScript, and Tailwind CSS.

## Features

- Modern UI with Tailwind CSS and shadcn/ui components
- Real-time chat interface for OpenClaw
- Connection management and settings
- Multi-language support (i18n)
- Responsive design
- Docker-ready deployment

## Quick Start

### Prerequisites

- Node.js 20+
- pnpm (recommended) or npm

### Development

```bash
# Install dependencies
pnpm install

# Start development server
pnpm dev

# Build for production
pnpm build

# Preview production build
pnpm preview
```

### Docker

```bash
# Build image
docker build -t smanweb .

# Run container
docker run -p 3000:80 smanweb

# Or use docker-compose
docker-compose up -d
```

## Project Structure

```
smanweb/
├── src/
│   ├── components/     # React components
│   ├── hooks/          # Custom hooks
│   ├── lib/            # Utilities
│   ├── pages/          # Page components
│   ├── services/       # API services
│   ├── store/          # Zustand store
│   └── types/          # TypeScript types
├── public/             # Static assets
├── docs/               # Documentation
└── dist/               # Build output
```

## Configuration

Copy `.env.example` to `.env` and configure:

```bash
cp .env.example .env
```

| Variable | Description | Default |
|----------|-------------|---------|
| VITE_API_URL | OpenClaw Gateway URL | http://localhost:8000 |
| VITE_DEBUG | Enable debug mode | false |
| VITE_APP_TITLE | Application title | OpenClaw Web |

## Tech Stack

- React 19 + TypeScript
- Vite
- Tailwind CSS
- shadcn/ui
- Zustand (state management)
- React Router
- i18next

## License

MIT
