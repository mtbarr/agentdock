import { createContext } from 'react';

export interface ChatInputActions {
  onImageClick: (src: string) => void;
  attachments: { id: string; name: string; mimeType: string; data?: string; path?: string }[];
}

export const ChatInputActionsContext = createContext<ChatInputActions | null>(null);
