import { useState, useCallback, useEffect, memo } from 'react';
import { Eye, EyeOff, Plus, Trash2, ChevronDown, ChevronRight } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import { useWsConnection } from '@/stores/ws-connection';
import { t } from '@/locales';
import type { WeComBotProfile } from '@/types/settings';

interface WeComBotEditorProps {
  bots: WeComBotProfile[];
  enabled: boolean;
  onUpdateBots: (bots: WeComBotProfile[]) => void;
}

export const WeComBotEditor = memo(function WeComBotEditor({ bots, enabled, onUpdateBots }: WeComBotEditorProps) {
  const [expandedBots, setExpandedBots] = useState<Record<string, boolean>>({});
  const [showSecrets, setShowSecrets] = useState<Record<string, boolean>>({});
  const [botSkills, setBotSkills] = useState<Record<string, string[]>>({});
  const [botSkillsLoading, setBotSkillsLoading] = useState<Record<string, boolean>>({});
  const [workspaces, setWorkspaces] = useState<string[]>([]);

  const getWs = useCallback(() => useWsConnection.getState().client, []);

  // Fetch available workspaces on mount
  useEffect(() => {
    const client = getWs();
    if (!client) return;

    const handler = (...args: unknown[]) => {
      const msg = args[0] as Record<string, unknown>;
      if (msg.workspaces) {
        setWorkspaces(msg.workspaces as string[]);
      }
      client.off('cron.workspaces', handler);
    };
    client.on('cron.workspaces', handler);
    client.send({ type: 'cron.workspaces' });

    return () => { client.off('cron.workspaces', handler); };
  }, [getWs]);

  // Auto-load skills for query-mode bots that already have a workspace
  useEffect(() => {
    const client = getWs();
    if (!client) return;

    for (const bot of bots) {
      if (bot.mode === 'query' && bot.workspace && !botSkills[bot.id] && !botSkillsLoading[bot.id]) {
        loadSkills(bot.id, bot.workspace);
      }
    }
  }, [bots]); // eslint-disable-line react-hooks/exhaustive-deps

  const addBot = () => {
    const newBot: WeComBotProfile = {
      id: crypto.randomUUID(),
      label: '',
      botId: '',
      secret: '',
      mode: 'full',
      workspace: '',
      allowedSkills: [],
      enabled: true,
    };
    onUpdateBots([...bots, newBot]);
    setExpandedBots((prev) => ({ ...prev, [newBot.id]: true }));
  };

  const removeBot = (botId: string) => {
    onUpdateBots(bots.filter((b) => b.id !== botId));
  };

  const updateBot = (botId: string, updates: Partial<WeComBotProfile>) => {
    onUpdateBots(bots.map((b) => (b.id === botId ? { ...b, ...updates } : b)));
  };

  const toggleExpanded = (botId: string) => {
    setExpandedBots((prev) => ({ ...prev, [botId]: !prev[botId] }));
  };

  const loadSkills = (botId: string, workspace: string) => {
    if (!workspace) {
      setBotSkills((prev) => ({ ...prev, [botId]: [] }));
      return;
    }
    const client = getWs();
    if (!client) return;

    setBotSkillsLoading((prev) => ({ ...prev, [botId]: true }));

    const handler = (...args: unknown[]) => {
      const msg = args[0] as Record<string, unknown>;
      if (msg.skills) {
        setBotSkills((prev) => ({ ...prev, [botId]: msg.skills as string[] }));
      }
      setBotSkillsLoading((prev) => ({ ...prev, [botId]: false }));
      client.off('chatbot.listWorkspaceSkills', handler);
    };
    client.on('chatbot.listWorkspaceSkills', handler);
    client.send({ type: 'chatbot.listWorkspaceSkills', workspace });

    setTimeout(() => {
      setBotSkillsLoading((prev) => ({ ...prev, [botId]: false }));
      client.off('chatbot.listWorkspaceSkills', handler);
    }, 10_000);
  };

  const toggleSkill = (botId: string, skillName: string) => {
    const bot = bots.find((b) => b.id === botId);
    if (!bot) return;
    const skills = bot.allowedSkills.includes(skillName)
      ? bot.allowedSkills.filter((s) => s !== skillName)
      : [...bot.allowedSkills, skillName];
    updateBot(botId, { allowedSkills: skills });
  };

  if (!enabled) return null;

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <Label className="text-sm font-medium">{t('chatbot.botList')} ({bots.length})</Label>
        <Button variant="outline" size="sm" onClick={addBot}>
          <Plus className="h-4 w-4 mr-1" />
          {t('chatbot.addBot')}
        </Button>
      </div>

      {bots.length === 0 && (
        <p className="text-sm text-muted-foreground text-center py-4">
          {t('chatbot.addBot')}
        </p>
      )}

      {bots.map((bot) => (
        <div key={bot.id} className="border rounded-lg">
          {/* Collapsible header */}
          <button
            type="button"
            className="w-full flex items-center justify-between p-3 hover:bg-muted/50 transition-colors"
            onClick={() => toggleExpanded(bot.id)}
          >
            <div className="flex items-center gap-2">
              {expandedBots[bot.id] ? (
                <ChevronDown className="h-4 w-4 text-muted-foreground" />
              ) : (
                <ChevronRight className="h-4 w-4 text-muted-foreground" />
              )}
              <span className="text-sm font-medium">
                {bot.label || bot.botId || t('chatbot.botLabel')}
              </span>
              {bot.mode === 'query' && (
                <span className="text-xs bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300 px-1.5 py-0.5 rounded">
                  {t('chatbot.modeQuery')}
                </span>
              )}
              {bot.mode === 'collect' && (
                <span className="text-xs bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300 px-1.5 py-0.5 rounded">
                  {t('chatbot.modeCollect')}
                </span>
              )}
            </div>
            <div className="flex items-center gap-2">
              <Switch
                checked={bot.enabled}
                onCheckedChange={(checked) => updateBot(bot.id, { enabled: checked })}
                onClick={(e) => e.stopPropagation()}
              />
              <Button
                variant="ghost"
                size="sm"
                onClick={(e) => { e.stopPropagation(); removeBot(bot.id); }}
                className="h-7 text-destructive hover:text-destructive"
              >
                <Trash2 className="h-3.5 w-3.5" />
              </Button>
            </div>
          </button>

          {/* Expandable detail */}
          {expandedBots[bot.id] && (
            <div className="px-3 pb-3 space-y-3 border-t">
              <div className="space-y-1 pt-3">
                <Label className="text-xs">{t('chatbot.botLabel')}</Label>
                <Input
                  value={bot.label}
                  onChange={(e) => updateBot(bot.id, { label: e.target.value })}
                  placeholder={t('chatbot.botLabel')}
                  className="h-8 text-sm"
                />
              </div>

              <div className="space-y-1">
                <Label className="text-xs">{t('settings.chatbot.wecom.botId')}</Label>
                <Input
                  value={bot.botId}
                  onChange={(e) => updateBot(bot.id, { botId: e.target.value })}
                  placeholder={t('settings.chatbot.wecom.botId.placeholder')}
                  className="h-8 text-sm"
                />
              </div>

              <div className="space-y-1">
                <Label className="text-xs">{t('settings.chatbot.wecom.secret')}</Label>
                <div className="relative">
                  <Input
                    type={showSecrets[bot.id] ? 'text' : 'password'}
                    value={bot.secret}
                    onChange={(e) => updateBot(bot.id, { secret: e.target.value })}
                    placeholder={t('settings.chatbot.wecom.secret.placeholder')}
                    className="h-8 text-sm pr-8"
                  />
                  <button
                    type="button"
                    onClick={() => setShowSecrets((prev) => ({ ...prev, [bot.id]: !prev[bot.id] }))}
                    className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                  >
                    {showSecrets[bot.id] ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
                  </button>
                </div>
              </div>

              {/* Mode toggle */}
              <div className="space-y-1">
                <Label className="text-xs">{t('chatbot.botMode')}</Label>
                <div className="flex gap-1">
                  <Button
                    variant={bot.mode === 'full' ? 'default' : 'outline'}
                    size="sm"
                    className="h-7 text-xs flex-1"
                    onClick={() => updateBot(bot.id, { mode: 'full', workspace: '', allowedSkills: [] })}
                  >
                    {t('chatbot.modeFull')}
                  </Button>
                  <Button
                    variant={bot.mode === 'query' ? 'default' : 'outline'}
                    size="sm"
                    className="h-7 text-xs flex-1"
                    onClick={() => updateBot(bot.id, { mode: 'query' })}
                  >
                    {t('chatbot.modeQuery')}
                  </Button>
                  <Button
                    variant={bot.mode === 'collect' ? 'default' : 'outline'}
                    size="sm"
                    className="h-7 text-xs flex-1"
                    onClick={() => updateBot(bot.id, { mode: 'collect', workspace: '', allowedSkills: [] })}
                  >
                    {t('chatbot.modeCollect')}
                  </Button>
                </div>
              </div>

              {/* Workspace (query mode only) */}
              {bot.mode === 'query' && (
                <div className="space-y-1">
                  <Label className="text-xs">{t('chatbot.botWorkspace')}</Label>
                  <Select
                    value={bot.workspace}
                    onValueChange={(ws) => {
                      updateBot(bot.id, { workspace: ws, allowedSkills: [] });
                      loadSkills(bot.id, ws);
                    }}
                  >
                    <SelectTrigger className="h-8 text-sm">
                      <SelectValue placeholder={t('chatbot.selectWorkspace')} />
                    </SelectTrigger>
                    <SelectContent>
                      {workspaces.map((ws) => (
                        <SelectItem key={ws} value={ws}>
                          {ws.split(/[/\\]/).pop()}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              )}

              {/* Skills whitelist (query mode only) */}
              {bot.mode === 'query' && bot.workspace && (
                <div className="space-y-1">
                  <Label className="text-xs">{t('chatbot.skillWhitelist')}</Label>
                  {botSkillsLoading[bot.id] ? (
                    <p className="text-xs text-muted-foreground">{t('chatbot.loadingSkills')}</p>
                  ) : (botSkills[bot.id] ?? []).length === 0 ? (
                    <p className="text-xs text-muted-foreground">{t('chatbot.noSkills')}</p>
                  ) : (
                    <div className="space-y-1 max-h-40 overflow-y-auto border rounded p-2">
                      {(botSkills[bot.id] ?? []).map((skill) => (
                        <label key={skill} className="flex items-center gap-2 text-xs cursor-pointer">
                          <input
                            type="checkbox"
                            checked={bot.allowedSkills.includes(skill)}
                            onChange={() => toggleSkill(bot.id, skill)}
                            className="rounded"
                          />
                          <span>{skill}</span>
                        </label>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      ))}
    </div>
  );
});
