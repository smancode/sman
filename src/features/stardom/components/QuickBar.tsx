// src/features/stardom/components/QuickBar.tsx
// 底部快捷入口 — 战术面板入口

import { Dna, Map, Trophy, FileText, Network } from 'lucide-react';
import { t } from '@/locales';

const QUICK_ITEMS = [
  { icon: Network, labelKey: 'stardom.quick.network', key: 'network' },
  { icon: Dna, labelKey: 'stardom.quick.evolution', key: 'evolution' },
  { icon: Trophy, labelKey: 'stardom.quick.reputation', key: 'reputation' },
  { icon: Map, labelKey: 'stardom.quick.battles', key: 'battles' },
  { icon: FileText, labelKey: 'stardom.quick.report', key: 'report' },
];

export function QuickBar() {
  return (
    <div className="flex items-center justify-center gap-1 px-3 py-1.5" style={{ borderTop: '1px solid var(--bz-border)', background: 'var(--bz-bg-panel)' }}>
      {QUICK_ITEMS.map(({ icon: Icon, labelKey, key }) => (
        <button
          key={key}
          className="flex items-center gap-1.5 px-3 py-1 rounded text-xs transition-all duration-200 hover:bg-white/10"
          style={{ color: 'var(--bz-text-dim)' }}
          onMouseEnter={(e) => {
            e.currentTarget.style.color = 'var(--bz-cyan)';
            e.currentTarget.style.textShadow = '0 0 8px var(--bz-cyan)';
          }}
          onMouseLeave={(e) => {
            e.currentTarget.style.color = 'var(--bz-text-dim)';
            e.currentTarget.style.textShadow = 'none';
          }}
        >
          <Icon className="h-3 w-3" />
          {t(labelKey)}
        </button>
      ))}
    </div>
  );
}
