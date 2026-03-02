import { useEffect, useCallback } from 'react';
import { LexicalComposer } from '@lexical/react/LexicalComposer';
import { RichTextPlugin } from '@lexical/react/LexicalRichTextPlugin';
import { ContentEditable } from '@lexical/react/LexicalContentEditable';
import { HistoryPlugin } from '@lexical/react/LexicalHistoryPlugin';
import { OnChangePlugin } from '@lexical/react/LexicalOnChangePlugin';
import { useLexicalComposerContext } from '@lexical/react/LexicalComposerContext';
import { $getRoot, COMMAND_PRIORITY_CRITICAL, KEY_ENTER_COMMAND, SELECTION_CHANGE_COMMAND } from 'lexical';
import { LexicalErrorBoundary } from '@lexical/react/LexicalErrorBoundary';

import ChatDropdown from './ChatDropdown';
import { DropdownOption } from '../../types/chat';

interface ChatInputProps {
  inputValue: string;
  onInputChange: (val: string) => void;
  onSend: () => void;
  onStop: () => void;
  isSending: boolean;
  
  agentOptions: DropdownOption[];
  selectedAgentId: string;
  onAgentChange: (id: string) => void;

  modelOptions: DropdownOption[];
  selectedModelId: string;
  onModelChange: (id: string) => void;

  modeOptions: DropdownOption[];
  selectedModeId: string;
  onModeChange: (id: string) => void;

  hasSelectedAgent: boolean;
  
  attachments: { id: string; data: string; mimeType: string }[];
  onAttachmentsChange: (items: { id: string; data: string; mimeType: string }[]) => void;
  onImageClick: (src: string) => void;
}

// Plugin to handle image pasting
function PasteLogPlugin({ onImagePaste }: { onImagePaste: (file: File) => void }) {
  const [editor] = useLexicalComposerContext();

  useEffect(() => {
    return editor.registerCommand(
      SELECTION_CHANGE_COMMAND,
      () => {
        return false;
      },
      COMMAND_PRIORITY_CRITICAL
    );
  }, [editor]);

  const handlePaste = useCallback((e: ClipboardEvent) => {
    const items = e.clipboardData?.items;
    if (!items) return;

    for (let i = 0; i < items.length; i++) {
      if (items[i].type.indexOf('image') !== -1) {
        const file = items[i].getAsFile();
        if (file) {
          onImagePaste(file);
        }
      }
    }
  }, [onImagePaste]);

  useEffect(() => {
    const rootElement = editor.getRootElement();
    if (rootElement) {
      rootElement.addEventListener('paste', handlePaste);
      return () => rootElement.removeEventListener('paste', handlePaste);
    }
  }, [editor, handlePaste]);

  return null;
}

// Plugin to handle Enter key (Send) vs Shift+Enter (Newline)
function KeyboardPlugin({ onSend }: { onSend: () => void }) {
  const [editor] = useLexicalComposerContext();

  useEffect(() => {
    return editor.registerCommand(
      KEY_ENTER_COMMAND,
      (event: KeyboardEvent) => {
        if (!event.shiftKey) {
          event.preventDefault();
          onSend();
          return true;
        }
        return false;
      },
      COMMAND_PRIORITY_CRITICAL
    );
  }, [editor, onSend]);

  return null;
}

export default function ChatInput({
  inputValue,
  onInputChange,
  onSend,
  onStop,
  isSending,
  agentOptions,
  selectedAgentId,
  onAgentChange,
  modelOptions,
  selectedModelId,
  onModelChange,
  modeOptions,
  selectedModeId,
  onModeChange,
  hasSelectedAgent,
  attachments,
  onAttachmentsChange,
  onImageClick
}: ChatInputProps) {

  const handleImagePaste = (file: File) => {
    const reader = new FileReader();
    reader.onload = (event) => {
      const base64 = (event.target?.result as string).split(',')[1];
      const id = Math.random().toString(36).substring(2, 9);
      const newAtt = { id, data: base64, mimeType: file.type };
      onAttachmentsChange([...attachments, newAtt]);
      
      const placeholder = `[image-${id}]`;
      onInputChange(inputValue + (inputValue ? ' ' : '') + placeholder);
    };
    reader.readAsDataURL(file);
  };

  const removeAttachment = (id: string) => {
    onAttachmentsChange(attachments.filter(a => a.id !== id));
    onInputChange(inputValue.replace(`[image-${id}]`, '').trim());
  };

  const initialConfig = {
    namespace: 'ChatInput',
    theme: {
      paragraph: 'm-0',
      text: {
        base: 'text-foreground',
      },
    },
    onError: (error: Error) => {
      console.error(error);
    },
  };

  return (
    <div className="flex-shrink-0 px-4 pb-4 pt-2">
      <div className="max-w-4xl mx-auto">
        <div className="bg-background-secondary rounded-ide border border-border shadow-2xl focus-within:ring-1 focus-within:ring-accent/50 transition-all flex flex-col overflow-hidden">
          
          {/* Attachments Preview */}
          {attachments.length > 0 && (
            <div className="flex flex-wrap gap-2 p-3 border-b border-border bg-black/5">
              {attachments.map(att => (
                <div key={att.id} className="relative group w-14 h-14 rounded border border-border overflow-hidden bg-background shadow-sm">
                  <img 
                    src={`data:${att.mimeType};base64,${att.data}`} 
                    className="w-full h-full object-cover cursor-zoom-in hover:opacity-90 transition-opacity" 
                    onClick={() => onImageClick(`data:${att.mimeType};base64,${att.data}`)}
                  />
                  <button 
                    onClick={() => removeAttachment(att.id)}
                    className="absolute top-0 right-0 p-0.5 bg-error text-white opacity-0 group-hover:opacity-100 transition-opacity"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
                  </button>
                </div>
              ))}
            </div>
          )}

          {/* Lexical Editor */}
          <div className="relative min-h-[40px] max-h-[300px] overflow-y-auto">
            <LexicalComposer initialConfig={initialConfig}>
              <RichTextPlugin
                contentEditable={
                  <ContentEditable 
                    className="outline-none p-3 text-ide-regular text-foreground placeholder:text-foreground/30 min-h-[40px]" 
                  />
                }
                placeholder={
                  <div className="absolute top-3 left-3 text-foreground/30 pointer-events-none text-ide-regular">
                    Type your task here (Paste images with Ctrl+V)
                  </div>
                }
                ErrorBoundary={LexicalErrorBoundary}
              />
              <HistoryPlugin />
              <OnChangePlugin onChange={(editorState) => {
                editorState.read(() => {
                  const root = $getRoot();
                  const text = root.getTextContent();
                  if (text !== inputValue) {
                    onInputChange(text);
                  }
                });
              }} />
              <PasteLogPlugin onImagePaste={handleImagePaste} />
              <KeyboardPlugin onSend={onSend} />
            </LexicalComposer>
          </div>

          {/* Bottom Bar Controls - Inspired by Screenshot 2 */}
          <div className="flex items-center justify-between px-3 py-1.5 border-t border-border bg-background-secondary/50">
            {/* Left side: Add and Dropdowns */}
            <div className="flex items-center gap-1">
              <button
                className="p-1.5 rounded text-foreground/60 hover:text-foreground hover:bg-black/5 transition-all"
                title="Add context"
              >
                <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
              </button>

              <div className="h-4 w-[1px] bg-border mx-1" />

              <ChatDropdown
                value={selectedAgentId}
                options={agentOptions}
                placeholder="Select Agent"
                disabled={isSending}
                onChange={onAgentChange}
              />

              {modeOptions.length > 0 && (
                <ChatDropdown
                  value={selectedModeId}
                  options={modeOptions}
                  placeholder="Mode"
                  minWidthClass="min-w-[80px]"
                  disabled={isSending || !hasSelectedAgent}
                  onChange={onModeChange}
                />
              )}
            </div>

            {/* Right side: Model and Action */}
            <div className="flex items-center gap-2">
              <ChatDropdown
                value={selectedModelId}
                options={modelOptions}
                placeholder="Select Model"
                header="Model"
                disabled={isSending || !hasSelectedAgent}
                direction="up"
                onChange={onModelChange}
              />

              <div className="flex items-center ml-1">
                {isSending ? (
                  <button
                    type="button"
                    onClick={onStop}
                    className="p-1.5 text-error hover:bg-error/10 rounded transition-all"
                    title="Stop generating"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
                    </svg>
                  </button>
                ) : (
                  <button
                    type="button"
                    onClick={onSend}
                    disabled={!inputValue.trim()}
                    className="p-1.5 text-foreground/60 hover:text-accent hover:bg-accent/10 rounded transition-all disabled:opacity-20 translate-y-[1px]"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <line x1="22" y1="2" x2="11" y2="13"></line>
                      <polygon points="22 2 15 22 11 13 2 9 22 2"></polygon>
                    </svg>
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
