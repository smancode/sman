// src/features/bazaar/world/map-data.ts
// 游戏大厅布局 — 40×30 瓦片
// 设计理念：中心枢纽 + 辐射路径 + 分区色彩身份
//
// 核心布局（squint test 可辨）：
//
//   Row 0-1:  北部深色边界
//   Row 2-4:  声望榜（3x3大建筑，北区地标）
//   Row 5-7:  市场街（6摊位两排 + 东西宽路）
//   Row 8-10: 北部缓冲（南北主干道 visible）
//   Row 11-16: 中央广场（石砖，最大亮区，锚点）
//   Row 17-18: 广场南出口（3条辐射路径起点）
//   Row 19-24: 南部三区分散（悬赏/工坊/搜索各有路径连通）
//   Row 25-29: 南部缓冲+深色边界
//
// 瓦片: 0=草地, 1=路径, 2=石砖, 3=水, 4=深色边界

import { DESIGN } from './palette';

type TileId = 0 | 1 | 2 | 3 | 4;

const G: TileId = 0;
const P: TileId = 1;
const S: TileId = 2;
const W: TileId = 3;
const D: TileId = 4;

export const MAP_DATA: TileId[][] = [
  // ── Row 0: 北部深色边界 ──
  [D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D],
  // ── Row 1: 北部缓冲 ──
  [D,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],

  // ── Row 2-4: 声望榜独占区（col 18-20, 3x3瓦片=96x96px）──
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,G,G,G,G,G,G,G,G,G,G],

  // ── Row 5-6: 市场街（摊位区+宽路）──
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,G,G,G,G,G,G,G,G,G,G],

  // ── Row 7: 市场街南 ──
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,G,G,G,G,G,G,G,G,G,G],

  // ── Row 8-9: 市场街到广场之间（主干道缩窄到4瓦片）──
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],

  // ── Row 10: 广场北侧入口 ──
  [W,W,W,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],

  // ── Row 11-16: 中央广场（石砖，6行×10列，最大亮区）──
  [W,W,W,W,G,G,G,G,G,G,G,G,G,G,G,S,S,S,S,S,S,S,S,S,S,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [W,W,W,W,G,G,G,G,G,G,G,G,G,G,S,S,S,S,S,S,S,S,S,S,S,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [W,W,W,W,G,G,G,G,G,G,G,G,G,G,S,S,S,S,S,S,S,S,S,S,S,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [W,W,W,W,G,G,G,G,G,G,G,G,G,G,S,S,S,S,S,S,S,S,S,S,S,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [W,W,W,G,G,G,G,G,G,G,G,G,G,G,S,S,S,S,S,S,S,S,S,S,S,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [W,W,W,G,G,G,G,G,G,G,G,G,G,G,G,S,S,S,S,S,S,S,S,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],

  // ── Row 17: 广场南出口（3条辐射路径起点）──
  [G,W,W,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],

  // ── Row 18: 南部主干道（3条分支可见：左/中/右）──
  [G,G,G,G,G,G,G,G,G,G,G,G,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,G,G,G,G,G,G,G,G],

  // ── Row 19: 三路分叉 ──
  //   左路(col 8-9)→悬赏板  中路(col 18-21)→工坊  右路(col 28-29)→搜索站
  [G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,P,P,P,P,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,P,P,P,P,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,P,P,P,P,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,P,P,P,P,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,P,P,P,P,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G],

  // ── Row 24: 南部末端 ──
  [G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,P,P,P,P,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G],

  // ── Row 25-27: 南部缓冲 ──
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],

  // ── Row 28-29: 南部深色边界 ──
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,D],
  [D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D,D],
];

export interface BuildingData {
  id: string;
  type: 'stall' | 'reputation' | 'bounty' | 'search' | 'workshop' | 'mailbox';
  col: number;
  row: number;
  label: string;
  width: number;
  height: number;
}

export const BUILDINGS: BuildingData[] = [
  // ── 声望榜：北区中央，3x3（col 18-20, row 2-4），最显眼 ──
  { id: 'reputation', type: 'reputation', col: 18, row: 2, label: '🏆 声望榜', width: 96, height: 96 },

  // ── 市场街：6摊位两排排列 ──
  // 上排(row 5): col 15, 20, 25（间隔5瓦片）
  { id: 'stall-1', type: 'stall', col: 15, row: 5, label: '🏪 摊位', width: 64, height: 64 },
  { id: 'stall-2', type: 'stall', col: 20, row: 5, label: '🏪 摊位', width: 64, height: 64 },
  { id: 'stall-3', type: 'stall', col: 25, row: 5, label: '🏪 摊位', width: 64, height: 64 },
  // 下排(row 7): col 15, 20, 25
  { id: 'stall-4', type: 'stall', col: 15, row: 7, label: '🏪 摊位', width: 64, height: 64 },
  { id: 'stall-5', type: 'stall', col: 20, row: 7, label: '🏪 摊位', width: 64, height: 64 },
  { id: 'stall-6', type: 'stall', col: 25, row: 7, label: '🏪 摊位', width: 64, height: 64 },

  // ── 悬赏板：西南，左路终点（col 7, row 20，路径在col 8-9连通）──
  { id: 'bounty', type: 'bounty', col: 7, row: 20, label: '📜 悬赏板', width: 96, height: 96 },

  // ── 工坊：正南，中路终点（col 18, row 23，路径在col 18-21连通）──
  { id: 'workshop', type: 'workshop', col: 18, row: 23, label: '🔧 工坊', width: 64, height: 64 },

  // ── 搜索站：东南，右路终点（col 28, row 20，路径在col 28-29连通）──
  { id: 'search', type: 'search', col: 28, row: 20, label: '🔍 搜索站', width: 64, height: 64 },
];

export function getMapPixelSize() {
  return {
    width: DESIGN.MAP_COLS * DESIGN.TILE_SIZE,
    height: DESIGN.MAP_ROWS * DESIGN.TILE_SIZE,
  };
}
