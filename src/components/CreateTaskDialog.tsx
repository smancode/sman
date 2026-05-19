import { useState, useEffect } from 'react';
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
import { Textarea } from '@/components/ui/textarea';
import { useGroupStore } from '@/stores/group';

interface CreateTaskDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  groupId: string;
}

export function CreateTaskDialog({
  open,
  onOpenChange,
  groupId,
}: CreateTaskDialogProps) {
  useLocale();
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [autoDispatch, setAutoDispatch] = useState(false);

  useEffect(() => {
    if (open) {
      setTitle('');
      setDescription('');
      setAutoDispatch(false);
    }
  }, [open]);

  const handleCreate = async () => {
    if (!title.trim()) {
      alert(t('task.titleRequired'));
      return;
    }

    try {
      await useGroupStore.getState().createTask(groupId, title.trim(), description.trim() || undefined, autoDispatch);
      onOpenChange(false);
    } catch (err) {
      console.error('[CreateTaskDialog] Failed to create task:', err);
      alert(`${t('task.createFail')}: ${err instanceof Error ? err.message : String(err)}`);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-[480px]">
        <DialogHeader>
          <DialogTitle>{t('task.createTitle')}</DialogTitle>
          <DialogDescription>{t('task.createDescription')}</DialogDescription>
        </DialogHeader>

        <div className="space-y-4 py-4">
          <div className="space-y-2">
            <Label htmlFor="task-title">{t('task.titleLabel')}</Label>
            <Input
              id="task-title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder={t('task.titlePlaceholder')}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  handleCreate();
                }
              }}
            />
          </div>

          <div className="space-y-2">
            <Label htmlFor="task-description">{t('task.descriptionLabel')}</Label>
            <Textarea
              id="task-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder={t('task.descriptionPlaceholder')}
              rows={3}
            />
          </div>

          <label className="flex items-center gap-2 text-sm cursor-pointer">
            <input
              type="checkbox"
              checked={autoDispatch}
              onChange={(e) => setAutoDispatch(e.target.checked)}
              className="rounded border-muted-foreground/30"
            />
            <span>{t('task.autoDispatch')}</span>
          </label>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {t('common.cancel')}
          </Button>
          <Button onClick={handleCreate}>
            {t('common.confirm')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
