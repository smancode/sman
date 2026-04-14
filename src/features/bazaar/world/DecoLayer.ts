// src/features/bazaar/world/DecoLayer.ts
// 装饰物渲染层 — 树木/灯笼/花丛等环境装饰

export interface DecoData {
  type: 'tree' | 'lantern' | 'flowers' | 'bush' | 'barrel' | 'signpost';
  col: number; // 瓦片坐标
  row: number;
}

// 装饰物放置位置（避开建筑和道路）
export const DECORATIONS: DecoData[] = [
  // 上半区草地
  { type: 'tree', col: 5, row: 3 },
  { type: 'flowers', col: 10, row: 2 },
  { type: 'lantern', col: 16, row: 2 },
  { type: 'bush', col: 15, row: 4 },
  { type: 'tree', col: 25, row: 2 },
  { type: 'flowers', col: 30, row: 3 },
  { type: 'bush', col: 37, row: 2 },

  // 中央广场四周
  { type: 'lantern', col: 9, row: 9 },
  { type: 'lantern', col: 29, row: 9 },
  { type: 'tree', col: 4, row: 11 },
  { type: 'flowers', col: 14, row: 10 },
  { type: 'bush', col: 24, row: 10 },
  { type: 'tree', col: 35, row: 11 },
  { type: 'flowers', col: 19, row: 12 },
  { type: 'lantern', col: 9, row: 14 },
  { type: 'lantern', col: 29, row: 14 },

  // 下半区草地
  { type: 'tree', col: 8, row: 20 },
  { type: 'flowers', col: 14, row: 21 },
  { type: 'bush', col: 10, row: 23 },
  { type: 'lantern', col: 16, row: 20 },
  { type: 'tree', col: 25, row: 21 },
  { type: 'flowers', col: 30, row: 22 },
  { type: 'bush', col: 37, row: 20 },
  { type: 'tree', col: 20, row: 26 },
  { type: 'flowers', col: 28, row: 27 },
];

const TS = 32;

// 装饰物尺寸 (像素)
const DECO_SIZES: Record<string, { w: number; h: number }> = {
  tree: { w: 24, h: 28 },
  lantern: { w: 10, h: 20 },
  flowers: { w: 14, h: 10 },
  bush: { w: 14, h: 12 },
  barrel: { w: 12, h: 14 },
  signpost: { w: 8, h: 20 },
};

// 颜色方案（程序化 fallback，ImageAssets 可覆盖）
const DECO_COLORS: Record<string, { main: string; dark: string; accent?: string }> = {
  tree: { main: '#2ECC71', dark: '#1E8449', accent: '#27AE60' },
  lantern: { main: '#8B4513', dark: '#5D3A1A', accent: '#FF6B00' },
  flowers: { main: '#FF6B6B', dark: '#5D3A1A', accent: '#FFEAA7' },
  bush: { main: '#6aaf30', dark: '#5D3A1A', accent: '#FF6B6B' },
  barrel: { main: '#8B4513', dark: '#5D3A1A' },
  signpost: { main: '#A0522D', dark: '#5D3A1A' },
};

export class DecoLayer {
  private images = new Map<string, HTMLImageElement>();
  private loaded = false;

  /** 尝试加载 PNG 装饰物图片 */
  async loadImages(): Promise<void> {
    const types = ['tree', 'lantern', 'flowers', 'bush'];
    const promises = types.map(async (type) => {
      try {
        const img = new Image();
        img.src = `/bazaar/assets/deco_${type}.png`;
        await img.decode();
        this.images.set(type, this.makeTransparent(img));
      } catch {
        // PNG 不存在，用程序化 fallback
      }
    });
    await Promise.allSettled(promises);
    this.loaded = true;
  }

  /** 渲染单个装饰物 */
  render(ctx: CanvasRenderingContext2D, deco: DecoData, cameraX: number, cameraY: number, frame: number): void {
    const px = deco.col * TS + TS / 2 - cameraX;
    const py = deco.row * TS + TS - cameraY;
    const size = DECO_SIZES[deco.type] ?? { w: 16, h: 16 };

    const img = this.images.get(deco.type);
    if (img) {
      // 使用 PNG
      ctx.drawImage(
        img,
        px - size.w / 2,
        py - size.h,
        size.w,
        size.h,
      );
    } else {
      // 程序化 fallback
      this.drawProcedural(ctx, deco, px, py, frame);
    }
  }

  /** 获取装饰物的 Y 坐标用于排序 */
  getSortY(deco: DecoData): number {
    return deco.row * TS;
  }

  /** 程序化绘制 fallback */
  private drawProcedural(ctx: CanvasRenderingContext2D, deco: DecoData, px: number, py: number, frame: number): void {
    const colors = DECO_COLORS[deco.type] ?? { main: '#999', dark: '#666' };
    const outColor = '#5D3A1A'; // 暖棕描边

    switch (deco.type) {
      case 'tree': {
        // 树干
        ctx.fillStyle = '#8B4513';
        ctx.fillRect(px - 2, py - 10, 5, 10);
        // 树冠 (3 个叠加圆)
        ctx.fillStyle = colors.main;
        ctx.beginPath(); ctx.arc(px, py - 16, 9, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = colors.accent ?? '#27AE60';
        ctx.beginPath(); ctx.arc(px - 3, py - 20, 7, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = colors.dark;
        ctx.beginPath(); ctx.arc(px + 4, py - 18, 6, 0, Math.PI * 2); ctx.fill();
        // 描边
        ctx.strokeStyle = outColor;
        ctx.lineWidth = 1;
        ctx.beginPath(); ctx.arc(px, py - 16, 9, 0, Math.PI * 2); ctx.stroke();
        break;
      }
      case 'lantern': {
        // 灯柱
        ctx.fillStyle = '#666';
        ctx.fillRect(px - 1, py - 16, 3, 16);
        // 灯体
        ctx.fillStyle = colors.accent ?? '#FF6B00';
        ctx.fillRect(px - 4, py - 20, 9, 6);
        // 光晕 (脉动)
        const glow = 0.15 + Math.sin(frame * 0.05) * 0.05;
        ctx.fillStyle = `rgba(255,107,0,${glow})`;
        ctx.beginPath(); ctx.arc(px, py - 17, 14, 0, Math.PI * 2); ctx.fill();
        // 描边
        ctx.strokeStyle = outColor;
        ctx.lineWidth = 1;
        ctx.strokeRect(px - 4, py - 20, 9, 6);
        break;
      }
      case 'flowers': {
        // 花茎
        const flowerColors = ['#FF6B6B', '#FFEAA7', '#DDA0DD'];
        for (let i = 0; i < 3; i++) {
          const fx = px - 5 + i * 5;
          const fy = py - 3 - Math.random() * 2;
          ctx.fillStyle = flowerColors[i];
          ctx.fillRect(fx, fy, 4, 4);
        }
        break;
      }
      case 'bush': {
        ctx.fillStyle = colors.main;
        ctx.beginPath(); ctx.arc(px, py - 6, 7, 0, Math.PI * 2); ctx.fill();
        ctx.fillStyle = colors.dark;
        ctx.beginPath(); ctx.arc(px + 3, py - 4, 4, 0, Math.PI * 2); ctx.fill();
        // 小红果
        ctx.fillStyle = colors.accent ?? '#FF6B6B';
        ctx.fillRect(px - 2, py - 8, 2, 2);
        ctx.fillRect(px + 3, py - 6, 2, 2);
        ctx.strokeStyle = outColor;
        ctx.lineWidth = 1;
        ctx.beginPath(); ctx.arc(px, py - 6, 7, 0, Math.PI * 2); ctx.stroke();
        break;
      }
      default: {
        // barrel / signpost 简单矩形
        ctx.fillStyle = colors.main;
        ctx.fillRect(px - 5, py - 12, 10, 12);
        ctx.strokeStyle = outColor;
        ctx.lineWidth = 1;
        ctx.strokeRect(px - 5, py - 12, 10, 12);
      }
    }
  }

  /** 将图片中的白色/近白色像素变为透明 */
  private makeTransparent(img: HTMLImageElement): HTMLImageElement {
    const canvas = document.createElement('canvas');
    canvas.width = img.naturalWidth;
    canvas.height = img.naturalHeight;
    const ctx = canvas.getContext('2d')!;
    ctx.drawImage(img, 0, 0);

    const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
    const data = imageData.data;
    const threshold = 240;

    for (let i = 0; i < data.length; i += 4) {
      if (data[i] > threshold && data[i + 1] > threshold && data[i + 2] > threshold) {
        data[i + 3] = 0;
      }
    }

    ctx.putImageData(imageData, 0, 0);
    const result = new Image();
    result.src = canvas.toDataURL('image/png');
    return result;
  }
}
