// src/features/bazaar/world/map-data.ts
// 像素风集市世界地图 — 40×30 瓦片
// 0=草地, 1=路径, 2=石砖, 3=水, 4=深色装饰

import { DESIGN } from './palette';

type TileId = 0 | 1 | 2 | 3 | 4;

// 快捷常量
const G: TileId = 0; // 草地
const P: TileId = 1; // 路径
const S: TileId = 2; // 石砖
const W: TileId = 3; // 水
const D: TileId = 4; // 深色装饰

export const MAP_DATA: TileId[][] = [
  // 行 0-5：摆摊区（左上）+ 声望榜（右上）
  [D,D,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,D,D,D,G,G,G,G,G,G,G,G],
  [D,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,D,D,D,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G,D,D,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G,D,D,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G,G,D,D,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],

  // 行 6-8：横贯道路
  [P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P],
  [P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P],
  [P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P],

  // 行 9-14：中央广场（大面积路径+草地点缀）
  [P,P,G,G,P,P,G,G,P,P,G,G,P,P,G,G,P,P,G,G,P,P,G,G,P,P,G,G,P,P,G,G,P,P,G,G,P,P,P,P,P,P],
  [P,G,G,G,G,P,G,G,G,G,P,G,G,G,G,P,G,G,G,G,P,G,G,G,G,P,G,G,G,G,P,G,G,G,G,P,G,G,G,G,P,P],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [P,G,G,G,G,P,G,G,G,G,P,G,G,G,G,P,G,G,G,G,P,G,G,G,G,P,G,G,G,G,P,G,G,G,G,P,G,G,G,G,P,P],
  [P,P,G,G,P,P,G,G,P,P,G,G,P,P,G,G,P,P,G,G,P,P,G,G,P,P,G,G,P,P,G,G,P,P,G,G,P,P,P,P,P,P],

  // 行 15-17：横贯道路
  [P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P],
  [P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P],
  [P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P],

  // 行 18-23：悬赏板区（左下）+ 搜索站（右下）
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],

  // 行 24-29：底部装饰
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [D,D,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,D,D],
  [D,D,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,D,D],
  [D,D,D,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,D,D,D],
];

export interface BuildingData {
  id: string;
  type: 'stall' | 'reputation' | 'bounty' | 'search' | 'workshop' | 'mailbox';
  col: number; // 瓦片坐标
  row: number;
  label: string;
}

export const BUILDINGS: BuildingData[] = [
  // 摆摊区（左上，6 个摊位）
  { id: 'stall-1', type: 'stall', col: 2, row: 1, label: '摊位' },
  { id: 'stall-2', type: 'stall', col: 7, row: 1, label: '摊位' },
  { id: 'stall-3', type: 'stall', col: 12, row: 1, label: '摊位' },
  { id: 'stall-4', type: 'stall', col: 2, row: 4, label: '摊位' },
  { id: 'stall-5', type: 'stall', col: 7, row: 4, label: '摊位' },
  { id: 'stall-6', type: 'stall', col: 12, row: 4, label: '摊位' },

  // 声望榜（右上）
  { id: 'reputation', type: 'reputation', col: 33, row: 1, label: '🏆 声望榜' },

  // 悬赏板（左下）
  { id: 'bounty', type: 'bounty', col: 3, row: 19, label: '📋 悬赏板' },

  // 搜索站（右下）
  { id: 'search', type: 'search', col: 33, row: 19, label: '🔍 搜索站' },

  // 工坊（右下角）
  { id: 'workshop', type: 'workshop', col: 33, row: 23, label: '🔧 工坊' },
];

export function getMapPixelSize() {
  return {
    width: DESIGN.MAP_COLS * DESIGN.TILE_SIZE,
    height: DESIGN.MAP_ROWS * DESIGN.TILE_SIZE,
  };
}
