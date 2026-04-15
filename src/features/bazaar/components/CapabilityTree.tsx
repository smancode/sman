// src/features/bazaar/components/CapabilityTree.tsx
// 能力树 / 进化仓 — 可视化 learned_routes 数据
// 六大能力分支：执行 / 推理 / 协调 / 工具 / 风控 / 学习

import { useBazaarStore } from '@/stores/bazaar';
import { useState, useMemo } from 'react';
import { ScrollArea } from '@/components/ui/scroll-area';

interface CapabilityBranch {
  key: string;
  label: string;
  icon: string;
  color: string;
  glow: string;
  keywords: string[];
}

const CAPABILITY_BRANCHES: CapabilityBranch[] = [
  { key: 'execution', label: '执行能力', icon: '⚡', color: 'var(--bz-cyan)', glow: 'var(--bz-cyan-glow)', keywords: ['执行', '部署', '构建', '运行', '编译', '测试', '代码', '实现'] },
  { key: 'reasoning', label: '推理能力', icon: '🧠', color: 'var(--bz-purple)', glow: 'var(--bz-purple)', keywords: ['分析', '推理', '判断', '决策', '评估', '诊断', '排查'] },
  { key: 'coordination', label: '协调能力', icon: '⬡', color: 'var(--bz-green)', glow: 'var(--bz-green-glow)', keywords: ['协作', '协调', '沟通', '路由', '分配', '调度', '编排'] },
  { key: 'tools', label: '工具调用', icon: '🔧', color: 'var(--bz-amber)', glow: 'var(--bz-amber-glow)', keywords: ['工具', 'API', 'MCP', '搜索', '查询', '调用', '浏览器'] },
  { key: 'risk', label: '风控能力', icon: '🛡', color: 'var(--bz-red)', glow: 'var(--bz-red)', keywords: ['风控', '安全', '审计', '合规', '检测', '验证', '校验'] },
  { key: 'learning', label: '学习适应', icon: '📡', color: 'var(--bz-blue)', glow: 'var(--bz-blue)', keywords: ['学习', '适应', '进化', '经验', '提取', '记忆', '总结'] },
];

function classifyCapability(cap: string, experience: string): string {
  const text = (cap + ' ' + experience).toLowerCase();
  for (const branch of CAPABILITY_BRANCHES) {
    if (branch.keywords.some(kw => text.includes(kw))) return branch.key;
  }
  return 'execution'; // default
}

function BranchNode({ branch, items }: { branch: CapabilityBranch; items: Array<{ capability: string; experience: string; agentName: string }> }) {
  const [expanded, setExpanded] = useState(false);
  const count = items.length;
  const level = Math.min(5, Math.floor(count / 2) + 1);

  return (
    <div className="rounded-lg overflow-hidden" style={{ background: 'var(--bz-bg-card)', border: `1px solid var(--bz-border)` }}>
      {/* Branch header */}
      <button
        className="w-full flex items-center gap-3 px-3 py-2.5 text-left hover:bg-white/5 transition-colors"
        onClick={() => setExpanded(!expanded)}
      >
        <span className="text-lg" style={{ filter: `drop-shadow(0 0 4px ${branch.color})` }}>{branch.icon}</span>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium" style={{ color: branch.color }}>{branch.label}</span>
            <span className="text-[10px] font-mono px-1.5 py-0.5 rounded" style={{ background: branch.color + '20', color: branch.color }}>
              Lv.{level}
            </span>
          </div>
          {/* Level progress bar */}
          <div className="w-full h-1 rounded-full mt-1.5 overflow-hidden" style={{ background: 'var(--bz-bg)' }}>
            <div
              className="h-full rounded-full transition-all duration-500"
              style={{
                width: `${Math.min(100, (count / 10) * 100)}%`,
                background: branch.color,
                boxShadow: `0 0 4px ${branch.glow}`,
              }}
            />
          </div>
        </div>
        <div className="flex flex-col items-end">
          <span className="text-xs font-mono" style={{ color: branch.color }}>{count}</span>
          <span className="text-[9px]" style={{ color: 'var(--bz-text-dim)' }}>技能</span>
        </div>
      </button>

      {/* Expanded items */}
      {expanded && count > 0 && (
        <div className="px-3 pb-2 space-y-1" style={{ borderTop: '1px solid var(--bz-border)' }}>
          {items.map((item, i) => (
            <div key={i} className="rounded px-2 py-1.5 space-y-0.5" style={{ background: 'var(--bz-bg)' }}>
              <div className="text-xs font-medium" style={{ color: 'var(--bz-text)' }}>{item.capability}</div>
              {item.experience && (
                <p className="text-[10px] line-clamp-2" style={{ color: 'var(--bz-text-dim)' }}>{item.experience}</p>
              )}
              <div className="text-[9px]" style={{ color: branch.color }}>
                来源: {item.agentName}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export function CapabilityTree() {
  const { capabilities } = useBazaarStore();

  // Classify capabilities into branches
  const branches = useMemo(() => {
    const result: Record<string, Array<{ capability: string; experience: string; agentName: string }>> = {};
    for (const branch of CAPABILITY_BRANCHES) {
      result[branch.key] = [];
    }
    for (const cap of capabilities) {
      const branchKey = classifyCapability(cap.capability, cap.experience);
      result[branchKey].push({ capability: cap.capability, experience: cap.experience, agentName: cap.agentName });
    }
    return result;
  }, [capabilities]);

  const totalCapabilities = capabilities.length;

  return (
    <div className="h-full flex flex-col" style={{ background: 'var(--bz-bg)' }}>
      {/* Header */}
      <div className="px-4 py-3 flex items-center justify-between" style={{ borderBottom: '1px solid var(--bz-border)' }}>
        <div>
          <h2 className="text-sm font-medium" style={{ color: 'var(--bz-text)' }}>
            <span style={{ color: 'var(--bz-cyan)', textShadow: '0 0 6px var(--bz-cyan)' }}>⬡</span> 进化仓
          </h2>
          <p className="text-[10px] mt-0.5" style={{ color: 'var(--bz-text-dim)' }}>
            已习得 {totalCapabilities} 项能力 · 持续进化中
          </p>
        </div>
        <div className="flex items-center gap-1.5">
          <div className="w-2 h-2 rounded-full" style={{ background: 'var(--bz-green)', boxShadow: '0 0 6px var(--bz-green-glow)', animation: 'bz-node-breathe 3s ease-in-out infinite' }} />
          <span className="text-[10px]" style={{ color: 'var(--bz-green)' }}>ACTIVE</span>
        </div>
      </div>

      {/* Capability branches */}
      <ScrollArea className="flex-1">
        <div className="p-3 space-y-2">
          {CAPABILITY_BRANCHES.map((branch) => (
            <BranchNode
              key={branch.key}
              branch={branch}
              items={branches[branch.key]}
            />
          ))}
        </div>
      </ScrollArea>

      {/* Empty state */}
      {totalCapabilities === 0 && (
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
          <div className="text-center space-y-2">
            <div className="text-4xl opacity-20">⬡</div>
            <p className="text-sm" style={{ color: 'var(--bz-text-dim)' }}>暂无习得能力</p>
            <p className="text-xs" style={{ color: 'var(--bz-text-dim)', opacity: 0.6 }}>
              完成协作任务后，经验将自动沉淀为能力节点
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
