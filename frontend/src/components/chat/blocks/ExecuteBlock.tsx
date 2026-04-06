import React, { useMemo } from 'react';
import { ToolCallBlock } from '../../../types/chat';
import { ChevronRight, SquareTerminal } from 'lucide-react';
import { parseToolStatus } from '../../../utils/toolCallUtils';
import { useAutoCollapse } from '../../../hooks/useAutoCollapse';
import { MarkdownMessage } from '../MarkdownMessage';

const TerminalIcon = () => (
  <SquareTerminal size={16} className="text-primary" />
);

interface Props {
  block: ToolCallBlock;
  isActivePrompt?: boolean;
}

const ANSI_ESCAPE_PATTERN = /\u001b\[[0-9;?]*[ -/]*[@-~]/g;

function pickLastString(value: unknown): string | null {
  if (typeof value === 'string' && value.trim()) return value.trim();
  if (Array.isArray(value)) {
    for (let i = value.length - 1; i >= 0; i--) {
      const item = value[i];
      if (typeof item === 'string' && item.trim()) {
        return item.trim();
      }
    }
  }
  return null;
}

export const ExecuteBlock: React.FC<Props> = ({ block, isActivePrompt = false }) => {
  const { isPending, isError, isFinished } = parseToolStatus(block.entry.status);
  const showPending = isPending && isActivePrompt;
  const showFinished = isFinished || !showPending;
  const { isExpanded, toggle } = useAutoCollapse();
  const resultText = block.entry.result ? String(block.entry.result) : '';
  const sanitizedResultText = useMemo(
    () => resultText.replace(ANSI_ESCAPE_PATTERN, ''),
    [resultText]
  );
  const isFencedCodeAtStart = sanitizedResultText.startsWith('```');

  const command = useMemo(() => {
    try {
      const json = JSON.parse(block.entry.rawJson || '{}');
      const rawOutput = json.rawOutput || {};
      
      // Use rawOutput.parsed_cmd.cmd if available (check both array/object)
      const pCmd = Array.isArray(rawOutput.parsed_cmd) ? rawOutput.parsed_cmd[0] : rawOutput.parsed_cmd;
      const parsedCmd = pickLastString(pCmd?.cmd);
      if (parsedCmd) return parsedCmd;

      const rawInput = json.rawInput || {};
      const inputCommand = pickLastString(rawInput.command);
      if (inputCommand) return inputCommand;
    } catch (e) {
      // Ignore parse errors, fallback below
    }
    
    const fallback = block.entry.title || 'Terminal Command';
    return String(fallback).replace(/^`|`$/g, '');
  }, [block.entry.rawJson, block.entry.title, block.entry.kind]);

  return (
    <div className="my-2 border border-border rounded-md overflow-hidden shadow-sm">
      <button
        onClick={toggle}
        className="flex items-center gap-2 w-full px-3 py-2 bg-editor-bg "
      >
        <div className="flex-shrink-0 text-editor-fg opacity-70">
          <TerminalIcon />
        </div>
        <div className="flex-1 text-left font-mono truncate text-editor-fg opacity-90 pr-2">
          {command}
        </div>
        <div className="flex-shrink-0 flex items-center gap-2">
          {(showPending || isError) && (
            <div
              className={`w-2.5 h-2.5 rounded-full ${
                showPending ? 'bg-warning animate-pulse' : 'bg-error'
              }`}
            />
          )}
          <div className={`transition-transform duration-200 text-editor-fg opacity-50 ${isExpanded ? 'rotate-90' : ''}`}>
            <ChevronRight size={14} />
          </div>
        </div>
      </button>

      <div
        className="grid transition-[grid-template-rows] duration-300 ease-in-out overflow-hidden"
        style={{ gridTemplateRows: isExpanded ? '1fr' : '0fr' }}
      >
        <div className="overflow-hidden">
          <div className="p-3 bg-editor-bg max-h-[400px] overflow-y-auto scrollbar-thin scrollbar-thumb-border scrollbar-track-transparent">
            <div className="leading-relaxed text-editor-fg min-h-[0.5rem]">
              <div className="mb-1 text-editor-fg opacity-100 font-bold">
                <span className="opacity-50 mr-2 select-none font-mono">$</span>
                {command}
              </div>
              {block.entry.result ? (
                <div className="mt-2 opacity-90">
                  {isFencedCodeAtStart ? (
                    <MarkdownMessage content={sanitizedResultText} />
                  ) : (
                    <pre className="whitespace-pre-wrap break-words font-mono text-sm m-0">
                      {sanitizedResultText}
                    </pre>
                  )}
                </div>
              ) : !showFinished ? (
                <span className="opacity-40 italic">Executing...</span>
              ) : null}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
