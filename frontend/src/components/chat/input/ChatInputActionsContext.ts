import { createContext } from 'react';
import { ChatAttachment } from '../../../types/chat';

export interface ChatInputActions {
  onImageClick: (src: string) => void;
  onOpenFile?: (path: string, line?: number) => void;
  attachments: ChatAttachment[];
}

export const ChatInputActionsContext = createContext<ChatInputActions | null>(null);
