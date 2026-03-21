import { useEffect, useState } from 'react';
import { Bookmark, Plus, Trash2 } from 'lucide-react';
import { ACPBridge } from '../utils/bridge';
import { PromptLibraryItem } from '../types/promptLibrary';

interface FormState {
  name: string;
  prompt: string;
}

const inputClass = 'bg-input border border-border rounded-ide px-2 py-1 text-foreground font-mono focus:outline-none focus:border-primary';
const labelClass = 'flex flex-col gap-1';
const labelTextClass = 'text-foreground/50 font-sans';

function emptyForm(): FormState {
  return { name: '', prompt: '' };
}

function nextId(): string {
  return `saved-prompt-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
}

function formToPrompt(form: FormState, id: string): PromptLibraryItem {
  return {
    id,
    name: form.name.trim(),
    prompt: form.prompt.trim(),
  };
}

function promptToForm(prompt: PromptLibraryItem): FormState {
  return {
    name: prompt.name,
    prompt: prompt.prompt,
  };
}

export function PromptLibraryView() {
  const [prompts, setPrompts] = useState<PromptLibraryItem[]>([]);
  const [form, setForm] = useState<FormState | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);

  useEffect(() => {
    const cleanup = ACPBridge.onPromptLibrary((e) => setPrompts(e.detail.items));
    ACPBridge.loadPromptLibrary();
    return cleanup;
  }, []);

  const save = (updated: PromptLibraryItem[]) => {
    setPrompts(updated);
    ACPBridge.savePromptLibrary(updated);
  };

  const openAdd = () => {
    setForm(emptyForm());
    setEditingId(null);
  };

  const openEdit = (prompt: PromptLibraryItem) => {
    setForm(promptToForm(prompt));
    setEditingId(prompt.id);
  };

  const cancelForm = () => {
    setForm(null);
    setEditingId(null);
  };

  const submitForm = () => {
    if (!form) return;
    if (!form.name.trim() || !form.prompt.trim()) return;

    if (editingId) {
      save(prompts.map((prompt) => (
        prompt.id === editingId
          ? formToPrompt(form, editingId)
          : prompt
      )));
    } else {
      save([...prompts, formToPrompt(form, nextId())]);
    }

    cancelForm();
  };

  const remove = (id: string) => {
    save(prompts.filter((prompt) => prompt.id !== id));
    if (editingId === id) {
      cancelForm();
    }
  };

  return (
    <div className="h-full flex flex-col bg-background text-foreground text-ide-small">
      <div className="flex items-center justify-between px-4 py-2.5 border-b border-border flex-shrink-0">
        <div className="flex items-center gap-2 text-foreground/80">
          <Bookmark size={14} />
          <span className="font-medium">Prompt Library</span>
        </div>
        <button
          onClick={openAdd}
          className="flex items-center gap-1 px-2 py-1 rounded-ide text-foreground/60 hover:text-foreground hover:bg-background-secondary transition-colors"
        >
          <Plus size={14} />
          <span>Add</span>
        </button>
      </div>

      <div className="flex-1 overflow-y-auto">
        {prompts.length === 0 && !form && (
          <div className="flex flex-col items-center justify-center h-full gap-2 text-foreground/30">
            <Bookmark size={28} strokeWidth={1.5} />
            <span>Prompt library is empty</span>
          </div>
        )}

        {prompts.map((prompt) => (
          <div
            key={prompt.id}
            onClick={() => openEdit(prompt)}
            className={`flex items-start gap-3 px-4 py-2.5 border-b border-border cursor-pointer hover:bg-background-secondary transition-colors ${editingId === prompt.id ? 'bg-background-secondary' : ''}`}
          >
            <div className="mt-0.5 text-foreground/40">
              <Bookmark size={14} />
            </div>

            <div className="flex-1 min-w-0">
              <div className="truncate text-foreground">{prompt.name}</div>
              <div className="mt-1 text-xs text-foreground/45 whitespace-pre-wrap break-words max-h-16 overflow-hidden">
                {prompt.prompt}
              </div>
            </div>

            <button
              onClick={(event) => {
                event.stopPropagation();
                remove(prompt.id);
              }}
              className="p-1 rounded text-foreground/30 hover:text-error transition-colors"
            >
              <Trash2 size={13} />
            </button>
          </div>
        ))}

        {form && (
          <div className="border-b border-border bg-background-secondary px-4 py-3 flex flex-col gap-3">
            <span className="text-foreground/60 font-medium">{editingId ? 'Edit prompt' : 'New prompt'}</span>

            <label className={labelClass}>
              <span className={labelTextClass}>Name</span>
              <input
                autoFocus
                value={form.name}
                onChange={(event) => setForm({ ...form, name: event.target.value })}
                placeholder="review"
                className={inputClass}
              />
            </label>

            <label className={labelClass}>
              <span className={labelTextClass}>Prompt</span>
              <textarea
                value={form.prompt}
                onChange={(event) => setForm({ ...form, prompt: event.target.value })}
                placeholder="Review this code and list bugs first."
                rows={8}
                className={`${inputClass} resize-none`}
              />
            </label>

            <div className="flex gap-2">
              <button
                onClick={submitForm}
                disabled={!form.name.trim() || !form.prompt.trim()}
                className="px-3 py-1 rounded-ide bg-primary text-primary-foreground border border-primary-border hover:opacity-90 transition-opacity disabled:opacity-40"
              >
                Save
              </button>
              <button
                onClick={cancelForm}
                className="px-3 py-1 rounded-ide bg-secondary text-secondary-foreground border border-secondary-border hover:bg-accent hover:text-accent-foreground transition-colors"
              >
                Cancel
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
