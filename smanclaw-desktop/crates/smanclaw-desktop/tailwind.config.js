/** @type {import('tailwindcss').Config} */
export default {
  content: ['./src/**/*.{html,js,svelte,ts}'],
  theme: {
    extend: {
      colors: {
        background: '#0f0f12',
        surface: '#1a1a1f',
        border: '#2a2a32',
        'text-primary': '#f5f5f7',
        'text-secondary': '#a1a1aa',
        accent: '#6366f1',
        'accent-hover': '#4f46e5',
        'accent-light': '#818cf8',
        success: '#22c55e',
        warning: '#f59e0b',
        error: '#ef4444'
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'sans-serif'],
        mono: ['JetBrains Mono', 'Menlo', 'Monaco', 'monospace']
      }
    }
  }
};
