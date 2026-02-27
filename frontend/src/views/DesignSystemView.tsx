import { } from 'react';

const ColorTile = ({ name, variable, tailwindName }: { name: string, variable: string, tailwindName?: string }) => (
  <div className="flex items-center gap-3 p-2 bg-[var(--ide-Panel-background)] border border-[var(--ide-Borders-color)] rounded-lg group hover:border-foreground/20 transition-colors">
    <div 
      className="w-10 h-10 rounded shadow-inner border border-[var(--ide-Borders-color)] flex-shrink-0" 
      style={{ backgroundColor: `var(${variable})` }}
    />
    <div className="flex flex-col min-w-0">
      <span className="text-[10px] font-mono opacity-40 uppercase truncate" title={name}>{name}</span>
      <span className="text-xs font-mono font-medium truncate select-all" title={variable}>{variable}</span>
      {tailwindName && (
        <span className="text-[9px] font-bold text-blue-500/70 uppercase tracking-tighter mt-0.5">.{tailwindName}</span>
      )}
    </div>
  </div>
);

export function DesignSystemView() {
  // These map directly to the --* variables in index.css (Screenshot mapping)
  const mappedColors = [
    { name: 'Background', var: '--background', tailwind: 'bg-background' },
    { name: 'Foreground', var: '--foreground', tailwind: 'text-foreground' },
    { name: 'Surface (Panel)', var: '--surface', tailwind: 'bg-surface' },
    { name: 'Surface Hover', var: '--surface-hover', tailwind: 'bg-surface-hover' },
    { name: 'Primary BG', var: '--primary', tailwind: 'bg-primary' },
    { name: 'Primary FG', var: '--primary-foreground', tailwind: 'text-primary-foreground' },
    { name: 'Primary Border', var: '--primary-border', tailwind: 'border-primary-border' },
    { name: 'Secondary BG', var: '--secondary', tailwind: 'bg-secondary' },
    { name: 'Secondary FG', var: '--secondary-foreground', tailwind: 'text-secondary-foreground' },
    { name: 'Secondary Border', var: '--secondary-border', tailwind: 'border-secondary-border' },
    { name: 'Accent BG', var: '--accent', tailwind: 'bg-accent' },
    { name: 'Accent FG', var: '--accent-foreground', tailwind: 'text-accent-foreground' },
    { name: 'Border', var: '--border', tailwind: 'border-border' },
    { name: 'Input BG', var: '--input', tailwind: 'bg-input' },
    { name: 'Ring/Focus', var: '--ring', tailwind: 'ring-ring' },
    { name: 'Error', var: '--error', tailwind: 'text-error' },
    { name: 'Warning', var: '--warning', tailwind: 'text-warning' },
    { name: 'Success', var: '--success', tailwind: 'text-success' },
    { name: 'Editor BG', var: '--editor-bg', tailwind: 'bg-editor-bg' },
    { name: 'Editor FG', var: '--editor-fg', tailwind: 'text-editor-fg' },
  ];

  const fullUiKeys = [
    'Panel-background', 'Panel-foreground',
    'Label-background', 'Label-foreground', 'Label-disabledForeground', 'Label-infoForeground',
    'Borders-color', 'Borders-ContrastBorderColor', 'Separator-separatorColor',
    'Button-default-startBackground', 'Button-default-endBackground', 'Button-default-foreground',
    'Button-default-borderColor', 'Button-default-focusColor',
    'Button-startBackground', 'Button-endBackground', 'Button-foreground', 'Button-borderColor',
    'TextField-background', 'TextField-foreground', 'TextField-borderColor',
    'List-background', 'List-foreground', 'List-selectionBackground', 'List-selectionForeground', 'List-hoverBackground',
    'Tree-background', 'Tree-selectionBackground', 'Tree-hoverBackground',
    'Toolbar-background', 'Toolbar-hoverBackground',
    'Notification-errorForeground', 'Notification-warningForeground',
    'ProgressBar-passedColor', 'ProgressBar-failedColor',
    'Tests-passedColor', 'Tests-failedColor', 'Tests-errorColor',
    'ScrollBar-trackColor', 'ScrollBar-thumbColor',
    'ToolTip-background', 'ToolTip-foreground'
  ];

  return (
    <div className="h-full overflow-y-auto bg-[var(--ide-bg)] text-[var(--ide-fg)] selection:bg-[var(--ide-List-selectionBackground)] selection:text-[var(--ide-List-selectionForeground)] animate-in fade-in duration-500">
      <div className="p-6">
        <header className="mb-8">
          <h1 className="text-2xl font-bold tracking-tight text-[var(--ide-Label-foreground)]">Design System 2.0</h1>
          <p className="opacity-70 mt-1">
            IntelliJ Theme Integration and Semantic Mapping.
          </p>
        </header>
        
        <section className="space-y-12">
          {/* Main Screenshot Semantic Mapping */}
          <div className="space-y-4">
            <h2 className="text-xs font-semibold uppercase tracking-widest opacity-40 flex items-center gap-2">
              <span className="w-8 h-[1px] bg-foreground/20"></span> Semantic Mapping (from Screenshot)
            </h2>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
              {mappedColors.map(c => (
                <ColorTile key={c.var} name={c.name} variable={c.var} tailwindName={c.tailwind} />
              ))}
            </div>
          </div>

          {/* Component Library Preview */}
          <div className="space-y-4">
            <h2 className="text-xs font-semibold uppercase tracking-widest opacity-40 flex items-center gap-2">
              <span className="w-8 h-[1px] bg-foreground/20"></span> Component Library Preview
            </h2>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <div className="p-4 bg-surface border border-border rounded shadow-sm">
                <h3 className="font-medium mb-3">Buttons & Actions</h3>
                <div className="flex flex-wrap gap-3">
                  <button className="px-4 py-1.5 bg-primary text-primary-foreground border border-primary-border rounded hover:opacity-90 transition-all active:scale-[0.95]">
                    Primary Action
                  </button>
                  <button className="px-4 py-1.5 bg-secondary text-secondary-foreground border border-secondary-border rounded hover:bg-surface-hover transition-all">
                    Secondary
                  </button>
                  <button className="px-4 py-1.5 text-error border border-error/30 rounded hover:bg-error/5 transition-all">
                    Dangerous
                  </button>
                </div>
              </div>
              
              <div className="p-4 bg-editor-bg border border-border rounded shadow-sm">
                <h3 className="font-medium mb-3 text-editor-fg">Editor Concept</h3>
                <div className="font-mono text-xs space-y-1.5 opacity-90">
                  <p><span className="text-[var(--ide-syntax-keyword)]">const</span> config = {'{'}</p>
                  <p className="ml-4"><span className="text-[var(--ide-syntax-attr)]">theme</span>: <span className="text-[var(--ide-syntax-string)]">"IntelliJ"</span>,</p>
                  <p className="ml-4"><span className="text-[var(--ide-syntax-attr)]">active</span>: <span className="text-[var(--ide-syntax-keyword)]">true</span></p>
                  <p>{'}'};</p>
                </div>
              </div>
            </div>
          </div>

          {/* Raw UI Keys (Full List) */}
          <div className="space-y-4">
            <h2 className="text-xs font-semibold uppercase tracking-widest opacity-40 flex items-center gap-2">
              <span className="w-8 h-[1px] bg-foreground/20"></span> All Available Raw UI Keys
            </h2>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
              {fullUiKeys.map(key => (
                <ColorTile key={key} name={key.replace(/-/g, '.')} variable={`--ide-${key}`} />
              ))}
            </div>
          </div>
        </section>

        <footer className="mt-16 pt-6 border-t border-border/20 flex justify-between items-center text-[10px] uppercase tracking-tighter opacity-30">
          <span>Unified LLM Design System</span>
          <span>Mapping: index.css</span>
        </footer>
      </div>
    </div>
  );
}
