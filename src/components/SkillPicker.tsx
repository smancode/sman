import { useState, useEffect, useRef } from 'react';
import { Wrench, Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useWsConnection } from '@/stores/ws-connection';

interface Skill {
  id: string;
  name: string;
  description: string;
  content: string;
}

interface SkillPickerProps {
  open: boolean;
  onClose: () => void;
  onSelect: (skill: Skill) => void;
  sessionId: string;
}

export function SkillPicker({ open, onClose, onSelect, sessionId }: SkillPickerProps) {
  const [skills, setSkills] = useState<Skill[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const client = useWsConnection((s) => s.client);

  // Load skills when opened
  useEffect(() => {
    if (!open || !sessionId || !client) return;

    setLoading(true);
    setError(null);
    setSelectedIndex(0);

    const handleMessage = (data: any) => {
      if (data.type === 'skills.listProject') {
        setSkills(data.skills || []);
        setLoading(false);
      } else if (data.type === 'error' && data.sessionId === sessionId) {
        setError(data.error || '加载失败');
        setLoading(false);
      }
    };

    client.on('message', handleMessage);
    client.send({ type: 'skills.listProject', sessionId });

    // Cleanup
    return () => {
      client.off('message', handleMessage);
    };
  }, [open, sessionId, client]);

  // Handle keyboard navigation
  useEffect(() => {
    if (!open) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
        return;
      }

      if (skills.length === 0) return;

      switch (e.key) {
        case 'ArrowDown':
          e.preventDefault();
          setSelectedIndex((prev) => (prev + 1) % skills.length);
          break;
        case 'ArrowUp':
          e.preventDefault();
          setSelectedIndex((prev) => (prev - 1 + skills.length) % skills.length);
          break;
        case 'Enter':
          e.preventDefault();
          if (skills[selectedIndex]) {
            onSelect(skills[selectedIndex]);
          }
          break;
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [open, skills, selectedIndex, onClose, onSelect]);

  // Scroll selected item into view
  useEffect(() => {
    if (containerRef.current) {
      const selectedEl = containerRef.current.children[selectedIndex] as HTMLElement;
      if (selectedEl) {
        selectedEl.scrollIntoView({ block: 'nearest' });
      }
    }
  }, [selectedIndex]);

  if (!open) return null;

  return (
    <div className="bg-card rounded-lg shadow-lg border border-border overflow-hidden z-50">
      {/* Header */}
      <div className="flex items-center gap-2 px-3 py-2 border-b border-border bg-muted/30">
        <Wrench className="h-4 w-4 text-muted-foreground" />
        <span className="text-sm font-medium">选择 Skill</span>
        <span className="text-xs text-muted-foreground ml-auto">
          ↑↓ 选择 · Enter 确认 · Esc 取消
        </span>
      </div>

      {/* Skills list */}
      <div ref={containerRef} className="max-h-[240px] overflow-y-auto py-1">
        {loading ? (
          <div className="flex items-center justify-center py-8">
            <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
          </div>
        ) : error ? (
          <div className="px-3 py-4 text-sm text-destructive text-center">{error}</div>
        ) : skills.length === 0 ? (
          <div className="px-3 py-4 text-sm text-muted-foreground text-center">
            暂无项目 Skills
            <p className="text-xs mt-1 text-muted-foreground/60">
              在 .claude/skills/ 目录下添加 .md 文件
            </p>
          </div>
        ) : (
          skills.map((skill, index) => (
            <button
              key={skill.id}
              onClick={() => onSelect(skill)}
              className={cn(
                'w-full flex flex-col items-start gap-0.5 px-3 py-2 text-left transition-colors',
                index === selectedIndex
                  ? 'bg-primary/10 text-primary'
                  : 'hover:bg-muted/50 text-foreground'
              )}
            >
              <span className="text-sm font-medium">{skill.name}</span>
              {skill.description && (
                <span className="text-xs text-muted-foreground truncate">
                  {skill.description}
                </span>
              )}
            </button>
          ))
        )}
      </div>
    </div>
  );
}
