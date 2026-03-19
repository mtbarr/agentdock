import {
  $createParagraphNode,
  $createTextNode,
  $getRoot,
  LexicalEditor,
} from 'lexical';
import { AvailableCommand } from '../../../types/chat';

export interface SlashMenuLayout {
  width: number;
  maxHeight: number;
}

export function extractSlashQuery(inputValue: string): string | null {
  const match = inputValue.match(/^\/([^\s]*)$/);
  return match ? match[1].toLowerCase() : null;
}

export function hasMatchingSlashCommand(commands: AvailableCommand[], slashQuery: string | null): boolean {
  if (slashQuery === null) return false;
  if (slashQuery.length === 0) return commands.length > 0;
  return commands.some((command) => command.name.toLowerCase().startsWith(slashQuery));
}

export function findHighlightedSlashCommandIndex(commands: AvailableCommand[], slashQuery: string | null): number {
  if (commands.length === 0) return 0;
  if (slashQuery === null || slashQuery.length === 0) return 0;
  const normalizedQuery = slashQuery.toLowerCase();
  const matchingIndex = commands.findIndex((command) => command.name.toLowerCase().startsWith(normalizedQuery));
  return matchingIndex >= 0 ? matchingIndex : 0;
}

export function calculateSlashMenuLayout(
  rootElement: HTMLDivElement,
  commandCount: number,
  viewportTopInset: number
): SlashMenuLayout {
  const rootRect = rootElement.getBoundingClientRect();
  const horizontalPadding = 12;
  const width = Math.max(320, rootRect.width - horizontalPadding * 2);
  const rowHeight = 34;
  const naturalHeight = Math.max(48, commandCount * rowHeight + 8);
  const gap = 8;
  const availableAbove = Math.max(120, Math.floor(rootRect.top - gap - viewportTopInset));
  const availableBelow = Math.max(120, Math.floor(window.innerHeight - rootRect.bottom - gap - 12));
  const preferAbove = availableAbove >= naturalHeight || availableAbove >= availableBelow;

  return {
    width,
    maxHeight: preferAbove ? availableAbove : availableBelow,
  };
}

export function computeViewportTopInset(rootElement: HTMLDivElement): number {
  const rootRect = rootElement.getBoundingClientRect();
  const elementsAtTop = document.elementsFromPoint(
    Math.max(16, rootRect.left + 16),
    16
  );
  const bottomEdge = elementsAtTop.reduce((max, element) => {
    const rect = element.getBoundingClientRect();
    return rect.bottom > 0 ? Math.max(max, rect.bottom) : max;
  }, 12);
  return Math.max(12, Math.floor(bottomEdge + 8));
}

export function applySlashCommandToEditor(
  editor: LexicalEditor | null,
  command: AvailableCommand,
  onInputChange: (value: string) => void
): string {
  const nextValue = `/${command.name} `;
  onInputChange(nextValue);

  if (editor) {
    editor.update(() => {
      const root = $getRoot();
      root.clear();
      const paragraph = $createParagraphNode();
      paragraph.append($createTextNode(nextValue));
      root.append(paragraph);
      paragraph.selectEnd();
    });
    editor.focus();
  }

  return nextValue;
}
