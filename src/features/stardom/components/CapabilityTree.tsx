// src/features/stardom/components/CapabilityTree.tsx
// 进化仓 — 能力数字化三层架构
// 第一层：可完全数字协作(绿) / 第二层：协作增强(蓝) / 第三层：辅助支持(灰)
// 五维评分：可表达性 × 可观察性 × 可重复性 × 可评估性 × 可拆分性

import { useStardomStore } from '@/stores/stardom';
import { useState, useMemo } from 'react';
import { ScrollArea } from '@/components/ui/scroll-area';
import { t } from '@/locales';

// ── 三层数字化适配模型 ──

interface DigitizationLayer {
  key: string;
  title: string;
  subtitle: string;
  icon: string;
  color: string;
  glow: string;
  bgColor: string;
  keywords: string[];
}

const LAYERS: DigitizationLayer[] = [
  {
    key: 'auto',
    title: t('stardom.cap.autoLayer'),
    subtitle: t('stardom.cap.autoSubtitle'),
    icon: '⬡',
    color: 'var(--bz-green)',
    glow: 'var(--bz-green-glow)',
    bgColor: 'rgba(16, 185, 129, 0.05)',
    keywords: ['文档', '数据', '检索', '整理', '清洗', '分类', '摘要', '生成', '报告', '抽取', '自动化', '规则', '校验', '格式', '转换', '归档', '索引', '扫描', '监控', '同步', '编译', '测试', '代码', '部署', '构建'],
  },
  {
    key: 'enhanced',
    title: t('stardom.cap.collabLayer'),
    subtitle: t('stardom.cap.collabSubtitle'),
    icon: '◈',
    color: 'var(--bz-cyan)',
    glow: 'var(--bz-cyan-glow)',
    bgColor: 'rgba(6, 182, 212, 0.05)',
    keywords: ['管理', '分析', '策划', '研究', '协调', '设计', '支持', '辅助', '决策', '评估', '方案', '规划', '排期', '拆解', '风险', '合规', '培训', '客户', '沟通', '协作', '编排', '调度'],
  },
  {
    key: 'assist',
    title: t('stardom.cap.supportLayer'),
    subtitle: t('stardom.cap.supportSubtitle'),
    icon: '◉',
    color: 'var(--bz-text-dim)',
    glow: 'transparent',
    bgColor: 'rgba(100, 116, 139, 0.05)',
    keywords: ['领导', '战略', '谈判', '创意', '审美', '关系', '信任', '激励', '文化', '愿景', '判断', '直觉', '现场', '情感', '博弈'],
  },
];

// ── 五维评分计算 ──

function calculateDigitizationScore(capability: string, experience: string): number {
  const text = (capability + ' ' + experience).toLowerCase();

  // 每个维度 0-1，关键词命中越多分数越高
  const dimensions = [
    { keywords: ['规则', '流程', '标准', '模板', '方法', 'SOP', '规范', '框架'], weight: 1 },       // 可表达性
    { keywords: ['记录', '日志', '追踪', '监控', '数据', '指标', '反馈', '结果'], weight: 1 },         // 可观察性
    { keywords: ['重复', '常规', '批量', '周期', '标准', '模式', '复用', '通用'], weight: 1 },          // 可重复性
    { keywords: ['评分', '质量', '正确率', '完整度', '时效', '评估', '达标', '效果'], weight: 1 },      // 可评估性
    { keywords: ['拆解', '分解', '模块', '步骤', '子任务', '单元', '组件', '分工'], weight: 1 },        // 可拆分性
  ];

  let totalScore = 0;
  for (const dim of dimensions) {
    const hits = dim.keywords.filter(kw => text.includes(kw)).length;
    // 基础分 + 关键词加分，上限 1
    const dimScore = Math.min(1, 0.3 + hits * 0.35);
    totalScore += dimScore * dim.weight;
  }

  return Math.round((totalScore / dimensions.length) * 100);
}

function classifyLayer(capability: string, experience: string): string {
  const text = (capability + ' ' + experience).toLowerCase();

  // 先检查辅助层（最低优先级但最明确）
  for (const kw of LAYERS[2].keywords) {
    if (text.includes(kw)) return 'assist';
  }
  // 再检查全自动层
  for (const kw of LAYERS[0].keywords) {
    if (text.includes(kw)) return 'auto';
  }
  // 再检查协作增强层
  for (const kw of LAYERS[1].keywords) {
    if (text.includes(kw)) return 'enhanced';
  }
  // 默认归为协作增强层
  return 'enhanced';
}

// ── 单条能力卡片 ──

function CapabilityItem({ capability, experience, agentName, score }: {
  capability: string;
  experience: string;
  agentName: string;
  score: number;
}) {
  const scoreColor = score >= 70 ? 'var(--bz-green)' : score >= 40 ? 'var(--bz-cyan)' : 'var(--bz-text-dim)';

  return (
    <div className="rounded px-2.5 py-2 space-y-1.5" style={{ background: 'var(--bz-bg)', border: '1px solid var(--bz-border)' }}>
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium" style={{ color: 'var(--bz-text)' }}>{capability}</span>
        <span className="text-[10px] font-mono" style={{ color: scoreColor }}>{score}%</span>
      </div>
      {/* 适配度条 */}
      <div className="w-full h-1 rounded-full overflow-hidden" style={{ background: 'var(--bz-bg-card)' }}>
        <div
          className="h-full rounded-full transition-all duration-500"
          style={{ width: `${score}%`, background: scoreColor, boxShadow: `0 0 4px ${scoreColor}` }}
        />
      </div>
      {experience && (
        <p className="text-[10px] line-clamp-2" style={{ color: 'var(--bz-text-dim)' }}>{experience}</p>
      )}
      <div className="text-[9px]" style={{ color: 'var(--bz-text-dim)' }}>
        {t('stardom.cap.depositFrom').replace('{agent}', agentName)}
      </div>
    </div>
  );
}

// ── 层级面板 ──

function LayerPanel({ layer, items }: {
  layer: DigitizationLayer;
  items: Array<{ capability: string; experience: string; agentName: string; score: number }>;
}) {
  const [expanded, setExpanded] = useState(false);
  const count = items.length;
  const avgScore = count > 0 ? Math.round(items.reduce((s, i) => s + i.score, 0) / count) : 0;

  return (
    <div className="rounded-lg overflow-hidden" style={{ background: layer.bgColor, border: `1px solid var(--bz-border)` }}>
      <button
        className="w-full flex items-center gap-3 px-3 py-2.5 text-left hover:bg-white/5 transition-colors"
        onClick={() => setExpanded(!expanded)}
      >
        <span className="text-lg flex-shrink-0" style={{ color: layer.color, textShadow: `0 0 8px ${layer.glow}` }}>
          {layer.icon}
        </span>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-sm font-medium" style={{ color: layer.color }}>{layer.title}</span>
            <span className="text-[10px] font-mono px-1.5 py-0.5 rounded" style={{ background: layer.color + '20', color: layer.color }}>
              {count} {t('stardom.cap.items')}
            </span>
          </div>
          <div className="text-[10px] mt-0.5" style={{ color: 'var(--bz-text-dim)' }}>{layer.subtitle}</div>
          {/* {t('stardom.cap.avgFitness')} */}
          {count > 0 && (
            <div className="w-full h-0.5 rounded-full mt-1.5 overflow-hidden" style={{ background: 'var(--bz-bg)' }}>
              <div className="h-full rounded-full transition-all duration-500" style={{ width: `${avgScore}%`, background: layer.color }} />
            </div>
          )}
        </div>
        <div className="flex flex-col items-end flex-shrink-0">
          <span className="text-sm font-mono font-bold" style={{ color: layer.color, textShadow: `0 0 6px ${layer.glow}` }}>
            {avgScore}%
          </span>
          <span className="text-[9px]" style={{ color: 'var(--bz-text-dim)' }}>{t('stardom.cap.avgFitness')}</span>
        </div>
      </button>

      {expanded && count > 0 && (
        <div className="px-3 pb-2 space-y-1.5" style={{ borderTop: '1px solid var(--bz-border)' }}>
          {items.map((item, i) => (
            <CapabilityItem key={i} {...item} />
          ))}
        </div>
      )}
    </div>
  );
}

// ── 主组件 ──

export function CapabilityTree() {
  const { capabilities } = useStardomStore();

  const layerData = useMemo(() => {
    const result: Record<string, Array<{ capability: string; experience: string; agentName: string; score: number }>> = {
      auto: [],
      enhanced: [],
      assist: [],
    };

    for (const cap of capabilities) {
      const layerKey = classifyLayer(cap.capability, cap.experience);
      const score = calculateDigitizationScore(cap.capability, cap.experience);
      result[layerKey].push({
        capability: cap.capability,
        experience: cap.experience,
        agentName: cap.agentName,
        score,
      });
    }

    // 每层内按适配度降序
    for (const key of Object.keys(result)) {
      result[key].sort((a, b) => b.score - a.score);
    }

    return result;
  }, [capabilities]);

  const totalCapabilities = capabilities.length;
  const autoCount = layerData.auto.length;
  const autoPct = totalCapabilities > 0 ? Math.round((autoCount / totalCapabilities) * 100) : 0;

  return (
    <div className="h-full flex flex-col" style={{ background: 'var(--bz-bg)' }}>
      {/* Header */}
      <div className="px-4 py-3" style={{ borderBottom: '1px solid var(--bz-border)' }}>
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-sm font-medium flex items-center gap-2" style={{ color: 'var(--bz-text)' }}>
              <span style={{ color: 'var(--bz-cyan)', textShadow: '0 0 8px var(--bz-cyan)' }}>⬡</span>
              {t('stardom.cap.evolutionBay')}
            </h2>
            <p className="text-[10px] mt-0.5" style={{ color: 'var(--bz-text-dim)' }}>
              {t('stardom.cap.summary').replace('{count}', String(totalCapabilities)).replace('{pct}', String(autoPct))}
            </p>
          </div>
          <div className="flex items-center gap-1.5">
            <div className="w-2 h-2 rounded-full" style={{ background: 'var(--bz-green)', boxShadow: '0 0 6px var(--bz-green-glow)', animation: 'bz-node-breathe 3s ease-in-out infinite' }} />
            <span className="text-[10px]" style={{ color: 'var(--bz-green)' }}>EVOLVING</span>
          </div>
        </div>

        {/* 三层总览条 */}
        {totalCapabilities > 0 && (
          <div className="flex mt-2.5 h-2 rounded-full overflow-hidden gap-0.5" style={{ background: 'var(--bz-bg-card)' }}>
            {LAYERS.map((layer) => {
              const count = layerData[layer.key].length;
              if (count === 0) return null;
              return (
                <div
                  key={layer.key}
                  className="h-full rounded-full transition-all duration-500"
                  style={{ width: `${(count / totalCapabilities) * 100}%`, background: layer.color, boxShadow: `0 0 4px ${layer.glow}` }}
                  title={`${layer.title}: ${count} ${t('stardom.cap.items')}`}
                />
              );
            })}
          </div>
        )}
      </div>

      {/* 三层列表 */}
      <ScrollArea className="flex-1">
        <div className="p-3 space-y-2">
          {LAYERS.map((layer) => (
            <LayerPanel
              key={layer.key}
              layer={layer}
              items={layerData[layer.key]}
            />
          ))}
        </div>
      </ScrollArea>

      {/* Empty state */}
      {totalCapabilities === 0 && (
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
          <div className="text-center space-y-2">
            <div className="text-4xl opacity-20">⬡</div>
            <p className="text-sm" style={{ color: 'var(--bz-text-dim)' }}>{t('stardom.cap.waiting')}</p>
            <p className="text-xs" style={{ color: 'var(--bz-text-dim)', opacity: 0.6 }}>
              {t('stardom.cap.hint')}
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
