
export function DesignSystemView() {
  return (
    <div className="p-6 min-h-screen bg-background text-foreground selection:bg-accent selection:text-accent-foreground animate-in fade-in duration-500">
      <header className="mb-8">
        <h1 className="text-2xl font-bold tracking-tight">Design System 2.0</h1>
        <p className="opacity-70 mt-1">
          IntelliJ Theme Integration using Tailwind CSS.
        </p>
      </header>
      
      <section className="space-y-8">
        {/* Buttons Section */}
        <div className="space-y-4">
          <h2 className="text-xs font-semibold uppercase tracking-widest opacity-40">Actions</h2>
          <div className="flex flex-wrap gap-4">
            <button className="px-4 py-1.5 bg-primary text-primary-foreground border border-primary-border rounded-ide hover:opacity-90 transition-all active:scale-[0.98] focus:ring-2 focus:ring-ring outline-none">
              Confirm & Push
            </button>
            <button className="px-4 py-1.5 bg-secondary text-secondary-foreground border border-secondary-border rounded-ide hover:bg-surface-hover transition-all active:scale-[0.98] outline-none">
              Cancel
            </button>
            <button className="px-4 py-1.5 text-error border border-error/30 rounded-ide hover:bg-error/5 transition-all outline-none">
              Delete Prompt
            </button>
          </div>
        </div>

        {/* Surfaces & Editor Section */}
        <div className="space-y-4">
          <h2 className="text-xs font-semibold uppercase tracking-widest opacity-40">Containers</h2>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="p-4 bg-surface border border-border rounded-ide shadow-sm">
              <h3 className="font-medium mb-3">Editor Preview</h3>
              <div className="p-4 bg-editor-bg text-editor-fg font-mono text-sm rounded border border-border/50 overflow-hidden">
                <div className="flex gap-4">
                  <span className="opacity-30 select-none">1</span>
                  <span><span className="text-[var(--ide-syntax-keyword)]">const</span> agent = <span className="text-[var(--ide-syntax-string)]">"Gemini"</span>;</span>
                </div>
                <div className="flex gap-4">
                  <span className="opacity-30 select-none">2</span>
                  <span><span className="text-[var(--ide-syntax-keyword)]">await</span> agent.initialize();</span>
                </div>
              </div>
            </div>
            
            <div className="p-4 bg-surface border border-border rounded-ide flex flex-col justify-center">
              <h3 className="font-medium mb-2">System Surface</h3>
              <p className="text-sm opacity-70 leading-relaxed">
                This component uses the standard IDE panel background and label colors. 
                It automatically adjusts when you switch between Light, Dark, or high-contrast themes.
              </p>
            </div>
          </div>
        </div>

        {/* Form Elements */}
        <div className="space-y-4">
          <h2 className="text-xs font-semibold uppercase tracking-widest opacity-40">Form Elements</h2>
          <div className="max-w-md space-y-4">
            <div className="space-y-1.5">
              <label className="text-xs font-medium opacity-60 ml-1">Agent Prompt</label>
              <input 
                type="text" 
                placeholder="Type your instructions..." 
                className="w-full px-3 py-2 bg-input border border-border rounded-ide focus:ring-2 focus:ring-ring outline-none transition-all placeholder:opacity-30"
              />
            </div>
            <div className="flex items-center gap-3 text-sm opacity-80">
              <input type="checkbox" className="w-4 h-4 rounded border-border accent-primary" id="auto-sync" />
              <label htmlFor="auto-sync" className="cursor-pointer">Enable automatic context sync</label>
            </div>
          </div>
        </div>
      </section>

      <footer className="mt-16 pt-6 border-t border-border/20 flex justify-between items-center text-[10px] uppercase tracking-tighter opacity-30">
        <span>Unified LLM Plugin v0.0.2</span>
        <span>Framework: JCEF / React / Tailwind</span>
      </footer>
    </div>
  );
}
