// src/features/bazaar/world/map-data.ts
// 像素风集市世界地图 — 40×30 瓦片
// 设计规则: 主路3瓦片宽、建筑间距≥2瓦片、分区色彩身份、一条路原则
//
// 0=草地, 1=路径, 2=石砖, 3=水, 4=深色装饰(边界/围栏)

import { DESIGN } from './palette';

type TileId = 0 | 1 | 2 | 3 | 4;

const G: TileId = 0;
const P: TileId = 1;
const S: TileId = 2;
const W: TileId = 3;
const D: TileId = 4;

// 布局说明：
// - 中央广场 (col 16-23, row 11-16) 石砖，最亮区域
// - 北部市场街 (row 2-5) 6个摊位沿宽路排列
// - 西侧魔法水池 (col 1-5, row 10-16) 深水区域
// - 南部任务区 (row 18-24) 悬赏板+搜索站+工坊
// - 主干道全部 3 瓦片宽，支路 2 瓦片宽
// - 所有建筑周围留 ≥2 瓦片草地缓冲
export const MAP_DATA: TileId[][] = [
  // 行 0-1：北部深色边缘（自然边界）
  [D,D,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,D],
  [D,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],

  // 行 2-3：市场街主干道（3瓦片宽）+ 北侧摊位
  [G,G,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],

  // 行 4-5：摊位区（摊位在草地缓冲上）
  [G,G,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],

  // 行 6：声望榜前缓冲 + 北部东西横道（3瓦片宽）
  [G,G,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,G,G,G,G,G,G,G],

  // 行 7-8：南北主干道（3瓦片宽）+ 北部缓冲区
  [G,G,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,P,G,G,G,G],
  [G,G,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,G,G,G,G,G,G,G,G,G,G,P,P,P,G,G,G,G],

  // 行 9：主干道继续 + 东西向分支（2瓦片宽，向东延伸到搜索站）
  [G,G,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,G,P,P,G,G,G,G,G,G,G,P,P,P,G,G,G,G],

  // 行 10：广场北侧缓冲
  [G,G,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,G,P,P,G,G,G,G,G,G,G,P,P,P,G,G,G,G],

  // 行 11-16：中央广场（石砖）+ 西侧水池（水）
  [G,W,W,W,G,P,P,P,G,G,G,P,P,P,G,S,S,S,S,S,S,S,P,P,G,G,G,G,G,G,G,G,G,P,P,P,G,G,G,G],
  [G,W,W,W,W,P,P,P,G,G,G,P,P,G,S,S,S,S,S,S,S,S,P,P,G,G,G,G,G,G,G,G,G,P,P,P,G,G,G,G],
  [G,W,W,W,W,P,P,P,G,G,G,P,P,G,S,S,S,S,S,S,S,S,P,P,G,G,G,G,G,G,G,G,G,P,P,P,G,G,G,G],
  [G,W,W,W,G,P,P,P,G,G,G,P,P,G,S,S,S,S,S,S,S,S,P,P,G,G,G,G,G,G,G,G,G,P,P,P,G,G,G,G],
  [G,G,W,W,G,P,P,P,G,G,G,P,P,P,G,S,S,S,S,S,S,S,P,P,G,G,G,G,G,G,G,G,G,P,P,P,G,G,G,G],
  [G,G,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,P,P,P,G,G,G,G],

  // 行 17：广场南侧
  [G,G,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,P,P,P,G,G,G,G],

  // 行 18：南侧路口（加宽到3瓦片）
  [G,G,G,G,P,P,P,P,P,G,G,G,P,P,P,P,P,G,G,G,G,G,P,P,P,P,P,G,G,G,G,G,G,P,P,P,G,G,G,G],

  // 行 19-24：南部区域（悬赏板 + 搜索站 + 工坊）
  [G,G,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,P,P,P,G,G,G,G],
  [G,G,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,P,P,P,G,G,G,G],
  [G,G,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,P,P,P,G,G,G,G],
  [G,G,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,P,P,P,G,G,G,G],
  [G,G,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,P,P,P,G,G,G,G],
  [G,G,G,G,G,P,P,P,G,G,G,P,P,P,G,G,G,G,G,G,G,G,P,P,G,G,G,G,G,G,G,G,G,P,P,P,G,G,G,G],

  // 行 25：南部东西横道（3瓦片宽）
  [G,G,G,G,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,P,G,G,G],

  // 行 26-27：南部缓冲
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],

  // 行 28-29：南部深色边缘
  [D,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G],
  [D,D,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,G,D],
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
  // 摆摊区（市场街，沿北部东西主干道，每个摊位间隔 ≥2 瓦片）
  { id: 'stall-1', type: 'stall', col: 6, row: 3, label: '摊位', width: 64, height: 64 },
  { id: 'stall-2', type: 'stall', col: 11, row: 3, label: '摊位', width: 64, height: 64 },
  { id: 'stall-3', type: 'stall', col: 16, row: 3, label: '摊位', width: 64, height: 64 },
  { id: 'stall-4', type: 'stall', col: 6, row: 5, label: '摊位', width: 64, height: 64 },
  { id: 'stall-5', type: 'stall', col: 11, row: 5, label: '摊位', width: 64, height: 64 },
  { id: 'stall-6', type: 'stall', col: 16, row: 5, label: '摊位', width: 64, height: 64 },

  // 声望榜（北部主干道终点，最大最显眼）
  { id: 'reputation', type: 'reputation', col: 21, row: 4, label: '声望榜', width: 96, height: 96 },

  // 悬赏板（西南道路旁）
  { id: 'bounty', type: 'bounty', col: 6, row: 20, label: '悬赏板', width: 96, height: 96 },

  // 搜索站（东南区域）
  { id: 'search', type: 'search', col: 28, row: 20, label: '搜索站', width: 64, height: 64 },

  // 工坊（搜索站下方，"技术区"配对）
  { id: 'workshop', type: 'workshop', col: 28, row: 23, label: '工坊', width: 64, height: 64 },
];

export function getMapPixelSize() {
  return {
    width: DESIGN.MAP_COLS * DESIGN.TILE_SIZE,
    height: DESIGN.MAP_ROWS * DESIGN.TILE_SIZE,
  };
}
