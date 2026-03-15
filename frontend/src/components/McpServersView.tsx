import { useEffect, useState } from 'react';
import { PlugZap, Plus, Trash2 } from 'lucide-react';
import { McpServerConfig, McpTransport } from '../types/mcp';
import { ACPBridge } from '../utils/bridge';

interface FormState {
  name: string;
  transport: McpTransport;
  command: string;
  args: string;      // one per line
  env: string;       // NAME=value per line
  url: string;
  headers: string;   // Name: value per line
}

const emptyForm = (): FormState => ({
  name: '', transport: 'stdio', command: '', args: '', env: '', url: '', headers: '',
});

function parseLines(raw: string): string[] {
  return raw.split('\n').map(s => s.trim()).filter(Boolean);
}

function parsePairs(raw: string, sep: string): { name: string; value: string }[] {
  return parseLines(raw).flatMap(line => {
    const idx = line.indexOf(sep);
    if (idx < 0) return [];
    return [{ name: line.slice(0, idx).trim(), value: line.slice(idx + sep.length).trim() }];
  });
}

function serverToForm(s: McpServerConfig): FormState {
  return {
    name: s.name, transport: s.transport,
    command: s.command ?? '',
    args: (s.args ?? []).join('\n'),
    env: (s.env ?? []).map(e => `${e.name}=${e.value}`).join('\n'),
    url: s.url ?? '',
    headers: (s.headers ?? []).map(h => `${h.name}: ${h.value}`).join('\n'),
  };
}

function formToServer(form: FormState, id: string): McpServerConfig {
  const base = { id, name: form.name.trim(), enabled: true, transport: form.transport };
  if (form.transport === 'stdio') {
    return { ...base, command: form.command.trim(), args: parseLines(form.args), env: parsePairs(form.env, '=') };
  }
  return { ...base, url: form.url.trim(), headers: parsePairs(form.headers, ':') };
}

function nextId(): string {
  return `mcp-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
}

const inputClass = 'bg-input border border-border rounded-ide px-2 py-1 text-foreground font-mono focus:outline-none focus:border-primary';
const labelClass = 'flex flex-col gap-1';
const labelTextClass = 'text-foreground/50 font-sans';

export function McpServersView() {
  const [servers, setServers] = useState<McpServerConfig[]>([]);
  const [form, setForm] = useState<FormState | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);

  useEffect(() => {
    const cleanup = ACPBridge.onMcpServers(e => setServers(e.detail.servers));
    ACPBridge.loadMcpServers();
    return cleanup;
  }, []);

  const save = (updated: McpServerConfig[]) => {
    setServers(updated);
    ACPBridge.saveMcpServers(updated);
  };

  const toggle = (id: string) =>
    save(servers.map(s => s.id === id ? { ...s, enabled: !s.enabled } : s));

  const remove = (id: string) => {
    save(servers.filter(s => s.id !== id));
    if (editingId === id) { setForm(null); setEditingId(null); }
  };

  const openAdd = () => { setForm(emptyForm()); setEditingId(null); };

  const openEdit = (s: McpServerConfig) => { setForm(serverToForm(s)); setEditingId(s.id); };

  const cancelForm = () => { setForm(null); setEditingId(null); };

  const submitForm = () => {
    if (!form || !form.name.trim()) return;
    if (editingId) {
      save(servers.map(s => s.id === editingId ? { ...formToServer(form, editingId), enabled: s.enabled } : s));
    } else {
      save([...servers, formToServer(form, nextId())]);
    }
    setForm(null);
    setEditingId(null);
  };

  return (
    <div className="h-full flex flex-col bg-background text-foreground text-ide-small">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-2.5 border-b border-border flex-shrink-0">
        <div className="flex items-center gap-2 text-foreground/80">
          <PlugZap size={14} />
          <span className="font-medium">MCP Servers</span>
        </div>
        <button
          onClick={openAdd}
          className="flex items-center gap-1 px-2 py-1 rounded-ide text-foreground/60 hover:text-foreground hover:bg-background-secondary transition-colors"
        >
          <Plus size={14} />
          <span>Add</span>
        </button>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto">
        {servers.length === 0 && !form && (
          <div className="flex flex-col items-center justify-center h-full gap-2 text-foreground/30">
            <PlugZap size={28} strokeWidth={1.5} />
            <span>No MCP servers configured</span>
          </div>
        )}

        {servers.map(s => (
          <div
            key={s.id}
            onClick={() => openEdit(s)}
            className={`flex items-center gap-3 px-4 py-2.5 border-b border-border cursor-pointer hover:bg-background-secondary transition-colors ${editingId === s.id ? 'bg-background-secondary' : ''}`}
          >
            <button
              role="switch"
              aria-checked={s.enabled}
              onClick={e => { e.stopPropagation(); toggle(s.id); }}
              className={`relative inline-flex h-4 w-7 flex-shrink-0 rounded-full border-2 border-transparent transition-colors ${s.enabled ? 'bg-primary' : 'bg-border'}`}
            >
              <span className={`pointer-events-none inline-block h-3 w-3 rounded-full bg-white shadow transition-transform ${s.enabled ? 'translate-x-3' : 'translate-x-0'}`} />
            </button>
            <span className={`flex-1 truncate ${s.enabled ? 'text-foreground' : 'text-foreground/40'}`}>{s.name}</span>
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-background text-foreground/50 border border-border uppercase tracking-wide">{s.transport}</span>
            <button
              onClick={e => { e.stopPropagation(); remove(s.id); }}
              className="p-1 rounded text-foreground/30 hover:text-error transition-colors"
            >
              <Trash2 size={13} />
            </button>
          </div>
        ))}

        {/* Form */}
        {form && (
          <div className="border-b border-border bg-background-secondary px-4 py-3 flex flex-col gap-3">
            <span className="text-foreground/60 font-medium">{editingId ? 'Edit server' : 'New server'}</span>

            <label className={labelClass}>
              <span className={labelTextClass}>Name</span>
              <input autoFocus value={form.name} onChange={e => setForm({ ...form, name: e.target.value })}
                placeholder="My MCP Server" className={inputClass} />
            </label>

            <label className={labelClass}>
              <span className={labelTextClass}>Transport</span>
              <select value={form.transport} onChange={e => setForm({ ...form, transport: e.target.value as McpTransport })}
                className={inputClass}>
                <option value="stdio">stdio</option>
                <option value="http">http</option>
                <option value="sse">sse</option>
              </select>
            </label>

            {form.transport === 'stdio' ? (
              <>
                <label className={labelClass}>
                  <span className={labelTextClass}>Command</span>
                  <input value={form.command} onChange={e => setForm({ ...form, command: e.target.value })}
                    placeholder="npx" className={inputClass} />
                </label>
                <label className={labelClass}>
                  <span className={labelTextClass}>Args <span className="text-foreground/30">(one per line)</span></span>
                  <textarea value={form.args} onChange={e => setForm({ ...form, args: e.target.value })}
                    placeholder={'-y\n@modelcontextprotocol/server-fetch'} rows={3}
                    className={`${inputClass} resize-none`} />
                </label>
                <label className={labelClass}>
                  <span className={labelTextClass}>Environment <span className="text-foreground/30">(NAME=value per line)</span></span>
                  <textarea value={form.env} onChange={e => setForm({ ...form, env: e.target.value })}
                    placeholder="API_KEY=your-key" rows={2} className={`${inputClass} resize-none`} />
                </label>
              </>
            ) : (
              <>
                <label className={labelClass}>
                  <span className={labelTextClass}>URL</span>
                  <input value={form.url} onChange={e => setForm({ ...form, url: e.target.value })}
                    placeholder="http://localhost:3000/mcp" className={inputClass} />
                </label>
                <label className={labelClass}>
                  <span className={labelTextClass}>Headers <span className="text-foreground/30">(Name: value per line)</span></span>
                  <textarea value={form.headers} onChange={e => setForm({ ...form, headers: e.target.value })}
                    placeholder="Authorization: Bearer token" rows={2} className={`${inputClass} resize-none`} />
                </label>
              </>
            )}

            <div className="flex gap-2">
              <button onClick={submitForm} disabled={!form.name.trim()}
                className="px-3 py-1 rounded-ide bg-primary text-primary-foreground border border-primary-border hover:opacity-90 transition-opacity disabled:opacity-40">
                Save
              </button>
              <button onClick={cancelForm}
                className="px-3 py-1 rounded-ide bg-secondary text-secondary-foreground border border-secondary-border hover:bg-accent hover:text-accent-foreground transition-colors">
                Cancel
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
