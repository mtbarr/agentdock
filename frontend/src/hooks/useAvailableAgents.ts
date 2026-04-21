import { useEffect, useRef, useState } from 'react';
import { AgentOption } from '../types/chat';
import { ACPBridge } from '../utils/bridge';

export function useAvailableAgents() {
  const [availableAgents, setAvailableAgents] = useState<AgentOption[]>([]);
  const [adaptersResolved, setAdaptersResolved] = useState(false);
  const lastStableNewTabAgentIdRef = useRef<string>('');
  const stableAgentSnapshotsRef = useRef<Record<string, AgentOption>>({});

  useEffect(() => {
    const dispose = ACPBridge.onAdapters((e) => {
      const safeAdapters = Array.isArray(e.detail.adapters) ? e.detail.adapters : [];
      const nextSnapshots = { ...stableAgentSnapshotsRef.current };
      safeAdapters.forEach((agent) => {
        const previous = nextSnapshots[agent.id];
        nextSnapshots[agent.id] = {
          ...previous,
          ...agent,
          iconPath: agent.iconPath || previous?.iconPath,
          name: agent.name || previous?.name,
        };
      });
      stableAgentSnapshotsRef.current = nextSnapshots;

      const stableLastUsedRunnableId = safeAdapters.find((agent) => agent.isLastUsed && agent.downloaded === true)?.id;
      if (stableLastUsedRunnableId) {
        lastStableNewTabAgentIdRef.current = stableLastUsedRunnableId;
      } else {
        const currentStable = lastStableNewTabAgentIdRef.current;
        const currentStableStatus = currentStable ? safeAdapters.find((agent) => agent.id === currentStable) : undefined;
        if (currentStableStatus?.downloadedKnown === true && currentStableStatus.downloaded !== true) {
          lastStableNewTabAgentIdRef.current = safeAdapters.find((agent) => agent.downloaded === true)?.id || '';
        } else if (!currentStable) {
          lastStableNewTabAgentIdRef.current = safeAdapters.find((agent) => agent.downloaded === true)?.id || '';
        }
      }

      setAvailableAgents(safeAdapters);
      setAdaptersResolved(true);
      if (safeAdapters.length > 0) {
        try {
          localStorage.setItem('agent-dock.adapters', JSON.stringify(safeAdapters));
        } catch (e) {
          console.warn('[App] Failed to cache adapters:', e);
        }
      }
    });
    ACPBridge.requestAdapters();
    return dispose;
  }, []);

  return {
    availableAgents,
    adaptersResolved,
    lastStableNewTabAgentIdRef,
  };
}
