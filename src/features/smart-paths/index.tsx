import { useEffect, useState, useMemo, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ChevronLeft, ChevronDown, ChevronRight, Plus, Trash2, Play, Loader2, CheckCircle, XCircle,
  FolderOpen, Save, GripVertical, Route, Pencil, Square,
  Clock, FileText, FileCode, BookOpen, Ban, Send, MessageSquare,
} from 'lucide-react';
import { Streamdown } from 'streamdown';
import 'streamdown/styles.css';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from '@/components/ui/select';
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter, DialogDescription,
} from '@/components/ui/dialog';
import { useSmartPathStore } from '@/stores/smart-path';
import { useCronStore } from '@/stores/cron';
import { useWsConnection } from '@/stores/ws-connection';
import type { SmartPath, SmartPathRun, SmartPathStep } from '@/types/settings';
import { t } from '@/locales';

const STATUS_CONFIG: Record<string, { labelKey: string; variant: 'default' | 'success' | 'warning' | 'destructive' | 'secondary' | 'outline' }> = {
  draft: { labelKey: 'smartpath.status.draft', variant: 'secondary' },
  ready: { labelKey: 'smartpath.status.ready', variant: 'outline' },
  running: { labelKey: 'smartpath.executing', variant: 'default' },
  completed: { labelKey: 'smartpath.status.completed', variant: 'success' },
  failed: { labelKey: 'smartpath.status.failed', variant: 'destructive' },
};

function getStepCount(p: SmartPath): number {
  try { return (JSON.parse(p.steps) as SmartPathStep[]).length; } catch { return 0; }
}

// ── Left sidebar ──

function PathList({ paths, currentPath, onSelect, onNew, onBack }: {
  paths: SmartPath[];
  currentPath: SmartPath | null;
  onSelect: (p: SmartPath) => void;
  onNew: () => void;
  onBack: () => void;
}) {
  const groups = useMemo(() => {
    const map = new Map<string, SmartPath[]>();
    for (const p of paths) {
      const name = p.workspace.split(/[/\\]/).pop() || p.workspace;
      if (!map.has(name)) map.set(name, []);
      map.get(name)!.push(p);
    }
    return Array.from(map.entries()).sort((a, b) => a[0].localeCompare(b[0]));
  }, [paths]);

  return (
    <div className="w-64 shrink-0 flex flex-col h-full">
      <div className="p-3 space-y-2">
        <button onClick={onBack} className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors">
          <ChevronLeft className="h-4 w-4" /> {t('smartpath.back')}
        </button>
        <Button variant="outline" size="sm" className="w-full" onClick={onNew}>
          <Plus className="h-4 w-4 mr-1" /> {t('smartpath.newPath')}
        </Button>
      </div>
      <div className="flex-1 overflow-y-auto p-2 space-y-4">
        {groups.length === 0 && <div className="text-xs text-muted-foreground text-center py-8">{t('smartpath.noPaths')}</div>}
        {groups.map(([wsName, wsPaths]) => (
          <div key={wsName}>
            <div className="flex items-center gap-1.5 px-2 py-1 text-xs text-muted-foreground">
              <FolderOpen className="h-3 w-3" /> {wsName}
            </div>
            <div className="space-y-1">
              {wsPaths.map((p) => {
                const sc = STATUS_CONFIG[p.status];
                return (
                  <button key={p.id} onClick={() => onSelect(p)}
                    className={cn('flex flex-col gap-1 w-full rounded-lg px-3 py-2 text-left transition-colors',
                      currentPath?.id === p.id ? 'bg-primary/10 text-primary' : 'text-foreground hover:bg-muted')}>
                    <div className="flex items-center justify-between gap-1">
                      <span className="font-medium text-sm truncate">{p.name}</span>
                      <div className="flex items-center gap-1 shrink-0">
                        {p.cronExpression && <Clock className="h-3 w-3 text-muted-foreground" />}
                        <Badge variant={sc?.variant || 'outline'} className="text-[10px] px-1.5 py-0">{sc ? t(sc.labelKey) : p.status}</Badge>
                      </div>
                    </div>
                    <span className="text-xs text-muted-foreground">{getStepCount(p)}{t('smartpath.steps')}</span>
                  </button>
                );
              })}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ── Step edit card ──

function StepEditCard({ step, index, total, onChange, onDelete, onExecute, executing, executionStream, executionCompleted, workspace }: {
  step: SmartPathStep; index: number; total: number;
  onChange: (s: SmartPathStep) => void; onDelete: () => void;
  onExecute: () => void;
  executing: boolean; executionStream: string; executionCompleted: boolean; workspace: string;
}) {
  const streamRef = useRef<HTMLDivElement>(null);
  const hasResult = executionCompleted || !!executionStream;
  const [skillsExpanded, setSkillsExpanded] = useState(false);
  const [availableSkills, setAvailableSkills] = useState<string[]>([]);
  const [skillsLoading, setSkillsLoading] = useState(false);

  const loadSkills = useCallback(() => {
    if (!workspace) { setAvailableSkills([]); return; }
    const client = useWsConnection.getState().client;
    if (!client) return;
    setSkillsLoading(true);
    const handler = (...args: unknown[]) => {
      const msg = args[0] as Record<string, unknown>;
      if (msg.skills) {
        setAvailableSkills(msg.skills as string[]);
      }
      setSkillsLoading(false);
      client.off('chatbot.listWorkspaceSkills', handler);
    };
    client.on('chatbot.listWorkspaceSkills', handler);
    client.send({ type: 'chatbot.listWorkspaceSkills', workspace });
    setTimeout(() => {
      setSkillsLoading(false);
      client.off('chatbot.listWorkspaceSkills', handler);
    }, 10_000);
  }, [workspace]);

  const toggleSkill = useCallback((skillId: string) => {
    const current = step.skills || [];
    const next = current.includes(skillId)
      ? current.filter((s) => s !== skillId)
      : [...current, skillId];
    onChange({ ...step, skills: next });
  }, [step, onChange]);

  useEffect(() => {
    if (skillsExpanded && availableSkills.length === 0 && !skillsLoading) {
      loadSkills();
    }
  }, [skillsExpanded, availableSkills.length, skillsLoading, loadSkills]);

  useEffect(() => {
    if (executing && streamRef.current) {
      streamRef.current.scrollTop = streamRef.current.scrollHeight;
    }
  }, [executionStream, executing]);

  return (
    <>
      <div className="flex justify-center"><div className="w-px h-4 bg-border" /></div>
      <Card className="relative group">
        <CardContent className="p-4 space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <GripVertical className="h-4 w-4 text-muted-foreground cursor-move shrink-0" />
            <span className="text-sm font-semibold text-muted-foreground shrink-0">{index + 1}</span>
            <Input value={step.name || ''} onChange={(e) => onChange({ ...step, name: e.target.value })}
              placeholder={t("smartpath.stepName")} className="h-6 text-sm border-0 p-0 shadow-none focus-visible:ring-0 font-medium min-w-0" />
            <div className="flex-1" />
            <Button variant="outline" size="sm" className="h-7 text-xs gap-1 shrink-0"
              disabled={!step.userInput.trim() || executing || !workspace} onClick={onExecute}>
              {executing ? <Loader2 className="h-3 w-3 animate-spin" />
                : <Play className="h-3 w-3" />}
              {executing ? t('smartpath.executing') : t('smartpath.execute')}
            </Button>
            <Button variant="ghost" size="icon" className="h-7 w-7 opacity-0 group-hover:opacity-100 transition-opacity" onClick={onDelete}>
              <Trash2 className="h-3.5 w-3.5 text-muted-foreground" />
            </Button>
          </div>
          <Textarea value={step.userInput} onChange={(e) => onChange({ ...step, userInput: e.target.value })}
            placeholder={t("smartpath.stepInput")} className="min-h-[56px] text-sm resize-none" />

          <div className="space-y-1">
            <Label className="text-xs text-muted-foreground">{t('smartpath.deliveryCheck')}</Label>
            <Input value={step.deliveryCheck || ''} onChange={(e) => onChange({ ...step, deliveryCheck: e.target.value })}
              placeholder={t('smartpath.deliveryCheckPlaceholder')} className="h-6 text-xs" />
          </div>

          {/* Skill 选择器 — 可展开/折叠 */}
          <div className="space-y-1">
            <button
              type="button"
              className="flex items-center gap-1.5 text-xs text-muted-foreground hover:text-foreground transition-colors w-full"
              onClick={() => setSkillsExpanded((v) => !v)}
            >
              {skillsExpanded ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
              <span>{t('smartpath.stepSkills')}</span>
              {step.skills && step.skills.length > 0 && (
                <Badge variant="secondary" className="h-4 px-1.5 text-[10px]">{step.skills.length}</Badge>
              )}
            </button>
            {skillsExpanded && (
              <div className="ml-1">
                <p className="text-[11px] text-muted-foreground mb-1.5">{t('smartpath.stepSkillsHint')}</p>
                {skillsLoading ? (
                  <p className="text-xs text-muted-foreground">{t('smartpath.loadingSkills')}</p>
                ) : availableSkills.length === 0 ? (
                  <p className="text-xs text-muted-foreground">{t('smartpath.noSkillsAvailable')}</p>
                ) : (
                  <div className="space-y-1 max-h-40 overflow-y-auto border rounded p-2">
                    {availableSkills.map((skill) => (
                      <label key={skill} className="flex items-center gap-2 text-xs cursor-pointer">
                        <input
                          type="checkbox"
                          checked={(step.skills || []).includes(skill)}
                          onChange={() => toggleSkill(skill)}
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

          {(executing || hasResult) && (
            <Card className="border border-muted-foreground/20">
              <CardContent className="p-3">
                <div className="text-xs text-muted-foreground mb-1.5 font-medium">
                  {executing ? t('smartpath.executionProcess') : t('smartpath.executionResult')}
                </div>
                <div ref={executing ? streamRef : undefined}
                  className="markdown-content prose prose-sm dark:prose-invert max-w-none break-words text-sm leading-relaxed max-h-80 overflow-y-auto whitespace-pre-wrap">
                  {executing && !executionStream ? (
                    <span className="text-muted-foreground animate-pulse">{t("smartpath.waitingResponse")}</span>
                  ) : (
                    <Streamdown mode='static' controls={{ code: true, table: true }}>
                      {executionStream || ''}
                    </Streamdown>
                  )}
                </div>
              </CardContent>
            </Card>
          )}
        </CardContent>
      </Card>
    </>
  );
}

// ── Step control bar (step-by-step mode) ──

function StepControlBar({ stepIndex, isLastStep, hasResult, executing, onRedo, onContinue, onFinalize, onGuide }: {
  stepIndex: number; isLastStep: boolean; hasResult?: boolean; executing?: boolean;
  onRedo: () => void; onContinue: () => void; onFinalize: () => void; onGuide?: () => void;
}) {
  return (
    <div className="flex items-center gap-2 mt-2">
      {hasResult && (
        <Button variant="outline" size="sm" className="h-7 text-xs gap-1" disabled={executing} onClick={onRedo}>
          {executing ? <Loader2 className="h-3 w-3 animate-spin" /> : <Play className="h-3 w-3" />} {t('smartpath.redoStep')}
        </Button>
      )}
      {hasResult && onGuide && (
        <Button variant="outline" size="sm" className="h-7 text-xs gap-1" disabled={executing} onClick={onGuide}>
          <BookOpen className="h-3 w-3" /> {t('smartpath.generateGuide')}
        </Button>
      )}
      {isLastStep ? (
        <Button size="sm" className="h-7 text-xs gap-1" disabled={executing} onClick={onFinalize}>
          {executing ? <Loader2 className="h-3 w-3 animate-spin" /> : <CheckCircle className="h-3 w-3" />} {t('smartpath.finalizeStep')}
        </Button>
      ) : (
        <Button size="sm" className="h-7 text-xs gap-1" disabled={executing} onClick={onContinue}>
          {executing ? <Loader2 className="h-3 w-3 animate-spin" /> : null} {executing ? t('smartpath.executing') : t('smartpath.continueStep')}
        </Button>
      )}
    </div>
  );
}

// ── Step view card ──

function StepViewCard({ step, index, total, executionStream, executing, stepping, stepResult, stepDesc, stepDeliveryCheck, finalizing, onResultChange, onDescChange, onRedo, onContinue, onFinalize, pathId, workspace }: {
  step: SmartPathStep; index: number; total: number;
  executionStream?: string; executing?: boolean;
  stepping?: boolean; finalizing?: boolean;
  stepResult?: string; stepDesc?: string;
  stepDeliveryCheck?: { passed?: boolean; reason?: string; retried?: boolean };
  onResultChange?: (v: string) => void;
  onDescChange?: (v: string) => void;
  onRedo?: () => void; onContinue?: () => void; onFinalize?: () => void;
  pathId?: string; workspace?: string;
}) {
  const streamRef = useRef<HTMLDivElement>(null);
  const guideChatRef = useRef<HTMLDivElement>(null);

  const guideChatOpen = useSmartPathStore((s) => s.guideChatOpen[index] || false);
  const guideChatMessages = useSmartPathStore((s) => s.guideChatMessages[index] || []);
  const guideChatLoading = useSmartPathStore((s) => s.guideChatLoading[index] || false);
  const guideChatStream = useSmartPathStore((s) => s.guideChatStream[index] || '');
  const startGuideChat = useSmartPathStore((s) => s.startGuideChat);
  const sendGuideMessage = useSmartPathStore((s) => s.sendGuideMessage);
  const saveGuide = useSmartPathStore((s) => s.saveGuide);
  const closeGuideChat = useSmartPathStore((s) => s.closeGuideChat);
  const [guideInput, setGuideInput] = useState('');

  useEffect(() => {
    if (executing && streamRef.current) {
      streamRef.current.scrollTop = streamRef.current.scrollHeight;
    }
  }, [executionStream, executing]);

  useEffect(() => {
    if (guideChatRef.current) {
      guideChatRef.current.scrollTop = guideChatRef.current.scrollHeight;
    }
  }, [guideChatMessages, guideChatStream]);

  const handleGuideStart = useCallback(() => {
    if (!pathId || !workspace || !stepResult) return;
    startGuideChat(pathId, workspace, index, stepResult);
  }, [pathId, workspace, index, stepResult, startGuideChat]);

  const handleGuideSend = useCallback(() => {
    if (!pathId || !workspace || !guideInput.trim()) return;
    sendGuideMessage(pathId, workspace, index, guideInput.trim());
    setGuideInput('');
  }, [pathId, workspace, index, guideInput, sendGuideMessage]);

  const handleGuideSave = useCallback(() => {
    if (!pathId || !workspace) return;
    saveGuide(pathId, workspace, index);
  }, [pathId, workspace, index, saveGuide]);

  const handleGuideKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleGuideSend();
    }
  }, [handleGuideSend]);

  const stepCompleted = stepping && !!stepResult;
  const stepRunning = executing;

  let circleClass = 'w-7 h-7 rounded-full flex items-center justify-center text-xs font-semibold shrink-0';
  let circleContent: React.ReactNode = index + 1;
  if (stepCompleted) {
    circleClass += ' bg-green-500/15 text-green-600';
    circleContent = <CheckCircle className="h-4 w-4" />;
  } else if (stepRunning) {
    circleClass += ' bg-primary/10 text-primary';
    circleContent = <Loader2 className="h-4 w-4 animate-spin" />;
  } else {
    circleClass += ' bg-primary/10 text-primary';
  }

  return (
    <div className="flex items-start gap-3">
      <div className="flex flex-col items-center">
        <div className={circleClass}>{circleContent}</div>
        {index < total - 1 && <div className={cn('w-px flex-1 min-h-[16px] mt-1', stepCompleted ? 'bg-green-500/30' : 'bg-border')} />}
      </div>
      <div className="flex-1 pb-3">
        {step.name && <p className="text-sm font-medium break-words">{step.name}</p>}
        <p className="text-sm leading-relaxed text-muted-foreground break-words">{step.userInput}</p>

        {(executing || (executionStream && !(stepping && stepResult))) && (
          <div className="mt-2 rounded-md border border-muted-foreground/20 p-3">
            <div className="text-xs text-muted-foreground mb-1.5 font-medium">
              {executing ? (
                <><Loader2 className="h-3 w-3 inline animate-spin mr-1" />{t("smartpath.executionProcess")}</>
              ) : t("smartpath.executionResult")}
            </div>
            <div ref={executing ? streamRef : undefined}
              className="markdown-content prose prose-sm dark:prose-invert max-w-none break-words text-sm leading-relaxed max-h-80 overflow-y-auto whitespace-pre-wrap">
              {executing && !executionStream ? (
                <span className="text-muted-foreground animate-pulse">{t("smartpath.waitingResponse")}</span>
              ) : (
                <Streamdown mode='static' controls={{ code: true, table: true }}>
                  {executionStream || ''}
                </Streamdown>
              )}
            </div>
          </div>
        )}

        {stepping && stepResult && !executing && (
          <div className="mt-2 space-y-2">
            {step.deliveryCheck && stepDeliveryCheck && stepDeliveryCheck.passed === true && (
              <div className="flex items-center gap-1.5 px-2 py-1 rounded bg-green-500/10 border border-green-500/20 text-green-600 text-xs">
                <CheckCircle className="h-3.5 w-3.5" /> {t('smartpath.deliveryCheckPassed')}
              </div>
            )}
            {step.deliveryCheck && stepDeliveryCheck && stepDeliveryCheck.passed === false && (
              <div className="px-3 py-2 rounded bg-destructive/10 border border-destructive/30">
                <div className="flex items-center gap-1.5 text-destructive text-xs font-semibold">
                  <XCircle className="h-3.5 w-3.5" /> {t('smartpath.deliveryCheckFailed')}
                </div>
                {stepDeliveryCheck.reason && (
                  <p className="text-xs text-destructive/80 mt-1">{t('smartpath.deliveryCheckReason').replace('{reason}', stepDeliveryCheck.reason)}</p>
                )}
              </div>
            )}
            <div className="space-y-1">
              <Label className="text-xs text-muted-foreground">{t('smartpath.editDesc')}</Label>
              <Input
                value={stepDesc || step.userInput}
                onChange={(e) => onDescChange?.(e.target.value)}
                className="h-6 text-xs"
                placeholder={t('smartpath.stepDescPlaceholder')}
              />
            </div>
            <div className="space-y-1">
              <Label className="text-xs text-muted-foreground">{t('smartpath.editResult')}</Label>
              <Textarea
                value={stepResult}
                onChange={(e) => onResultChange?.(e.target.value)}
                className="min-h-[80px] text-xs resize-none"
                placeholder={t('smartpath.stepResultPlaceholder')}
              />
            </div>
            <StepControlBar
              stepIndex={index}
              isLastStep={index === total - 1}
              hasResult={!!stepResult}
              executing={executing || finalizing}
              onRedo={() => onRedo?.()}
              onContinue={() => onContinue?.()}
              onFinalize={() => onFinalize?.()}
              onGuide={pathId && workspace ? handleGuideStart : undefined}
            />

            {guideChatOpen && (
              <div className="rounded-md border border-primary/20 bg-primary/5">
                <div className="flex items-center gap-2 px-3 py-2 border-b border-primary/10">
                  <MessageSquare className="h-3.5 w-3.5 text-primary" />
                  <span className="text-xs font-medium text-primary">{t('smartpath.guideLabel')}</span>
                  <div className="flex-1" />
                  <Button variant="ghost" size="sm" className="h-6 text-xs" onClick={() => closeGuideChat(index)}>
                    {t('smartpath.guideChatCancel')}
                  </Button>
                </div>

                <div ref={guideChatRef} className="max-h-60 overflow-y-auto px-3 py-2 space-y-2">
                  {guideChatMessages.length === 0 && guideChatLoading && (
                    <div className="flex items-center gap-2 text-xs text-muted-foreground">
                      <Loader2 className="h-3 w-3 animate-spin" /> {t('smartpath.guideChatIntro')}
                    </div>
                  )}
                  {guideChatMessages.map((msg, i) => (
                    <div key={i} className={cn('text-sm leading-relaxed', msg.role === 'user' ? 'text-foreground' : 'text-muted-foreground')}>
                      <span className="text-xs font-medium mr-1">{msg.role === 'user' ? '👤' : '🤖'}</span>
                      <div className="markdown-content prose prose-sm dark:prose-invert max-w-none break-words inline whitespace-pre-wrap">
                        <Streamdown mode='static' controls={{ code: true, table: true }}>
                          {msg.content}
                        </Streamdown>
                      </div>
                    </div>
                  ))}
                  {guideChatLoading && guideChatStream && (
                    <div className="text-sm leading-relaxed text-muted-foreground">
                      <span className="text-xs font-medium mr-1">🤖</span>
                      <div className="markdown-content prose prose-sm dark:prose-invert max-w-none break-words inline whitespace-pre-wrap">
                        <Streamdown mode='static' controls={{ code: true, table: true }}>
                          {guideChatStream}
                        </Streamdown>
                      </div>
                    </div>
                  )}
                </div>

                <div className="flex items-center gap-2 px-3 py-2 border-t border-primary/10">
                  <Input
                    value={guideInput}
                    onChange={(e) => setGuideInput(e.target.value)}
                    onKeyDown={handleGuideKeyDown}
                    placeholder={t('smartpath.guideChatPlaceholder')}
                    className="h-7 text-xs flex-1"
                    disabled={guideChatLoading}
                  />
                  <Button size="sm" className="h-7 text-xs gap-1" disabled={guideChatLoading || !guideInput.trim()} onClick={handleGuideSend}>
                    <Send className="h-3 w-3" /> {t('smartpath.guideChatSend')}
                  </Button>
                  {!guideChatLoading && guideChatMessages.length > 0 && (
                    <Button size="sm" className="h-7 text-xs gap-1" onClick={handleGuideSave}>
                      <BookOpen className="h-3 w-3" /> {t('smartpath.guideChatConfirm')}
                    </Button>
                  )}
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

// ── Create dialog ──

function CreateDialog({ open, onOpenChange, workspaceOptions, onSubmit }: {
  open: boolean; onOpenChange: (o: boolean) => void;
  workspaceOptions: { value: string; label: string }[];
  onSubmit: (name: string, description: string, workspace: string) => void;
}) {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [workspace, setWorkspace] = useState('');
  useEffect(() => { if (open) { setName(''); setDescription(''); setWorkspace(''); } }, [open]);
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader><DialogTitle>{t("smartpath.createPath")}</DialogTitle><DialogDescription>{t("smartpath.createPathDesc")}</DialogDescription></DialogHeader>
        <div className="space-y-4 py-2">
          <div className="space-y-2"><Label>{t("smartpath.name")}</Label><Input value={name} onChange={(e) => setName(e.target.value)} placeholder={t("smartpath.namePlaceholder")} /></div>
          <div className="space-y-2"><Label>{t("smartpath.description")}</Label><Textarea value={description} onChange={(e) => setDescription(e.target.value)} placeholder={t("smartpath.descriptionPlaceholder")} className="min-h-[80px] resize-none" /></div>
          <div className="space-y-2"><Label>{t("smartpath.workspace")}</Label>
            <Select value={workspace} onValueChange={setWorkspace}>
              <SelectTrigger><SelectValue placeholder={t("smartpath.workspacePlaceholder")} /></SelectTrigger>
              <SelectContent>{workspaceOptions.map((o) => (<SelectItem key={o.value} value={o.value}><div className="flex items-center gap-2"><FolderOpen className="h-3.5 w-3.5" /><span>{o.label}</span></div></SelectItem>))}</SelectContent>
            </Select>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>{t("common.cancel")}</Button>
          <Button onClick={() => { if (name.trim() && workspace) onSubmit(name.trim(), description.trim(), workspace); }} disabled={!name.trim() || !workspace}>{t("smartpath.create")}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ── Cron config ──

function CronConfig({ value, onChange, disabled }: { value: string; onChange: (v: string) => void; disabled?: boolean }) {
  return (
    <div className="flex items-center gap-2">
      <Clock className="h-4 w-4 text-muted-foreground shrink-0" />
      <Input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={t("smartpath.cronPlaceholder")}
        className="h-7 text-sm font-mono"
        disabled={disabled}
      />
      {value && (
        <span className="text-xs text-muted-foreground whitespace-nowrap">
          {describeCron(value)}
        </span>
      )}
    </div>
  );
}

function describeCron(expr: string): string {
  const parts = expr.trim().split(/\s+/);
  if (parts.length !== 5) return '';
  const [min, hour, dom, mon, dow] = parts;
  if (min === '0' && hour !== '*' && dow === '1-5') return t('smartpath.weekday').replace('${hour}', hour)
  if (min === '0' && hour !== '*' && dom === '*' && mon === '*' && dow === '*') return t('smartpath.daily').replace('${hour}', hour)
  if (min.startsWith('*/') && hour === '*' && dom === '*' && mon === '*' && dow === '*') return t('smartpath.everyMinutes').replace('${min}', min.slice(2))
  return expr;
}

// ── Report viewer ──

function ReportViewer({ content, onClose }: { content: string; onClose: () => void }) {
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <Label className="text-sm font-medium">{t("smartpath.executionReport")}</Label>
        <Button variant="ghost" size="sm" onClick={onClose}>{t("common.close")}</Button>
      </div>
      <Card>
        <CardContent className="p-4">
          <div className="markdown-content prose prose-sm dark:prose-invert max-w-none break-words text-sm leading-relaxed max-h-[500px] overflow-y-auto whitespace-pre-wrap">
            <Streamdown mode="static" controls={{ code: true, table: true }}>
              {content}
            </Streamdown>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

// ── Edit mode ──

function PathEditor({ path, onSave, onCancel }: {
  path: SmartPath;
  onSave: (name: string, description: string, steps: SmartPathStep[], pathId: string, workspace: string, cronExpression: string, defaultArgs: string) => Promise<void>;
  onCancel: () => void;
}) {
  const [name, setName] = useState(path.name);
  const [description, setDescription] = useState(path.description || '');
  const [steps, setSteps] = useState<SmartPathStep[]>(() => { try { return JSON.parse(path.steps); } catch { return []; } });
  const [cronExpression, setCronExpression] = useState(path.cronExpression || '');
  const [defaultArgs, setDefaultArgs] = useState(path.defaultArgs || '');
  const [saving, setSaving] = useState(false);

  const executeStep = useSmartPathStore((s) => s.executeStep);
  const stepExecutionStream = useSmartPathStore((s) => s.stepExecutionStream);
  const stepExecutionStatus = useSmartPathStore((s) => s.stepExecutionStatus);
  const clearStepExecutionState = useSmartPathStore((s) => s.clearStepExecutionState);

  const updateStep = useCallback((i: number, s: SmartPathStep) => setSteps((prev) => { const n = [...prev]; n[i] = s; return n; }), []);
  const removeStep = useCallback((i: number) => setSteps((prev) => prev.filter((_, idx) => idx !== i)), []);

  const handleExecute = useCallback(async (i: number) => {
    const s = steps[i]; if (!s?.userInput.trim()) return;
    try {
      await executeStep(path.id, path.workspace, i, s, steps.slice(0, i));
    } catch {}
  }, [steps, path, executeStep]);

  const handleSave = async () => {
    if (!name.trim() || steps.length === 0) return;
    setSaving(true);
    try {
      await onSave(name.trim(), description.trim(), steps, path.id, path.workspace, cronExpression.trim(), defaultArgs.trim());
    } finally { setSaving(false); }
  };

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      <div className="flex items-center gap-2">
        <Route className="h-5 w-5 text-primary" />
        <Input value={name} onChange={(e) => setName(e.target.value)}
          className="h-8 text-lg font-semibold border-0 p-0 shadow-none focus-visible:ring-0" />
      </div>
      <div className="space-y-2">
        <Label>{t("smartpath.description")}</Label>
        <Textarea value={description} onChange={(e) => setDescription(e.target.value)}
          placeholder={t("smartpath.descriptionPlaceholder")} className="min-h-[80px] resize-none" />
      </div>
      <div className="space-y-2">
        <Label className="text-sm font-medium">{t("smartpath.scheduledExec")}</Label>
        <CronConfig value={cronExpression} onChange={setCronExpression} />
        <p className="text-xs text-muted-foreground">{t("smartpath.cronHint")}</p>
      </div>
      <div className="space-y-2">
        <Label className="text-sm font-medium">{t("smartpath.parameters")}</Label>
        <Input
          value={defaultArgs}
          onChange={(e) => setDefaultArgs(e.target.value)}
          placeholder={t("smartpath.parametersPlaceholder")}
          className="h-7 text-sm"
        />
        <p className="text-xs text-muted-foreground">{t("smartpath.parametersHint")}</p>
      </div>
      <div className="space-y-0">
        <Label className="text-sm font-medium mb-3 block">{t("smartpath.stepLabel")}</Label>
        {steps.length === 0 && (
          <div className="text-center py-8 text-muted-foreground text-sm border rounded-lg border-dashed">{t("smartpath.noSteps")}</div>
        )}
        {steps.map((s, i) => (
          <StepEditCard key={i} step={s} index={i} total={steps.length}
            onChange={(v) => updateStep(i, v)} onDelete={() => removeStep(i)}
            onExecute={() => handleExecute(i)} executing={stepExecutionStatus[i] === 'running'}
            executionStream={stepExecutionStream[i] || ''}
            executionCompleted={stepExecutionStatus[i] === 'completed'}
            workspace={path.workspace} />
        ))}
        <div className="flex justify-center pt-5">
          <Button variant="outline" size="sm" onClick={() => setSteps((p) => [...p, { name: '', userInput: '' }])}>
            <Plus className="h-4 w-4 mr-1.5" /> {t("smartpath.addStep")}
          </Button>
        </div>
      </div>
      <div className="flex gap-2 pt-2">
        <Button onClick={handleSave} disabled={!name.trim() || steps.length === 0 || saving}>
          {saving ? <Loader2 className="h-4 w-4 mr-1.5 animate-spin" /> : <Save className="h-4 w-4 mr-1.5" />} {t("smartpath.savePath")}
        </Button>
        <Button variant="outline" onClick={onCancel}>{t("common.cancel")}</Button>
      </div>
    </div>
  );
}

// ── View mode ──

function PathDetail({ path, runs, reports, onEdit, onRun, onAbort, onDelete }: {
  path: SmartPath; runs: SmartPathRun[];
  reports: Array<{ fileName: string; createdAt: string }>;
  onEdit: () => void; onRun: (useRefs: boolean) => void; onAbort: () => void; onDelete: () => void;
}) {
  const steps = useMemo<SmartPathStep[]>(() => { try { return JSON.parse(path.steps); } catch { return []; } }, [path.steps]);
  const sc = STATUS_CONFIG[path.status];
  const stepExecutionStream = useSmartPathStore((s) => s.stepExecutionStream);
  const stepExecutionStatus = useSmartPathStore((s) => s.stepExecutionStatus);
  const running = useSmartPathStore((s) => s.running);
  const stepping = useSmartPathStore((s) => s.stepping);
  const finalizing = useSmartPathStore((s) => s.finalizing);
  const stepResults = useSmartPathStore((s) => s.stepResults);
  const anyStepExecuting = Object.values(stepExecutionStatus).some((s) => s === 'running');
  const stepDescriptions = useSmartPathStore((s) => s.stepDescriptions);
  const stepDeliveryChecks = useSmartPathStore((s) => s.stepDeliveryChecks);
  const startStepping = useSmartPathStore((s) => s.startStepping);
  const runStepContinue = useSmartPathStore((s) => s.runStepContinue);
  const runStepRedo = useSmartPathStore((s) => s.runStepRedo);
  const updateStepResult = useSmartPathStore((s) => s.updateStepResult);
  const updateStepDescription = useSmartPathStore((s) => s.updateStepDescription);
  const finalizeStepping = useSmartPathStore((s) => s.finalizeStepping);
  const cancelStepping = useSmartPathStore((s) => s.cancelStepping);
  const fetchReport = useSmartPathStore((s) => s.fetchReport);
  const currentReport = useSmartPathStore((s) => s.currentReport);
  const references = useSmartPathStore((s) => s.references);
  const currentReference = useSmartPathStore((s) => s.currentReference);
  const fetchReference = useSmartPathStore((s) => s.fetchReference);
  const [viewingReport, setViewingReport] = useState<string | null>(null);
  const [viewingRef, setViewingRef] = useState<string | null>(null);
  const [useRefs, setUseRefs] = useState(false);

  const handleViewReport = async (fileName: string) => {
    if (viewingReport === fileName) {
      setViewingReport(null);
      return;
    }
    setViewingReport(fileName);
    await fetchReport(path.id, path.workspace, fileName);
  };

  const handleViewRef = async (fileName: string) => {
    if (viewingRef === fileName) {
      setViewingRef(null);
      return;
    }
    setViewingRef(fileName);
    await fetchReference(path.id, path.workspace, fileName);
  };

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      <div className="flex items-start justify-between gap-3">
        <div className="space-y-1">
          <h2 className="text-lg font-semibold">{path.name}</h2>
          {path.description && <p className="text-sm text-muted-foreground">{path.description}</p>}
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <FolderOpen className="h-3.5 w-3.5" /><span>{path.workspace.split(/[/\\]/).pop()}</span>
            <Badge variant={sc?.variant || 'outline'} className="text-[10px]">{sc ? t(sc.labelKey) : path.status}</Badge>
          </div>
        </div>
        <div className="flex gap-1.5 shrink-0">
          <Button variant="outline" size="sm" onClick={onEdit} disabled={running || stepping}><Pencil className="h-3.5 w-3.5 mr-1" /> {t("smartpath.edit")}</Button>
          {!running && !stepping && (
            <Button
              variant="outline"
              size="sm"
              className={useRefs ? '' : 'text-muted-foreground/50'}
              onClick={() => setUseRefs(!useRefs)}
              title={t('smartpath.useReferencesHint')}
            >
              {useRefs ? <CheckCircle className="h-3.5 w-3.5 mr-1" /> : <Ban className="h-3.5 w-3.5 mr-1" />}
              {t('smartpath.useReferences')}
            </Button>
          )}
          {stepping ? (
            <Button variant="outline" size="sm" disabled={anyStepExecuting} onClick={() => cancelStepping(path.id)}>
              {t('smartpath.cancelStepExec')}
            </Button>
          ) : !running && (
            <Button variant="outline" size="sm" onClick={async () => {
              await startStepping(path.id, path.workspace, path.defaultArgs, useRefs);
              runStepContinue(path.id, path.workspace, path.defaultArgs, useRefs).catch(() => {});
            }}>
              <Play className="h-3.5 w-3.5 mr-1" /> {t('smartpath.stepExec')}
            </Button>
          )}
          {running ? (
            <Button variant="destructive" size="sm" onClick={onAbort}>
              <Square className="h-3.5 w-3.5 mr-1" /> {t("smartpath.stop")}
            </Button>
          ) : !stepping && (
            <Button size="sm" onClick={() => onRun(useRefs)}>
              <Play className="h-3.5 w-3.5 mr-1" /> {t("smartpath.execute")}
            </Button>
          )}
          <Button variant="ghost" size="icon" className="h-8 w-8" onClick={onDelete} disabled={running || stepping}><Trash2 className="h-4 w-4 text-muted-foreground" /></Button>
        </div>
      </div>

      {path.defaultArgs && (
        <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          <span className="text-xs font-medium text-foreground">{t("smartpath.parameters")}:</span>
          <span className="text-xs break-all">{path.defaultArgs}</span>
        </div>
      )}

      {path.cronExpression && (
        <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          <Clock className="h-4 w-4 shrink-0" />
          <span className="break-all">{t("smartpath.scheduledLabel")}{path.cronExpression}</span>
          <span className="text-xs">({describeCron(path.cronExpression)})</span>
        </div>
      )}

      <div className="space-y-0">
        <Label className="text-sm font-medium mb-3 block">{t("smartpath.stepLabel")}</Label>
        {(running || stepping) && (stepExecutionStatus[-1] === 'running' || stepExecutionStream[-1]) && (
          <div className="px-3 py-2 border rounded-md bg-primary/5 border-primary/20 mb-2 text-sm">
            <div className="flex items-center gap-2 mb-1">
              {stepExecutionStatus[-1] === 'running' ? (
                <><Loader2 className="h-3.5 w-3.5 animate-spin text-primary" />
                <span className="text-primary">{stepping ? t('smartpath.orchestrating') : t('smartpath.analyzing')}</span></>
              ) : (
                <span className="text-primary">{t('smartpath.stepCompleted')}</span>
              )}
            </div>
            {stepExecutionStream[-1] && (
              <div className="markdown-content prose prose-sm dark:prose-invert max-w-none break-words text-sm leading-relaxed max-h-60 overflow-y-auto whitespace-pre-wrap">
                <Streamdown mode='static' controls={{ code: true, table: true }}>
                  {stepExecutionStream[-1]}
                </Streamdown>
              </div>
            )}
          </div>
        )}
        {steps.length === 0 ? <div className="text-sm text-muted-foreground py-4">{t("smartpath.noStepsView")}</div>
          : <div className="space-y-0">{steps.map((s, i) => {
            const isStepCompleted = stepping && i < stepResults.length;
            const isCurrentStep = stepping && i === stepResults.length;
            return (
              <StepViewCard key={i} step={s} index={i} total={steps.length}
                executionStream={stepExecutionStream[i] || ''}
                executing={stepExecutionStatus[i] === 'running'}
                stepping={stepping && (isStepCompleted || isCurrentStep)}
                stepResult={stepping ? stepResults[i] : undefined}
                stepDesc={stepping ? stepDescriptions[i] : undefined}
                stepDeliveryCheck={stepping ? stepDeliveryChecks[i] : undefined}
                finalizing={stepping && finalizing}
                onResultChange={stepping ? (v) => updateStepResult(i, v) : undefined}
                onDescChange={stepping ? (v) => updateStepDescription(i, v) : undefined}
                onRedo={stepping ? () => runStepRedo(path.id, path.workspace, i, path.defaultArgs) : undefined}
                onContinue={stepping ? () => runStepContinue(path.id, path.workspace, path.defaultArgs) : undefined}
                onFinalize={stepping ? () => finalizeStepping(path.id, path.workspace) : undefined}
                pathId={path.id}
                workspace={path.workspace}
              />
            );
          })}</div>}
      </div>

      {/* Reusable resources */}
      {references.length > 0 && (
        <div className="space-y-2">
          <Label className="text-sm font-medium">{t("smartpath.reusableResources")}</Label>
          <div className="space-y-1.5">
            {references.map((ref) => (
              <div key={ref.fileName}>
                <button
                  onClick={() => handleViewRef(ref.fileName)}
                  className={cn(
                    'flex items-center justify-between w-full rounded-md px-3 py-2 border text-sm text-left transition-colors',
                    viewingRef === ref.fileName ? 'bg-primary/5 border-primary/30' : 'hover:bg-muted',
                  )}
                >
                  <div className="flex items-center gap-2">
                    {ref.fileName.endsWith('.md') ? <FileText className="h-4 w-4 text-muted-foreground" />
                      : <FileCode className="h-4 w-4 text-muted-foreground" />}
                    <span>{ref.fileName}</span>
                  </div>
                  <span className="text-xs text-muted-foreground">{new Date(ref.updatedAt).toLocaleString()}</span>
                </button>
                {viewingRef === ref.fileName && currentReference && (
                  <div className="mt-1.5">
                    <Card>
                      <CardContent className="p-4">
                        <div className="flex items-center justify-between mb-2">
                          <span className="text-sm font-medium">{ref.fileName}</span>
                          <Button variant="ghost" size="sm" onClick={() => setViewingRef(null)}>{t("common.close")}</Button>
                        </div>
                        <div className="markdown-content prose prose-sm dark:prose-invert max-w-none break-words text-sm leading-relaxed max-h-[400px] overflow-y-auto whitespace-pre-wrap">
                          <Streamdown mode="static" controls={{ code: true, table: true }}>
                            {currentReference}
                          </Streamdown>
                        </div>
                      </CardContent>
                    </Card>
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Execution reports */}
      {reports.length > 0 && (
        <div className="space-y-2">
          <Label className="text-sm font-medium">{t("smartpath.executionReport")}</Label>
          <div className="space-y-1.5">
            {reports.map((r) => (
              <div key={r.fileName}>
                <button
                  onClick={() => handleViewReport(r.fileName)}
                  className={cn(
                    'flex items-center justify-between w-full rounded-md px-3 py-2 border text-sm text-left transition-colors',
                    viewingReport === r.fileName ? 'bg-primary/5 border-primary/30' : 'hover:bg-muted',
                  )}
                >
                  <div className="flex items-center gap-2">
                    <FileText className="h-4 w-4 text-muted-foreground" />
                    <span className="text-muted-foreground">{new Date(r.createdAt).toLocaleString()}</span>
                  </div>
                </button>
                {viewingReport === r.fileName && currentReport && (
                  <div className="mt-1.5">
                    <ReportViewer content={currentReport} onClose={() => setViewingReport(null)} />
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Execution history (legacy) */}
      {runs.length > 0 && reports.length === 0 && (
        <div className="space-y-2">
          <Label className="text-sm font-medium">{t("smartpath.executionHistory")}</Label>
          <div className="space-y-1.5">
            {runs.map((r) => { const rs = STATUS_CONFIG[r.status]; return (
              <div key={r.id} className="flex items-center justify-between rounded-md px-3 py-2 border text-sm">
                <div className="flex items-center gap-2">
                  {r.status === 'completed' ? <CheckCircle className="h-4 w-4 text-green-500" />
                    : r.status === 'failed' ? <XCircle className="h-4 w-4 text-red-500" />
                    : <Loader2 className="h-4 w-4 animate-spin text-blue-500" />}
                  <span className="text-muted-foreground">{new Date(r.startedAt).toLocaleString()}</span>
                </div>
                <Badge variant={rs?.variant || 'outline'} className="text-[10px]">{rs ? t(rs.labelKey) : r.status}</Badge>
              </div>); })}
          </div>
        </div>
      )}
    </div>
  );
}

// ── Empty state ──

function EmptyState({ onNew }: { onNew: () => void }) {
  return (
    <div className="flex items-center justify-center h-full">
      <div className="text-center space-y-3 max-w-xs">
        <div className="mx-auto w-12 h-12 rounded-full bg-muted flex items-center justify-center"><Route className="h-6 w-6 text-muted-foreground" /></div>
        <p className="text-sm text-muted-foreground">{t("smartpath.emptyState")}</p>
        <Button variant="outline" size="sm" onClick={onNew}><Plus className="h-4 w-4 mr-1.5" /> {t("smartpath.newPath")}</Button>
      </div>
    </div>
  );
}

// ── Main page ──

export function SmartPathPage() {
  const navigate = useNavigate();
  const { status: wsStatus } = useWsConnection();
  const paths = useSmartPathStore((s) => s.paths);
  const runs = useSmartPathStore((s) => s.runs);
  const reports = useSmartPathStore((s) => s.reports);
  const currentPath = useSmartPathStore((s) => s.currentPath);
  const loading = useSmartPathStore((s) => s.loading);
  const error = useSmartPathStore((s) => s.error);
  const fetchPaths = useSmartPathStore((s) => s.fetchPaths);
  const createPath = useSmartPathStore((s) => s.createPath);
  const updatePath = useSmartPathStore((s) => s.updatePath);
  const deletePath = useSmartPathStore((s) => s.deletePath);
  const runPath = useSmartPathStore((s) => s.runPath);
  const abortPath = useSmartPathStore((s) => s.abortPath);
  const fetchRuns = useSmartPathStore((s) => s.fetchRuns);
  const fetchReferences = useSmartPathStore((s) => s.fetchReferences);
  const setCurrentPath = useSmartPathStore((s) => s.setCurrentPath);
  const clearError = useSmartPathStore((s) => s.clearError);
  const clearStepExecutionState = useSmartPathStore((s) => s.clearStepExecutionState);
  const workspaces = useCronStore((s) => s.workspaces);
  const fetchWorkspaces = useCronStore((s) => s.fetchWorkspaces);

  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState(false);

  useEffect(() => {
    if (wsStatus !== 'connected') return;
    fetchWorkspaces().then(() => {
      const ws = useCronStore.getState().workspaces;
      if (ws.length > 0) fetchPaths(ws);
    });
  }, [wsStatus, fetchPaths, fetchWorkspaces]);

  useEffect(() => {
    if (currentPath) {
      fetchRuns(currentPath.id, currentPath.workspace);
      fetchReferences(currentPath.id, currentPath.workspace);
    }
  }, [currentPath?.id]);

  useEffect(() => { setEditing(false); }, [currentPath?.id]);

  const workspaceOptions = useMemo(() => workspaces.map((ws) => ({ value: ws, label: ws.split(/[/\\]/).pop() || ws })), [workspaces]);

  const handleCreate = async (name: string, description: string, workspace: string) => {
    setCreateOpen(false);
    const p = await createPath({ name, description, workspace, steps: '[]' });
    setCurrentPath(p);
    setEditing(true);
  };

  const handleSave = async (name: string, description: string, steps: SmartPathStep[], pathId: string, workspace: string, cronExpression: string, defaultArgs: string) => {
    await updatePath(pathId, workspace, { name, description, steps: JSON.stringify(steps), status: 'ready', cronExpression, defaultArgs });
    setEditing(false);
  };

  return (
    <div className="flex h-full">
      <PathList paths={paths} currentPath={currentPath}
        onSelect={(p) => setCurrentPath(p)} onNew={() => setCreateOpen(true)} onBack={() => navigate(-1)} />
      <div className="flex-1 overflow-y-auto">
        {error && (
          <div className="mx-6 mt-4 p-3 rounded-md bg-destructive/10 text-destructive text-sm flex items-center justify-between max-w-2xl lg:mx-auto">
            <span>{error}</span>
            <Button variant="ghost" size="sm" onClick={clearError}><XCircle className="h-4 w-4" /></Button>
          </div>
        )}
        {loading && <div className="flex items-center justify-center py-4"><Loader2 className="h-5 w-5 animate-spin text-muted-foreground" /></div>}
        <div className="p-6">
          {editing && currentPath ? (
            <PathEditor path={currentPath} onSave={handleSave} onCancel={() => { clearStepExecutionState(); setEditing(false); }} />
          ) : currentPath ? (
            <PathDetail path={currentPath} runs={runs} reports={reports}
              onEdit={() => setEditing(true)}
              onRun={(useRefs) => runPath(currentPath.id, currentPath.workspace, currentPath.defaultArgs, useRefs)}
              onAbort={() => abortPath(currentPath.id)}
              onDelete={() => { deletePath(currentPath.id, currentPath.workspace); setCurrentPath(null); }} />
          ) : (
            <EmptyState onNew={() => setCreateOpen(true)} />
          )}
        </div>
      </div>
      <CreateDialog open={createOpen} onOpenChange={setCreateOpen} workspaceOptions={workspaceOptions} onSubmit={handleCreate} />
    </div>
  );
}
