import { useCallback } from 'react';
import type { FileChangeSummary, Message } from '../../../types/chat';
import { ACPBridge } from '../../../utils/bridge';
import {
  buildConversationHandoffFromTranscriptFile,
  buildConversationHandoffSaveFailureContext,
  prepareConversationHandoff,
} from '../../../utils/conversationHandoff';

interface UseAgentHandoffRequestOptions {
  conversationId: string;
  selectedAgentId: string;
  messages: Message[];
  fileChanges: FileChangeSummary[];
  onAgentChangeRequest?: (payload: { agentId: string; handoffText: string }) => void;
}

export function useAgentHandoffRequest({
  conversationId,
  selectedAgentId,
  messages,
  fileChanges,
  onAgentChangeRequest,
}: UseAgentHandoffRequestOptions) {
  return useCallback(async (id: string) => {
    if (!onAgentChangeRequest || id === selectedAgentId) return;

    const prepared = prepareConversationHandoff(messages, fileChanges);
    let handoffText = prepared.handoffText;

    if (prepared.exceedsInlineLimit) {
      try {
        const saved = await ACPBridge.saveConversationTranscript(conversationId, prepared.normalizedTranscript);
        handoffText = buildConversationHandoffFromTranscriptFile(prepared, saved.filePath || '');
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        console.warn('[ChatSessionView] Failed to persist handoff transcript:', error);
        handoffText = buildConversationHandoffSaveFailureContext(prepared, message);
      }
    }

    onAgentChangeRequest({
      agentId: id,
      handoffText,
    });
  }, [conversationId, fileChanges, messages, onAgentChangeRequest, selectedAgentId]);
}
