import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import {
  Message,
  AgentOption,
  PermissionRequest,
  HistorySessionMeta,
  ChatAttachment,
  PendingHandoffContext
} from '../types/chat';
import { ACPBridge } from '../utils/bridge';
import { buildReplayMessages } from '../utils/replay';
import { lastAssistantMessageHasMeta } from './chatSession/messageProcessing';
import {
  nextMessageId,
  normalizeOutgoingBlocks,
  plainTextFromBlocks,
  prependHandoffContext,
  titleFromFirstPrompt,
} from './chatSession/messageBasics';
import { buildPromptBlocks } from './chatSession/promptBlocks';
import {
  PinnedAgentSnapshot,
  buildAgentOptions,
  buildModeOptions,
  resolveSelectedAgent,
  toPinnedAgentSnapshot,
} from './chatSession/agentSelection';
import { useAgentRuntimeOptions } from './chatSession/useAgentRuntimeOptions';
import { useAvailableCommands } from './chatSession/useAvailableCommands';
import { useBufferedMessageChunks } from './chatSession/useBufferedMessageChunks';

export function useChatSession(
  conversationId: string,
  availableAgents: AgentOption[],
  initialAgentId?: string,
  historySession?: HistorySessionMeta,
  pendingHandoff?: PendingHandoffContext,
  onHandoffConsumed?: (handoffId: string) => void
) {
  const [historyMessages, setHistoryMessages] = useState<Message[]>([]);
  const [liveMessages, setLiveMessages] = useState<Message[]>([]);
  const [inputValue, setInputValue] = useState('');
  const [status, setStatus] = useState<string>('not started');
  const [isSending, setIsSending] = useState(false);
  const [isHistoryReplaying, setIsHistoryReplaying] = useState(!!historySession);
  const [permissionQueue, setPermissionQueue] = useState<PermissionRequest[]>([]);
  const permissionRequest = permissionQueue[0] ?? null;
  const [attachments, setAttachments] = useState<ChatAttachment[]>([]);
  const [acpSessionId, setAcpSessionId] = useState<string>('');
  const messages = useMemo(() => [...historyMessages, ...liveMessages], [historyMessages, liveMessages]);
  const selectedAgentId = initialAgentId || '';

  const pendingPromptRef = useRef<any[] | null>(null);
  const pendingHandoffRef = useRef<PendingHandoffContext | null>(null);
  const consumedHandoffIdRef = useRef<string | null>(null);
  const resetSessionAfterInitialCancelRef = useRef(false);
  const startedAgentIdRef = useRef<string>('');
  const startedModelIdRef = useRef<string>('');
  const startedModeIdRef = useRef<string>('');
  const historyLoadRequestedRef = useRef<string | null>(null);
  const statusRef = useRef<string>('not started');
  const startTimeRef = useRef<number | null>(null);
  const historyLoadTimerRef = useRef<number | null>(null);
  const lastMetadataFingerprintRef = useRef<string>('');
  const allowMetadataUpdateRef = useRef(!historySession);
  const touchUpdatedAtRef = useRef(!historySession);
  const ignoreReplayChunksRef = useRef(!!historySession);
  const pinnedAgentSnapshotRef = useRef<PinnedAgentSnapshot | null>(null);

  const {
    applyBufferedChunks,
    enqueueChunk,
    clearBufferedChunks,
    markFlushUnscheduled,
  } = useBufferedMessageChunks({ setHistoryMessages, setLiveMessages });

  const consumeHandoff = useCallback(() => {
    const handoffId = pendingHandoffRef.current?.id;
    if (!handoffId) return;
    consumedHandoffIdRef.current = handoffId;
    pendingHandoffRef.current = null;
    onHandoffConsumed?.(handoffId);
  }, [onHandoffConsumed]);

  const selectedAgent = availableAgents.find((agent) => agent.id === selectedAgentId);
  const pinnedAgentId = selectedAgentId;

  useEffect(() => {
    const snapshotSourceId = pinnedAgentId;
    if (!snapshotSourceId) return;
    const matchingAgent = availableAgents.find((agent) => agent.id === snapshotSourceId);
    if (!matchingAgent) return;
    pinnedAgentSnapshotRef.current = toPinnedAgentSnapshot(matchingAgent);
  }, [availableAgents, pinnedAgentId]);

  const resolvedSelectedAgent = resolveSelectedAgent(selectedAgent, pinnedAgentSnapshotRef.current, pinnedAgentId);
  const availableCommands = useAvailableCommands(availableAgents, selectedAgentId);
  const effectiveSelectedAgent = resolvedSelectedAgent;
  const {
    availableModes,
    selectedModelId,
    selectedModeId,
    modelIdForStart,
    handleModelChange,
    handleModeChange,
  } = useAgentRuntimeOptions({
    availableAgents,
    effectiveSelectedAgent,
    selectedAgentId,
    conversationId,
    status,
    historySession,
    startedAgentIdRef,
    startedModelIdRef,
    startedModeIdRef,
  });

  const adapterDisplayName = resolvedSelectedAgent?.name || '';
  const agentOptions = useMemo(
    () => buildAgentOptions(availableAgents, pinnedAgentSnapshotRef.current, pinnedAgentId),
    [availableAgents, pinnedAgentId]
  );
  const modeOptions = useMemo(
    () => buildModeOptions(availableModes, selectedModeId),
    [availableModes, selectedModeId]
  );

  useEffect(() => {
    allowMetadataUpdateRef.current = false;
    lastMetadataFingerprintRef.current = '';
    statusRef.current = 'not started';
    setStatus('not started');
    setAcpSessionId('');
    startedAgentIdRef.current = '';
    startedModelIdRef.current = '';
    startedModeIdRef.current = '';
  }, [selectedAgentId]);

  useEffect(() => {
    if (!historySession) return;
    if (historySession.sessionId) {
      setAcpSessionId(historySession.sessionId);
    }
    allowMetadataUpdateRef.current = false;
    touchUpdatedAtRef.current = false;
    lastMetadataFingerprintRef.current = '';
  }, [historySession]);

  const startSelectedAgent = useCallback(() => {
    if (!selectedAgentId || typeof window.__startAgent !== 'function') return false;
    if (historySession) return false;
    if (!selectedAgent?.downloaded) {
      return false;
    }

    const modelId = modelIdForStart;

    try {
      startedAgentIdRef.current = selectedAgentId;
      startedModelIdRef.current = modelId || '';
      // startAgent() already applies the adapter's current startup mode on the backend.
      // Keep that as the baseline so we only call __setMode() when the user
      // selected a different mode than the startup-selected mode.
      startedModeIdRef.current = selectedAgent?.currentModeId || '';

      clearBufferedChunks();
      window.__startAgent(conversationId, selectedAgentId, modelId || undefined);
      return true;
    } catch (e) {
      console.warn('[useChatSession] Failed to auto-start agent:', e);
      return false;
    }
  }, [clearBufferedChunks, conversationId, historySession, modelIdForStart, selectedAgent, selectedAgentId]);

  useEffect(() => {
    if (!pendingHandoff) return;
    if (consumedHandoffIdRef.current === pendingHandoff.id) return;
    pendingHandoffRef.current = pendingHandoff;
  }, [pendingHandoff]);

  // =========================================================================
  // Chat Event Listeners (filtered by conversationId)
  // =========================================================================
  useEffect(() => {
    // --- UNIFIED content handler: one handler for both streaming and replay ---
    const unsubContent = ACPBridge.onContentChunk((e) => {
      const chunk = e.detail.chunk;
      if (chunk.chatId !== conversationId) return;
      if (chunk.isReplay && ignoreReplayChunksRef.current) {
        return;
      }
      enqueueChunk(chunk);
      if (!chunk.isReplay && chunk.type === 'prompt_done') {
        markFlushUnscheduled();
        applyBufferedChunks('prompt-done');
      }
    });

    const unsubConversationReplayLoaded = ACPBridge.onConversationReplayLoaded((e) => {
      const payload = e.detail.payload;
      if (payload.chatId !== conversationId) return;
      ignoreReplayChunksRef.current = true;
      clearBufferedChunks();
      setHistoryMessages(buildReplayMessages(payload.data));
      setIsHistoryReplaying(false);
    });

    const unsubStatus = ACPBridge.onStatus((e) => {
      if (e.detail.chatId !== conversationId) return;
      const s = e.detail.status;
      statusRef.current = s;
      if (s === 'ready' && resetSessionAfterInitialCancelRef.current) {
        resetSessionAfterInitialCancelRef.current = false;
        statusRef.current = 'not started';
        setStatus('not started');
        setAcpSessionId('');
        startedAgentIdRef.current = '';
        startedModelIdRef.current = '';
        startedModeIdRef.current = '';
        setIsSending(false);
      } else {
        setStatus(s);
      }

      if (s === 'ready' || s === 'error') {
        startTimeRef.current = null;

        // Flush any remaining buffered chunks through the same path as RAF flush.
        markFlushUnscheduled();
        applyBufferedChunks('status-ready');

        if (!pendingPromptRef.current && !historySession) {
          setIsHistoryReplaying(false);
        }
      }

      // Error is merged into ready block

      if (s === 'ready' && pendingPromptRef.current && typeof window.__sendPrompt === 'function') {
        const blocksToSend = pendingPromptRef.current;
        pendingPromptRef.current = null;

        setIsSending(true);
        
        // Assistant message is already added in handleSend, we just need to trigger the actual send
        try {
          window.__sendPrompt(conversationId, JSON.stringify(blocksToSend));
          consumeHandoff();
        } catch (err) {
          pendingPromptRef.current = blocksToSend;
          console.warn('[useChatSession] Failed to send pending blocks:', err);
          setIsSending(false);
        }
      }
    });

    const unsubSessionId = ACPBridge.onSessionId((e) => {
      if (e.detail.chatId !== conversationId) return;
      setAcpSessionId(e.detail.sessionId);
      allowMetadataUpdateRef.current = true;
      lastMetadataFingerprintRef.current = '';
    });

    const unsubMode = ACPBridge.onMode((e) => {
      if (e.detail.chatId !== conversationId) return;
      startedModeIdRef.current = e.detail.modeId;
    });

    // Permission request - filter by chatId when available
    const unsubPermission = ACPBridge.onPermissionRequest((e) => {
      const req = e.detail.request as PermissionRequest;
      if (req.chatId && req.chatId !== conversationId) return;
      setPermissionQueue((prev) => [...prev, req]);
    });

    return () => {
      unsubContent();
      unsubConversationReplayLoaded();
      unsubStatus();
      unsubSessionId();
      unsubMode();
      unsubPermission();
    };
  }, [conversationId, enqueueChunk, applyBufferedChunks, clearBufferedChunks, markFlushUnscheduled, consumeHandoff]);

  useEffect(() => {
    if (!isSending || isHistoryReplaying) return;
    if (!lastAssistantMessageHasMeta(messages)) return;
    setIsSending(false);
  }, [messages, isSending, isHistoryReplaying]);

  // Handle native attachments from backend
  useEffect(() => {
    const unsub = ACPBridge.onAttachmentsAdded((e) => {
      const { chatId: cid, files } = e.detail;
      if (cid !== conversationId) return;
      setAttachments((prev) => [...prev, ...files]);
    });
    return unsub;
  }, [conversationId]);

  useEffect(() => {
    if (!historySession) return;
    const loadRequestKey = historySession.conversationId;
    if (historyLoadRequestedRef.current === loadRequestKey) return;

    clearBufferedChunks();
    pendingPromptRef.current = null;
    setHistoryMessages([]);
    setLiveMessages([]);
    setStatus('initializing');
    setIsHistoryReplaying(true);
    ignoreReplayChunksRef.current = true;

    startedAgentIdRef.current = historySession.adapterName;
    startedModelIdRef.current = historySession.modelId || '';
    startedModeIdRef.current = historySession.modeId || '';

    if (historyLoadTimerRef.current !== null) {
      window.clearTimeout(historyLoadTimerRef.current);
      historyLoadTimerRef.current = null;
    }

    historyLoadTimerRef.current = window.setTimeout(() => {
      if (historyLoadRequestedRef.current === loadRequestKey) {
        return;
      }
      historyLoadRequestedRef.current = loadRequestKey;
      ACPBridge.loadHistoryConversation(
        conversationId,
        historySession.projectPath,
        historySession.conversationId
      );
      historyLoadTimerRef.current = null;
    }, 0);

    return () => {
      if (historyLoadTimerRef.current !== null) {
        window.clearTimeout(historyLoadTimerRef.current);
        historyLoadTimerRef.current = null;
      }
    };
  }, [clearBufferedChunks, conversationId, historySession]);

  useEffect(() => {
    if (status !== 'ready') return;
    if (!acpSessionId || !selectedAgentId) return;
    if (!allowMetadataUpdateRef.current) return;

    const promptCount = messages.filter((message) => message.role === 'user').length;
    if (promptCount <= 0) return;

    const title = titleFromFirstPrompt(messages);
    const fingerprint = `${acpSessionId}|${selectedAgentId}|${promptCount}|${title || ''}`;
    if (lastMetadataFingerprintRef.current === fingerprint) return;

    ACPBridge.updateSessionMetadata({
      conversationId,
      sessionId: acpSessionId,
      adapterName: selectedAgentId,
      promptCount,
      title,
      touchUpdatedAt: touchUpdatedAtRef.current
    });
    window.setTimeout(() => {
      ACPBridge.requestHistoryList();
    }, 100);
    lastMetadataFingerprintRef.current = fingerprint;
  }, [conversationId, status, acpSessionId, selectedAgentId, messages]);

  const handleSend = () => {
    const text = inputValue.trim();
    if ((!text && attachments.length === 0) || isSending || status === 'prompting') return;

    if (typeof window.__sendPrompt !== 'function') return;

    const normalizedBlocks = normalizeOutgoingBlocks(buildPromptBlocks(inputValue, attachments));
    if (normalizedBlocks.length === 0) return;
    const outgoingBlocks = pendingHandoffRef.current
      ? prependHandoffContext(normalizedBlocks, pendingHandoffRef.current.text)
      : normalizedBlocks;

    allowMetadataUpdateRef.current = true;
    touchUpdatedAtRef.current = true;
    setIsSending(true);
    const userMessage: Message = {
      id: nextMessageId('user'),
      role: 'user',
      content: plainTextFromBlocks(normalizedBlocks),
      blocks: normalizedBlocks,
      timestamp: Date.now(),
    };
    setLiveMessages((prev) => [...prev, userMessage]);
    setInputValue('');
    setAttachments([]);
    const promptStartedAt = Date.now();
    startTimeRef.current = promptStartedAt;
    const assistantMessage: Message = {
      id: nextMessageId('assistant'),
      role: 'assistant',
      content: '',
      contentBlocks: [],
      timestamp: Date.now(),
      agentId: selectedAgentId,
      agentName: adapterDisplayName,
      modelName: selectedModelId,
      modeName: selectedModeId,
      promptStartedAtMillis: promptStartedAt,
      metaComplete: false,
    };
    setLiveMessages((prev) => [...prev, assistantMessage]);

    if (status !== 'ready' && status !== 'error') {
      // Queue it up
      pendingPromptRef.current = outgoingBlocks;
      if (status === 'not started' || status === 'error') {
        startSelectedAgent();
      }
      return;
    }

    try {
      window.__sendPrompt(conversationId, JSON.stringify(outgoingBlocks));
      consumeHandoff();
      setPermissionQueue([]);
    } catch (e) {
      console.warn('[useChatSession] Failed to send prompt:', e);
      setIsSending(false);
    }
  };

  const handleStop = () => {
    if (typeof window.__cancelPrompt === 'function') {
      const liveUserMessageCount = liveMessages.filter((message) => message.role === 'user').length;
      resetSessionAfterInitialCancelRef.current = !historySession && liveUserMessageCount === 1;
      pendingPromptRef.current = null;
      window.__cancelPrompt(conversationId);
      setPermissionQueue([]);
    }
  };

  const handlePermissionDecision = (decision: string) => {
    if (!permissionRequest) return;
    try {
      if (window.__respondPermission) {
        window.__respondPermission(permissionRequest.requestId, decision);
      }
      // Dequeue the answered request; if more are pending the next one becomes visible automatically.
      setPermissionQueue((prev) => prev.slice(1));
    } catch (e) {
      console.warn('[useChatSession] Failed to respond to permission:', e);
    }
  };

  return {
    messages,
    inputValue,
    setInputValue,
    status,
    isSending,
    isHistoryReplaying,
    selectedAgentId,
    agentOptions,
    selectedModelId,
    handleModelChange,
    selectedModeId,
    modeOptions,
    handleModeChange,
    permissionRequest,
    handleSend,
    handleStop,
    handlePermissionDecision,
    hasSelectedAgent: !!resolvedSelectedAgent,
    attachments,
    setAttachments,
    availableCommands,
    acpSessionId,
    adapterName: selectedAgentId,
    adapterDisplayName,
    adapterIconPath: resolvedSelectedAgent?.iconPath || ''
  };
}
