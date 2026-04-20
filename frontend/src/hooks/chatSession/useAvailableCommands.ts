import { useEffect, useState } from 'react';
import { AgentOption, AvailableCommand } from '../../types/chat';
import { ACPBridge } from '../../utils/bridge';

export function useAvailableCommands(availableAgents: AgentOption[], selectedAgentId: string): AvailableCommand[] {
  const [availableCommandsByAgent, setAvailableCommandsByAgent] = useState<Record<string, AvailableCommand[]>>({});

  useEffect(() => {
    const nextByAgent: Record<string, AvailableCommand[]> = {};
    availableAgents.forEach((agent) => {
      const commands = ACPBridge.getAvailableCommands(agent.id);
      if (commands.length > 0) {
        nextByAgent[agent.id] = commands;
      }
    });
    setAvailableCommandsByAgent(nextByAgent);
  }, [availableAgents]);

  useEffect(() => {
    return ACPBridge.onAvailableCommands((e) => {
      const { adapterId, commands } = e.detail;
      setAvailableCommandsByAgent((prev) => ({
        ...prev,
        [adapterId]: commands,
      }));
    });
  }, []);

  return selectedAgentId
    ? (availableCommandsByAgent[selectedAgentId] ?? ACPBridge.getAvailableCommands(selectedAgentId))
    : [];
}
