import { useEffect, useCallback } from 'react';
import { useLexicalComposerContext } from '@lexical/react/LexicalComposerContext';
import {
  $getRoot,
  $nodesOfType,
  COMMAND_PRIORITY_CRITICAL,
  KEY_ENTER_COMMAND,
  LexicalEditor
} from 'lexical';
import { ImageNode } from './ImageNode';

export function AttachmentsSyncPlugin({ attachments, onAttachmentsChange }: { 
  attachments: { id: string; name: string; mimeType: string; data?: string; path?: string; isInline?: boolean }[], 
  onAttachmentsChange: (items: { id: string; name: string; mimeType: string; data?: string; path?: string; isInline?: boolean }[]) => void 
}) {
  const [editor] = useLexicalComposerContext();

  useEffect(() => {
    return editor.registerMutationListener(ImageNode, (mutations) => {
      let needsRefresh = false;
      for (const [_, mutation] of mutations) {
        if (mutation === 'destroyed') {
          needsRefresh = true;
          break;
        }
      }

      if (needsRefresh) {
        editor.read(() => {
          const existingIds = new Set($nodesOfType(ImageNode).map(n => n.__id));
          const filtered = attachments.filter(a => existingIds.has(a.id));
          if (filtered.length !== attachments.length) {
            onAttachmentsChange(filtered);
          }
        });
      }
    });
  }, [editor, attachments, onAttachmentsChange]);

  return null;
}

export function PasteLogPlugin({ onImagePaste }: { onImagePaste: (file: File, editor: LexicalEditor) => void }) {
  const [editor] = useLexicalComposerContext();

  const handlePaste = useCallback((e: ClipboardEvent) => {
    const items = e.clipboardData?.items;
    if (!items) return;

    for (let i = 0; i < items.length; i++) {
      if (items[i].type.indexOf('image') !== -1) {
        const file = items[i].getAsFile();
        if (file) {
          onImagePaste(file, editor);
        }
      }
    }
  }, [onImagePaste, editor]);

  useEffect(() => {
    const rootElement = editor.getRootElement();
    if (rootElement) {
      rootElement.addEventListener('paste', handlePaste);
      return () => rootElement.removeEventListener('paste', handlePaste);
    }
  }, [editor, handlePaste]);

  return null;
}

export function KeyboardPlugin({ onSend, sendMode }: { onSend: () => void, sendMode: 'enter' | 'ctrl-enter' }) {
  const [editor] = useLexicalComposerContext();

  useEffect(() => {
    return editor.registerCommand(
      KEY_ENTER_COMMAND,
      (event: KeyboardEvent) => {
        if (sendMode === 'enter') {
          if (!event.shiftKey && !event.ctrlKey && !event.metaKey) {
            event.preventDefault();
            onSend();
            return true;
          }
        } else {
          if (event.ctrlKey || event.metaKey) {
            event.preventDefault();
            onSend();
            return true;
          }
        }
        return false;
      },
      COMMAND_PRIORITY_CRITICAL
    );
  }, [editor, onSend, sendMode]);

  return null;
}

export function AutoHeightPlugin({ onHeightChange }: { onHeightChange: (height: number) => void }) {
  const [editor] = useLexicalComposerContext();

  useEffect(() => {
    const updateHeight = () => {
      const rootElement = editor.getRootElement();
      if (rootElement) {
        onHeightChange(rootElement.scrollHeight);
      }
    };
    
    updateHeight();
    return editor.registerUpdateListener(updateHeight);
  }, [editor, onHeightChange]);

  return null;
}

export function ClickToFocusPlugin({ containerRef }: { containerRef: React.RefObject<HTMLDivElement> }) {
  const [editor] = useLexicalComposerContext();

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    const handleClick = (e: MouseEvent) => {
      // Focus if clicking directly on the container or its padding area
      if (e.target === container) {
        editor.update(() => {
          $getRoot().selectEnd();
          editor.focus();
        });
      }
    };

    container.addEventListener('click', handleClick);
    return () => container.removeEventListener('click', handleClick);
  }, [editor, containerRef]);

  return null;
}

export function ClearEditorPlugin({ inputValue }: { inputValue: string }) {
  const [editor] = useLexicalComposerContext();

  useEffect(() => {
    if (inputValue === '') {
      editor.update(() => {
        const root = $getRoot();
        if (root.getTextContent() !== '' || root.getChildrenSize() > 1) {
          root.clear();
        }
      });
    }
  }, [inputValue, editor]);

  return null;
}
