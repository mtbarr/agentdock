import { SlashMenuLayout } from './slashCommands';

export interface MenuItem {
  primary: string;
  secondary?: string;
}

interface PopupMenuProps {
  items: MenuItem[];
  highlightedIndex: number;
  layout: SlashMenuLayout;
  menuRef: React.RefObject<HTMLDivElement>;
  onHover: (index: number) => void;
  onSelect: (index: number) => void;
}

export default function PopupMenu({
  items,
  highlightedIndex,
  layout,
  menuRef,
  onHover,
  onSelect,
}: PopupMenuProps) {
  return (
    <div
      ref={menuRef}
      className="absolute inset-x-3 bottom-full z-[140] mb-2 overflow-y-auto rounded-md border border-border bg-background-secondary py-1 shadow-2xl"
      style={{
        width: `${layout.width}px`,
        maxHeight: `${layout.maxHeight}px`,
      }}
    >
      {items.map((item, index) => {
        const isSelected = index === highlightedIndex;
        return (
          <button
            key={index}
            data-command-index={index}
            type="button"
            onMouseEnter={() => onHover(index)}
            onMouseDown={(event) => {
              event.preventDefault();
              onSelect(index);
            }}
            className={`flex w-full items-center gap-3 px-3 py-1.5 text-left text-ide-regular transition-colors ${
              isSelected ? 'bg-accent text-accent-foreground' : 'text-foreground hover:bg-accent hover:text-accent-foreground'
            }`}
          >
            <span className="w-40 shrink-0 truncate font-mono text-[12px] leading-5">
              {item.primary}
            </span>
            {item.secondary && (
              <span className={`min-w-0 flex-1 truncate text-[12px] leading-5 ${isSelected ? 'text-accent-foreground/90' : 'text-foreground/70'}`}>
                {item.secondary}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
