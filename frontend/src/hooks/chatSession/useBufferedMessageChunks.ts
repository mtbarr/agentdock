import { Dispatch, SetStateAction, useCallback, useRef } from 'react';
import { ContentChunk, Message } from '../../types/chat';
import { applyChunks, closeAllStreamingThinking } from './messageProcessing';

type UseBufferedMessageChunksArgs = {
  setHistoryMessages: Dispatch<SetStateAction<Message[]>>;
  setLiveMessages: Dispatch<SetStateAction<Message[]>>;
};

export function useBufferedMessageChunks({
  setHistoryMessages,
  setLiveMessages,
}: UseBufferedMessageChunksArgs) {
  const chunkBufferRef = useRef<ContentChunk[]>([]);
  const flushScheduledRef = useRef(false);

  const applyBufferedChunks = useCallback((reason: string) => {
    const chunks = chunkBufferRef.current;
    chunkBufferRef.current = [];
    if (chunks.length === 0 && reason !== 'status-ready') return;
    const replayChunks = chunks.filter((chunk) => chunk.isReplay);
    const liveChunks = chunks.filter((chunk) => !chunk.isReplay);

    setHistoryMessages(prev => {
      const result = replayChunks.length > 0 ? applyChunks(prev, replayChunks) : prev;
      return reason === 'status-ready' ? closeAllStreamingThinking(result) : result;
    });

    setLiveMessages(prev => {
      const result = liveChunks.length > 0 ? applyChunks(prev, liveChunks) : prev;
      return reason === 'status-ready' ? closeAllStreamingThinking(result) : result;
    });
  }, [setHistoryMessages, setLiveMessages]);

  const flushChunks = useCallback(() => {
    flushScheduledRef.current = false;
    applyBufferedChunks('raf');
  }, [applyBufferedChunks]);

  const enqueueChunk = useCallback((chunk: ContentChunk) => {
    chunkBufferRef.current.push(chunk);
    if (!flushScheduledRef.current) {
      flushScheduledRef.current = true;
      requestAnimationFrame(flushChunks);
    }
  }, [flushChunks]);

  const clearBufferedChunks = useCallback(() => {
    chunkBufferRef.current = [];
    flushScheduledRef.current = false;
  }, []);

  const markFlushUnscheduled = useCallback(() => {
    flushScheduledRef.current = false;
  }, []);

  return {
    applyBufferedChunks,
    enqueueChunk,
    clearBufferedChunks,
    markFlushUnscheduled,
  };
}
