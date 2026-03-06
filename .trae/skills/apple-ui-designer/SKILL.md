---
name: "apple-ui-designer"
description: "Designs and refines clean Apple-style desktop UI. Invoke when user asks for minimalist visual refresh, spacing polish, or neutral theme updates."
---

# Apple UI Designer

## Purpose

Create a clean, minimalist, Apple-style desktop interface with restrained color usage, clear hierarchy, and refined interaction details.

## When to Invoke

- User asks for Apple style, minimalist style, or cleaner UI
- User asks to replace vivid color themes with neutral palettes
- User requests spacing, typography, border, or shadow polishing
- User requests visual consistency across sidebar, header, chat, and forms

## Core Principles

1. Use neutral backgrounds and subtle elevation, not saturated gradients
2. Keep accent color sparse and functional
3. Prefer thin borders, soft shadows, and large enough corner radius
4. Maintain clear spacing rhythm with consistent paddings and gaps
5. Keep controls legible, calm, and predictable

## Color Guidance

- Background layers: near-white with slight contrast steps
- Text: strong primary contrast and muted secondary contrast
- Accent: one restrained blue used for focus and action states only
- Feedback colors: subtle success/warning/error with accessible contrast

## Component Guidance

- Sidebar: light surface, gentle border, compact section hierarchy
- Header: single bottom border, minimal decoration
- Input area: low-contrast border, clear focus ring, balanced vertical centering
- Buttons: neutral defaults, accent only for primary action
- Cards and panels: consistent radius and 1px border

## Interaction Guidance

- Keep transitions short and subtle
- Ensure keyboard and focus-visible states are clear
- Respect IME composition behavior for Enter key handling
- Avoid visual noise in resize handles and auxiliary controls

## Delivery Checklist

- Palette unified across global tokens
- Hardcoded legacy accent colors removed
- Focus, hover, active states aligned with new style
- Text input behavior validated with Chinese IME and English candidate selection
