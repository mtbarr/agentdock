import { ClaudeChatUsage } from './ClaudeChatUsage';
import { CodexChatUsage } from './CodexChatUsage';
import { GeminiChatUsage } from './GeminiChatUsage';

interface ChatUsageIndicatorProps {
  agentId: string;
  modelId?: string;
}

export function ChatUsageIndicator({ agentId, modelId }: ChatUsageIndicatorProps) {
  switch (agentId) {
    case 'claude-code':
      return <ClaudeChatUsage />;
    case 'codex':
      return <CodexChatUsage />;
    case 'gemini-cli':
      return <GeminiChatUsage modelId={modelId} />;
    default:
      return null;
  }
}
