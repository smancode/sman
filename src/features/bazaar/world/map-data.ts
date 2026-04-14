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
  // 行 0-1：北部深色边缘
  [D,D,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,D],
  [D,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],

  // 行 2-3：市场街（摊位区）+ 北部东西主干道
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G],

  // 行 4-6：摊位区 + 主干道
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G],

  // 行 7：主干道向西弯曲（路口加宽）
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,P,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G],

  // 行 8-9：西侧南北路 + 东侧南北路
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G],

  // 行 10：东侧路分支向东
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,P,P,P,P,P,P,P,G,G,G,G,G,G,G,G,G,G,P,G,G],

  // 行 11：广场前缓冲
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G],

  // 行 12-15：中央广场（石砖）+ 西侧池塘（水）
  [G,G,W,W,W,W,G,G,G,G,G,G,G,G,G,P,G,G,G,G,S,S,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G],
  [G,G,W,W,W,W,W,G,G,G,G,G,G,G,G,P,G,G,G,S,S,S,S,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G],
  [G,G,G,W,W,W,W,G,G,G,G,G,G,G,G,P,G,G,G,S,S,S,S,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G],
  [G,G,G,G,W,W,G,G,G,G,G,G,G,G,G,P,G,G,G,G,S,S,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G],

  // 行 16-17：广场南侧
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G],

  // 行 18：南侧路口（加宽）
  [G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,P,P,P,G,G,G,G,G,G,G,P,P,P,G,G,G,G,G,G,G,G,G,P,G,G],

  // 行 19-25：南部区域（悬赏板 + 搜索站 + 工坊）
  [G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G,G,G,G,G,G,G,G,G,G,P,G,G],

  // 行 26：南部东西横道
  [G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,G,G,G,G,G,G,G,P,P,G,G],

  // 行 27：南部缓冲
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],

  // 行 28-29：南部深色边缘
  [D,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [D,D,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,D],
];

export interface BuildingData {
  id: string;
  type: 'stall' | 'reputation' | 'bounty' | 'search' | 'workshop' | 'mailbox';
  col: number; // 瓦片坐标
  row: number;
  label: string;
  width: number;
  height: number;
}

export const BUILDINGS: BuildingData[] = [
  // 摆摊区（市场街，沿北部东西主干道）
  { id: 'stall-1', type: 'stall', col: 4, row: 2, label: '摊位', width: 64, height: 64 },
  { id: 'stall-2', type: 'stall', col: 9, row: 2, label: '摊位', width: 64, height: 64 },
  { id: 'stall-3', type: 'stall', col: 14, row: 2, label: '摊位', width: 64, height: 64 },
  { id: 'stall-4', type: 'stall', col: 4, row: 4, label: '摊位', width: 64, height: 64 },
  { id: 'stall-5', type: 'stall', col: 9, row: 4, label: '摊位', width: 64, height: 64 },
  { id: 'stall-6', type: 'stall', col: 14, row: 4, label: '摊位', width: 64, height: 64 },

  // 声望榜（北部主干道终点，最大最显眼）
  { id: 'reputation', type: 'reputation', col: 24, row: 2, label: '🏆 声望榜', width: 96, height: 96 },

  // 悬赏板（西南道路旁）
  { id: 'bounty', type: 'bounty', col: 11, row: 20, label: '📋 悬赏板', width: 96, height: 96 },

  // 搜索站（东南区域）
  { id: 'search', type: 'search', col: 32, row: 20, label: '🔍 搜索站', width: 64, height: 64 },

  // 工坊（搜索站下方，"技术区"配对）
  { id: 'workshop', type: 'workshop', col: 32, row: 23, label: '🔧 工坊', width: 64, height: 64 },
];

export function getMapPixelSize() {
  return {
    width: DESIGN.MAP_COLS * DESIGN.TILE_SIZE,
    height: DESIGN.MAP_ROWS * DESIGN.TILE_SIZE,
  };
}
