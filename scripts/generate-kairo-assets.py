#!/usr/bin/env python3
"""
Generate Kairo-style pixel art assets using Pillow.
Outputs PNG sprites for Agent, Buildings, and Tiles.
"""
from PIL import Image, ImageDraw
import os
import sys

OUT_DIR = os.path.join(os.path.dirname(__file__), '..', 'src', 'features', 'bazaar', 'world', 'assets', 'generated')
OUT_DIR = os.path.abspath(OUT_DIR)

# ── Color Palette (Kairo-style warm) ──
HAIR_COLORS = ['#5D4037', '#212121', '#FFB74D', '#F48FB1', '#7B68EE', '#4FC3F7', '#E52521']
SKIN_TONES = ['#FFE0BD', '#FFDAB9', '#D2A679', '#8D6E63']
OUTFIT_COLORS = ['#4CAF50', '#2196F3', '#E52521', '#FF9800', '#9C27B0', '#FFEB3B', '#00BCD4']

EYE = '#212121'
BLUSH = '#FFB6C1'
OUTLINE = '#4E342E'
SHOES = '#4E342E'
WHITE = '#FFFFFF'

BUILDING = {
    'wood': '#A1887F', 'woodDark': '#6D4C41',
    'roof': '#FF7043', 'roofDark': '#E64A19',
    'stone': '#BDBDBD', 'stoneDark': '#757575',
    'gold': '#FFD700', 'banner': '#FF5252',
    'blue': '#4FC3F7', 'blueDark': '#0288D1',
    'cloth': '#FFF8E1',
}

TILE = {
    'grass': '#8BC34A', 'grass2': '#9CCC65', 'grass3': '#AED581',
    'path': '#D7CCC8', 'path2': '#BCAAA4',
    'stone': '#BDBDBD', 'water': '#4FC3F7', 'dark': '#5D4037',
}

def hex_to_rgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

def draw_pixel(draw, x, y, color, scale=2):
    """Draw a scaled pixel."""
    rgb = hex_to_rgb(color) if isinstance(color, str) else color
    draw.rectangle([x*scale, y*scale, (x+1)*scale-1, (y+1)*scale-1], fill=rgb)

def draw_pixels(draw, pixels, scale=2):
    """Draw multiple pixels from list of (x, y, color)."""
    for x, y, c in pixels:
        draw_pixel(draw, x, y, c, scale)

# ── Agent Sprite (32x32, 4 frames × 4 directions) ──
def generate_agent_sprite(hair_idx, skin_idx, outfit_idx, facing='down', frame=0):
    """Generate a 32x32 Q-version Kairo-style agent sprite."""
    img = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    S = 2  # pixel scale (each logical pixel = 2x2 actual)

    hair = HAIR_COLORS[hair_idx % len(HAIR_COLORS)]
    skin = SKIN_TONES[skin_idx % len(SKIN_TONES)]
    outfit = OUTFIT_COLORS[outfit_idx % len(OUTFIT_COLORS)]

    bounce = -1 if frame in (1, 3) else 0

    # ── Big round head (rows 1-8, cols 4-11) ──
    # Hair top
    head_pixels = [
        # Row 1 (top of head - hair)
        (5, 1+bounce, hair), (6, 1+bounce, hair), (7, 1+bounce, hair), (8, 1+bounce, hair),
        # Row 2 (hair wider)
        (4, 2+bounce, hair), (5, 2+bounce, hair), (6, 2+bounce, hair), (7, 2+bounce, hair), (8, 2+bounce, hair), (9, 2+bounce, hair),
        # Row 3 (face outline + skin)
        (4, 3+bounce, OUTLINE), (5, 3+bounce, skin), (6, 3+bounce, skin), (7, 3+bounce, skin), (8, 3+bounce, skin), (9, 3+bounce, skin), (10, 3+bounce, OUTLINE),
        # Row 4 (face)
        (4, 4+bounce, skin), (5, 4+bounce, skin), (6, 4+bounce, skin), (7, 4+bounce, skin), (8, 4+bounce, skin), (9, 4+bounce, skin), (10, 4+bounce, skin),
        # Row 5 (eyes!)
        (4, 5+bounce, skin), (5, 5+bounce, skin), (6, 5+bounce, EYE), (7, 5+bounce, skin), (8, 5+bounce, skin), (9, 5+bounce, EYE), (10, 5+bounce, skin),
        # Row 6 (under eyes)
        (4, 6+bounce, skin), (5, 6+bounce, skin), (6, 6+bounce, skin), (7, 6+bounce, skin), (8, 6+bounce, skin), (9, 6+bounce, skin), (10, 6+bounce, skin),
        # Row 7 (blush)
        (5, 7+bounce, skin), (6, 7+bounce, BLUSH), (7, 7+bounce, skin), (8, 7+bounce, skin), (9, 7+bounce, BLUSH), (10, 7+bounce, skin),
        # Row 8 (chin)
        (5, 8+bounce, skin), (6, 8+bounce, skin), (7, 8+bounce, skin), (8, 8+bounce, skin), (9, 8+bounce, skin),
    ]

    # Adjust eyes for facing direction
    if facing == 'left':
        # Remove right eye, shift left
        head_pixels = [(x, y, c) for x, y, c in head_pixels if not (y == 5+bounce and x == 9 and c == EYE)]
        head_pixels.append((5, 5+bounce, skin))
        head_pixels.append((8, 5+bounce, EYE))
    elif facing == 'right':
        head_pixels = [(x, y, c) for x, y, c in head_pixels if not (y == 5+bounce and x == 6 and c == EYE)]
        head_pixels.append((7, 5+bounce, EYE))
        head_pixels.append((10, 5+bounce, skin))
    elif facing == 'up':
        # Back of head - show hair instead of face
        head_pixels = [
            (5, 1+bounce, hair), (6, 1+bounce, hair), (7, 1+bounce, hair), (8, 1+bounce, hair),
            (4, 2+bounce, hair), (5, 2+bounce, hair), (6, 2+bounce, hair), (7, 2+bounce, hair), (8, 2+bounce, hair), (9, 2+bounce, hair),
            (4, 3+bounce, OUTLINE), (5, 3+bounce, hair), (6, 3+bounce, hair), (7, 3+bounce, hair), (8, 3+bounce, hair), (9, 3+bounce, hair), (10, 3+bounce, OUTLINE),
            (4, 4+bounce, hair), (5, 4+bounce, hair), (6, 4+bounce, hair), (7, 4+bounce, hair), (8, 4+bounce, hair), (9, 4+bounce, hair), (10, 4+bounce, hair),
            (4, 5+bounce, hair), (5, 5+bounce, skin), (6, 5+bounce, hair), (7, 5+bounce, skin), (8, 5+bounce, skin), (9, 5+bounce, hair), (10, 5+bounce, skin),
            (4, 6+bounce, skin), (5, 6+bounce, skin), (6, 6+bounce, skin), (7, 6+bounce, skin), (8, 6+bounce, skin), (9, 6+bounce, skin), (10, 6+bounce, skin),
            (5, 7+bounce, skin), (6, 7+bounce, skin), (7, 7+bounce, skin), (8, 7+bounce, skin), (9, 7+bounce, skin), (10, 7+bounce, skin),
            (5, 8+bounce, skin), (6, 8+bounce, skin), (7, 8+bounce, skin), (8, 8+bounce, skin), (9, 8+bounce, skin),
        ]

    draw_pixels(draw, head_pixels, S)

    # Hair style overlay
    y0 = 1 + bounce
    hair_pixels = []
    hair_style = hair_idx % 8
    if hair_style == 0:  # spiky
        hair_pixels = [(5, y0-1, hair), (8, y0-1, hair), (3, y0, hair), (10, y0, hair)]
    elif hair_style == 1:  # long
        hair_pixels = [(3, y0+1, hair), (3, y0+2, hair), (3, y0+3, hair), (11, y0+1, hair), (11, y0+2, hair), (11, y0+3, hair)]
    elif hair_style == 2:  # curly
        hair_pixels = [(3, y0, hair), (11, y0, hair), (5, y0-1, hair), (9, y0-1, hair)]
    elif hair_style == 3:  # twin tails
        hair_pixels = [(2, y0+1, hair), (2, y0+2, hair), (2, y0+3, hair), (2, y0+4, hair),
                       (12, y0+1, hair), (12, y0+2, hair), (12, y0+3, hair), (12, y0+4, hair)]
    elif hair_style == 4:  # cap
        hair_pixels = [(3, y0+1, hair), (4, y0+1, hair), (5, y0+1, hair), (6, y0+1, hair),
                       (7, y0+1, hair), (8, y0+1, hair), (9, y0+1, hair), (10, y0+1, hair), (11, y0+1, hair),
                       (3, y0+2, hair)]
    elif hair_style == 5:  # helmet
        hair_pixels = [(4, y0, hair), (5, y0, hair), (6, y0, hair), (7, y0, hair),
                       (8, y0, hair), (9, y0, hair), (10, y0, hair),
                       (4, y0+1, hair), (5, y0+1, hair), (6, y0+1, hair), (7, y0+1, hair),
                       (8, y0+1, hair), (9, y0+1, hair), (10, y0+1, hair)]
    elif hair_style == 6:  # pointed hat
        hair_pixels = [(6, y0-2, hair), (7, y0-2, hair),
                       (5, y0-1, hair), (6, y0-1, hair), (7, y0-1, hair), (8, y0-1, hair), (9, y0-1, hair)]
    # hair_style == 7: bald - nothing extra

    draw_pixels(draw, hair_pixels, S)

    # ── Small body (rows 9-12) ──
    body_y = 9 + bounce
    body_pixels = [
        # Neckline
        (5, body_y, OUTLINE), (6, body_y, OUTLINE), (7, body_y, OUTLINE), (8, body_y, OUTLINE),
        # Shirt
        (4, body_y+1, outfit), (5, body_y+1, outfit), (6, body_y+1, outfit), (7, body_y+1, outfit), (8, body_y+1, outfit), (9, body_y+1, outfit),
        (4, body_y+2, outfit), (5, body_y+2, outfit), (6, body_y+2, outfit), (7, body_y+2, outfit), (8, body_y+2, outfit), (9, body_y+2, outfit),
        # Shirt highlight
        (6, body_y+1, hex_to_rgb(outfit)[:3]),  # won't be visible but keeps structure
    ]
    draw_pixels(draw, body_pixels, S)

    # ── Legs (rows 13-14) ──
    leg_y = body_y + 3
    leg_spread = 1 if frame == 1 else (-1 if frame == 3 else 0)
    leg_pixels = [
        # Left leg
        (5+leg_spread, leg_y, SHOES), (6+leg_spread, leg_y+1, SHOES),
        # Right leg
        (8-leg_spread, leg_y, SHOES), (7-leg_spread, leg_y+1, SHOES),
    ]
    draw_pixels(draw, leg_pixels, S)

    return img


def generate_agent_spritesheet():
    """Generate full spritesheet: 8 hair styles × 7 hair colors × 4 skin tones × 7 outfit colors."""
    os.makedirs(OUT_DIR, exist_ok=True)

    # Generate a showcase spritesheet with all variations
    facings = ['down', 'up', 'left', 'right']
    frames = [0, 1, 2, 3]

    # Full spritesheet: all directions × all frames for one appearance
    sheet_w = 4 * 32  # 4 frames
    sheet_h = 4 * 32  # 4 directions

    for hair_idx in range(8):
        for outfit_idx in range(7):
            sheet = Image.new('RGBA', (sheet_w, sheet_h), (0, 0, 0, 0))
            for dir_idx, facing in enumerate(facings):
                for frame_idx, frame in enumerate(frames):
                    sprite = generate_agent_sprite(hair_idx, 0, outfit_idx, facing, frame)
                    sheet.paste(sprite, (frame_idx * 32, dir_idx * 32))

            filename = f'agent_h{hair_idx}_o{outfit_idx}.png'
            sheet.save(os.path.join(OUT_DIR, filename))

    print(f'Generated {8 * 7} agent spritesheets in {OUT_DIR}')


# ── Buildings ──
def round_rect(draw, x, y, w, h, r, color):
    """Draw a rounded rectangle."""
    rgb = hex_to_rgb(color)
    draw.rounded_rectangle([x, y, x+w-1, y+h-1], radius=r, fill=rgb)


def generate_stall():
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Rounded roof (ellipse)
    rgb_roof = hex_to_rgb(BUILDING['roof'])
    draw.ellipse([2, 0, 62, 28], fill=rgb_roof)
    draw.rectangle([2, 14, 62, 20], fill=rgb_roof)

    # Roof dark stripe
    draw.rectangle([2, 20, 62, 24], fill=hex_to_rgb(BUILDING['roofDark']))

    # Banner stripes
    for i in range(8):
        x = i * 8 + 2
        draw.rectangle([x, 6, x+3, 16], fill=hex_to_rgb(BUILDING['banner']))

    # Rounded counter
    round_rect(draw, 4, 28, 56, 20, 4, BUILDING['wood'])
    draw.rectangle([4, 28, 60, 30], fill=hex_to_rgb(BUILDING['gold']))

    # Cute items on counter
    draw.rectangle([10, 22, 17, 29], fill=hex_to_rgb('#FF7043'))
    draw.rectangle([24, 22, 31, 29], fill=hex_to_rgb('#4CAF50'))
    draw.rectangle([38, 22, 45, 29], fill=hex_to_rgb('#FFD700'))

    # Legs
    draw.rectangle([8, 48, 13, 58], fill=hex_to_rgb(BUILDING['woodDark']))
    draw.rectangle([50, 48, 55, 58], fill=hex_to_rgb(BUILDING['woodDark']))

    return img


def generate_reputation():
    img = Image.new('RGBA', (96, 96), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Stone base
    round_rect(draw, 12, 68, 72, 22, 6, BUILDING['stoneDark'])
    round_rect(draw, 16, 58, 64, 14, 4, BUILDING['stone'])

    # Board
    round_rect(draw, 18, 10, 60, 48, 6, '#FFF8E1')
    draw.rectangle([16, 6, 80, 10], fill=hex_to_rgb(BUILDING['stoneDark']))
    draw.rectangle([16, 56, 80, 60], fill=hex_to_rgb(BUILDING['stoneDark']))

    # Gold crown
    draw.rectangle([38, 0, 58, 8], fill=hex_to_rgb(BUILDING['gold']))
    draw.rectangle([42, 0, 54, 4], fill=hex_to_rgb(BUILDING['gold']))
    draw.rectangle([46, 2, 50, 6], fill=hex_to_rgb('#FF5252'))

    # Stars
    for x, y in [(30, 24), (50, 20), (40, 34), (56, 30), (30, 42), (50, 40)]:
        draw.rectangle([x, y, x+4, y+4], fill=hex_to_rgb(BUILDING['gold']))
        draw.rectangle([x+1, y-1, x+3, y], fill=hex_to_rgb(BUILDING['gold']))

    # Pillars
    draw.rectangle([18, 56, 24, 80], fill=hex_to_rgb(BUILDING['stoneDark']))
    draw.rectangle([72, 56, 78, 80], fill=hex_to_rgb(BUILDING['stoneDark']))

    return img


def generate_bounty():
    img = Image.new('RGBA', (96, 96), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Wood frame
    round_rect(draw, 8, 6, 80, 62, 8, BUILDING['woodDark'])
    round_rect(draw, 12, 10, 72, 54, 6, BUILDING['wood'])

    # Paper notes
    round_rect(draw, 18, 16, 22, 16, 3, '#FFF8E1')
    round_rect(draw, 48, 14, 26, 18, 3, '#FFF8E1')
    round_rect(draw, 22, 40, 18, 16, 3, '#FFF8E1')

    # Nails
    for x, y in [(27, 14), (59, 12), (29, 38)]:
        draw.rectangle([x, y, x+2, y+2], fill=hex_to_rgb(BUILDING['gold']))

    # Base
    round_rect(draw, 14, 68, 68, 20, 6, BUILDING['woodDark'])
    draw.rectangle([18, 66, 24, 90], fill=hex_to_rgb(BUILDING['woodDark']))
    draw.rectangle([72, 66, 78, 90], fill=hex_to_rgb(BUILDING['woodDark']))

    return img


def generate_search():
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Base
    round_rect(draw, 14, 46, 36, 14, 4, BUILDING['stone'])

    # Crystal ball
    draw.ellipse([14, 10, 50, 48], fill=hex_to_rgb(BUILDING['blue']))
    draw.rectangle([14, 36, 50, 44], fill=hex_to_rgb(BUILDING['blueDark']))

    # Highlight
    draw.rectangle([22, 18, 32, 26], fill=hex_to_rgb('#B3E5FC'))

    # Star top
    draw.rectangle([28, 4, 36, 10], fill=hex_to_rgb(BUILDING['gold']))
    draw.rectangle([30, 2, 34, 4], fill=hex_to_rgb(BUILDING['gold']))

    return img


def generate_workshop():
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Base
    round_rect(draw, 14, 46, 36, 14, 4, BUILDING['blueDark'])

    # Workbench
    round_rect(draw, 10, 18, 44, 28, 4, BUILDING['blue'])
    round_rect(draw, 14, 22, 36, 20, 3, BUILDING['stone'])

    # Tools
    draw.rectangle([18, 26, 28, 30], fill=hex_to_rgb(BUILDING['gold']))
    draw.rectangle([36, 30, 40, 38], fill=hex_to_rgb(BUILDING['gold']))

    # Gear
    draw.rectangle([26, 4, 36, 14], fill=hex_to_rgb('#757575'))
    draw.rectangle([28, 2, 34, 16], fill=hex_to_rgb('#757575'))
    draw.rectangle([24, 6, 38, 10], fill=hex_to_rgb('#757575'))

    return img


# ── Tiles ──
def generate_tile(tile_id, variant=0):
    img = Image.new('RGBA', (32, 32), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    if tile_id == 0:  # Grass
        colors = [TILE['grass'], TILE['grass2'], TILE['grass3']]
        base = colors[variant % 3]
        draw.rectangle([0, 0, 31, 31], fill=hex_to_rgb(base))
        # Cute grass tufts
        dark_green = hex_to_rgb('#7CB342')
        if variant == 0:
            draw.rectangle([8, 10, 9, 13], fill=dark_green)
            draw.rectangle([22, 20, 23, 23], fill=dark_green)
            draw.rectangle([26, 8, 27, 9], fill=hex_to_rgb('#FFD700'))  # flower
        elif variant == 1:
            draw.rectangle([14, 6, 15, 9], fill=dark_green)
            draw.rectangle([6, 24, 7, 27], fill=dark_green)
            draw.rectangle([10, 18, 11, 19], fill=hex_to_rgb('#FF8A80'))  # flower
        else:
            draw.rectangle([18, 14, 19, 17], fill=dark_green)
            draw.rectangle([4, 8, 5, 11], fill=dark_green)
    elif tile_id == 1:  # Path
        base = TILE['path'] if variant == 0 else TILE['path2']
        draw.rectangle([0, 0, 31, 31], fill=hex_to_rgb(base))
        detail = hex_to_rgb('#C8B8AE') if variant == 0 else hex_to_rgb('#B0A094')
        draw.rectangle([4, 4, 5, 5], fill=detail)
        draw.rectangle([20, 16, 21, 17], fill=detail)
        draw.rectangle([12, 24, 13, 25], fill=detail)
    elif tile_id == 2:  # Stone
        draw.rectangle([0, 0, 31, 31], fill=hex_to_rgb(TILE['stone']))
        line = hex_to_rgb('#A0A0A0')
        draw.rectangle([0, 15, 31, 16], fill=line)
        draw.rectangle([15, 0, 16, 15], fill=line)
        draw.rectangle([8, 17, 9, 31], fill=line)
        draw.rectangle([24, 17, 25, 31], fill=line)
    elif tile_id == 3:  # Water
        draw.rectangle([0, 0, 31, 31], fill=hex_to_rgb(TILE['water']))
        draw.rectangle([4, 4, 11, 5], fill=hex_to_rgb('#81D4FA'))
        draw.rectangle([20, 14, 25, 15], fill=hex_to_rgb('#81D4FA'))
    else:  # Dark
        draw.rectangle([0, 0, 31, 31], fill=hex_to_rgb(TILE['dark']))

    return img


def generate_all_assets():
    os.makedirs(OUT_DIR, exist_ok=True)

    # Agent spritesheets (8 hair × 7 outfit = 56 sheets)
    generate_agent_spritesheet()

    # Buildings
    buildings = {
        'stall': generate_stall,
        'reputation': generate_reputation,
        'bounty': generate_bounty,
        'search': generate_search,
        'workshop': generate_workshop,
    }
    for name, gen_fn in buildings.items():
        img = gen_fn()
        img.save(os.path.join(OUT_DIR, f'building_{name}.png'))

    # Tiles
    for tile_id in range(5):
        for variant in range(3):
            img = generate_tile(tile_id, variant)
            img.save(os.path.join(OUT_DIR, f'tile_{tile_id}_{variant}.png'))

    print(f'All assets generated in {OUT_DIR}')

    # List files
    files = os.listdir(OUT_DIR)
    print(f'Total files: {len(files)}')


if __name__ == '__main__':
    generate_all_assets()
