import { useState, useEffect, useRef, useMemo } from 'react';
import { Wrench, Terminal, Route, Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useWsConnection } from '@/stores/ws-connection';
import { t } from '@/locales';


export interface Skill {
  id: string;
  name: string;
  description: string;
  content: string;
}

export interface CommandItem {
  id: string;
  name: string;
  description: string;
}

export interface PathItem {
  id: string;
  name: string;
  description: string;
}

export type PickerItem = {
  id: string;
  name: string;
  description: string;
  category: 'command' | 'skill' | 'path';
};

interface SkillPickerProps {
  open: boolean;
  onClose: () => void;
  onSelect: (item: PickerItem) => void;
  sessionId: string;
  filter?: string;
}

const CATEGORY_META: Record<PickerItem['category'], { label: string; icon: typeof Terminal }> = {
  command: { label: 'Commands', icon: Terminal },
  skill: { label: 'Skills', icon: Wrench },
  path: { label: 'Paths', icon: Route },
};

function fuzzyMatch(text: string, query: string): boolean {
  const lower = text.toLowerCase();
  const q = query.toLowerCase();
  let qi = 0;
  for (let i = 0; i < lower.length && qi < q.length; i++) {
    if (lower[i] === q[qi]) qi++;
  }
  return qi === q.length;
}

export function SkillPicker({ open, onClose, onSelect, sessionId, filter = '' }: SkillPickerProps) {
  const [skills, setSkills] = useState<Skill[]>([]);
  const [commands, setCommands] = useState<CommandItem[]>([]);
  const [paths, setPaths] = useState<PathItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const client = useWsConnection((s) => s.client);

  useEffect(() => {
    if (!open || !sessionId || !client) return;

    setLoading(true);
    setError(null);
    setSelectedIndex(0);

    const handleMessage = (data: any) => {
      if (data.type === 'skills.listProject') {
        setSkills(data.skills || []);
        setCommands(data.commands || []);
        setPaths(data.paths || []);
        setLoading(false);
      } else if (data.type === 'error' && data.sessionId === sessionId) {
        setError(data.error || t('skill.loadFail'));
        setLoading(false);
      }
    };

    client.on('message', handleMessage);
    client.send({ type: 'skills.listProject', sessionId });

    return () => {
      client.off('message', handleMessage);
    };
  }, [open, sessionId, client]);

  const allItems = useMemo<PickerItem[]>(() => {
    const items: PickerItem[] = [
      ...commands.map(c => ({ ...c, category: 'command' as const })),
      ...skills.map(s => ({ ...s, category: 'skill' as const })),
      ...paths.map(p => ({ ...p, category: 'path' as const })),
    ];

    if (!filter) return items;

    return items.filter(item => fuzzyMatch(item.name, filter));
  }, [commands, skills, paths, filter]);

  useEffect(() => {
    setSelectedIndex(0);
  }, [filter]);

  useEffect(() => {
    if (!open) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
        return;
      }

      if (allItems.length === 0) return;

      switch (e.key) {
        case 'ArrowDown':
          e.preventDefault();
          setSelectedIndex((prev) => (prev + 1) % allItems.length);
          break;
        case 'ArrowUp':
          e.preventDefault();
          setSelectedIndex((prev) => (prev - 1 + allItems.length) % allItems.length);
          break;
        case 'Enter':
          e.preventDefault();
          if (allItems[selectedIndex]) {
            onSelect(allItems[selectedIndex]);
          }
          break;
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [open, allItems, selectedIndex, onClose, onSelect]);

  useEffect(() => {
    if (containerRef.current) {
      const buttons = containerRef.current.querySelectorAll<HTMLButtonElement>('[data-item]');
      const selectedEl = buttons[selectedIndex];
      if (selectedEl) {
        selectedEl.scrollIntoView({ block: 'nearest' });
      }
    }
  }, [selectedIndex]);

  const groupedItems = useMemo(() => {
    const groups: { category: PickerItem['category']; items: PickerItem[] }[] = [];
    let currentCategory: PickerItem['category'] | null = null;

    for (const item of allItems) {
      if (item.category !== currentCategory) {
        currentCategory = item.category;
        groups.push({ category: item.category, items: [item] });
      } else {
        groups[groups.length - 1].items.push(item);
      }
    }
    return groups;
  }, [allItems]);

  if (!open) return null;

  let flatIndex = 0;

  return (
    <div className="bg-card rounded-lg shadow-lg border border-border overflow-hidden z-50">
      <div className="flex items-center gap-2 px-3 py-2 border-b border-border bg-muted/30 overflow-hidden">
        <Wrench className="h-4 w-4 text-muted-foreground shrink-0" />
        <span className="text-sm font-medium truncate">
          {filter ? `${t('skill.filter')}: ${filter}` : t('skill.selectCommand')}
        </span>
        <span className="text-xs text-muted-foreground ml-auto shrink-0">
          ↑↓ {t('skill.keyHint')}
        </span>
      </div>

      <div ref={containerRef} className="max-h-[280px] overflow-y-auto py-1 overflow-x-hidden [&::-webkit-scrollbar]:hidden [-ms-overflow-style:none] [scrollbar-width:none]">
        {loading ? (
          <div className="flex items-center justify-center py-8">
            <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
          </div>
        ) : error ? (
          <div className="px-3 py-4 text-sm text-destructive text-center">{error}</div>
        ) : allItems.length === 0 ? (
          <div className="px-3 py-4 text-sm text-muted-foreground text-center">
            {filter ? t('skill.noMatch') : t('skill.noItems')}
          </div>
        ) : (
          groupedItems.map((group) => {
            const meta = CATEGORY_META[group.category];
            const Icon = meta.icon;
            return (
              <div key={group.category}>
                <div className="flex items-center gap-1.5 px-3 pt-2 pb-0.5">
                  <Icon className="h-3 w-3 text-muted-foreground" />
                  <span className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">{meta.label}</span>
                </div>
                {group.items.map((item) => {
                  const idx = flatIndex++;
                  return (
                    <button
                      key={`${item.category}-${item.id}`}
                      data-item
                      onClick={() => onSelect(item)}
                      className={cn(
                        'w-full flex flex-col items-start gap-0.5 px-3 py-1.5 text-left transition-colors overflow-hidden min-w-0',
                        idx === selectedIndex
                          ? 'bg-primary/10 text-primary'
                          : 'hover:bg-muted/50 text-foreground'
                      )}
                    >
                      <span className="text-sm font-medium truncate w-full">{item.name}</span>
                      {item.description && (
                        <span className="text-xs text-muted-foreground truncate w-full">
                          {item.description}
                        </span>
                      )}
                    </button>
                  );
                })}
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
