import { useState, useEffect } from 'react';
import { useWsConnection } from '@/stores/ws-connection';
import { t, useLocale } from '@/locales';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Check, X } from 'lucide-react';

interface WorkspaceOption {
  workspace: string;
  name: string;
}

interface CreateGroupDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onGroupCreated: (group: { id: string; name: string; workspaceIds: string[] }) => void;
  recentWorkspaces: WorkspaceOption[];
}

export function CreateGroupDialog({
  open,
  onOpenChange,
  onGroupCreated,
  recentWorkspaces,
}: CreateGroupDialogProps) {
  useLocale();
  const client = useWsConnection((s) => s.client);
  const [groupName, setGroupName] = useState('');
  const [selectedWorkspaces, setSelectedWorkspaces] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(false);

  // Reset form when dialog opens
  useEffect(() => {
    if (open) {
      setGroupName('');
      setSelectedWorkspaces(new Set());
    }
  }, [open]);

  const handleCreate = async () => {
    console.log('[CreateGroupDialog] handleCreate called', { groupName, selectedWorkspaces, client });

    if (!groupName.trim()) {
      alert(t('group.nameRequired'));
      return;
    }
    if (selectedWorkspaces.size === 0) {
      alert(t('group.selectAtLeastOne'));
      return;
    }
    if (!client) {
      alert(t('group.notConnected'));
      return;
    }

    console.log('[CreateGroupDialog] Validation passed, preparing to create group');
    setLoading(true);
    try {
      const groupId = `group-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
      const workspaceIds = Array.from(selectedWorkspaces);

      const payload = {
        type: 'group.create',
        groupId,
        name: groupName.trim(),
        workspaceIds,
      };
      console.log('[CreateGroupDialog] Sending group.create message:', payload);

      client.send(payload);
      console.log('[CreateGroupDialog] Message sent successfully');

      // Server broadcasts group.list after create, no need to request it again
      onGroupCreated({ id: groupId, name: groupName.trim(), workspaceIds });
      onOpenChange(false);
    } catch (err) {
      console.error('[CreateGroupDialog] Failed to create group:', err);
      alert(`${t('group.createFail')}: ${err instanceof Error ? err.message : String(err)}`);
    } finally {
      setLoading(false);
    }
  };

  const toggleWorkspace = (workspace: string) => {
    setSelectedWorkspaces((prev) => {
      const next = new Set(prev);
      if (next.has(workspace)) {
        next.delete(workspace);
      } else {
        next.add(workspace);
      }
      return next;
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[500px]">
        <DialogHeader>
          <DialogTitle>{t('group.createTitle')}</DialogTitle>
          <DialogDescription>{t('group.createDescription')}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4 py-4">
          {/* Group name */}
          <div className="space-y-2">
            <Label htmlFor="group-name">{t('group.nameLabel')}</Label>
            <Input
              id="group-name"
              value={groupName}
              onChange={(e) => setGroupName(e.target.value)}
              placeholder={t('group.namePlaceholder')}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  handleCreate();
                }
              }}
            />
          </div>

          {/* Workspace selection */}
          <div className="space-y-2">
            <Label>{t('group.selectWorkspaces')}</Label>
            <div className="border rounded-lg p-3 bg-muted/20">
              <ScrollArea className="h-[200px] pr-4">
                <div className="space-y-1">
                  {recentWorkspaces.length === 0 ? (
                    <div className="text-sm text-muted-foreground text-center py-4">
                      {t('group.noWorkspaces')}
                    </div>
                  ) : (
                    recentWorkspaces.map((ws) => {
                      const isSelected = selectedWorkspaces.has(ws.workspace);
                      return (
                        <div
                          key={ws.workspace}
                          className={`
                            flex items-center justify-between p-2 rounded-md cursor-pointer
                            transition-all duration-200
                            ${isSelected ? 'bg-primary text-primary-foreground' : 'hover:bg-muted'}
                          `}
                          onClick={() => toggleWorkspace(ws.workspace)}
                        >
                          <span className="text-sm truncate flex-1" title={ws.workspace}>
                            {ws.name}
                          </span>
                          {isSelected ? <Check className="h-4 w-4 shrink-0" /> : <X className="h-4 w-4 shrink-0 opacity-30" />}
                        </div>
                      );
                    })
                  )}
                </div>
              </ScrollArea>
            </div>
            <div className="text-xs text-muted-foreground">
              {t('group.selectedCount', { count: String(selectedWorkspaces.size) })}
            </div>
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {t('common.cancel')}
          </Button>
          <Button onClick={handleCreate} disabled={loading}>
            {loading ? t('common.creating') : t('common.confirm')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
