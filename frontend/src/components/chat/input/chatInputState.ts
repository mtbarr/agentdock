import {
  AudioTranscriptionFeatureState,
  AvailableCommand,
  ChatAttachment,
  DropdownOption,
} from '../../../types/chat';

export interface ChatInputProps {
  conversationId: string;
  contextTokensUsed?: number;
  contextWindowSize?: number;
  inputValue: string;
  onInputChange: (val: string) => void;
  onSend: () => void;
  onStop: () => void;
  isSending: boolean;
  agentOptions: DropdownOption[];
  selectedAgentId: string;
  onAgentChange: (id: string) => void;
  selectedModelId: string;
  onModelChange: (id: string, targetAgentId?: string) => void;
  usageSessionKey?: string;
  status: string;
  modeOptions: DropdownOption[];
  selectedModeId: string;
  onModeChange: (id: string) => void;
  hasSelectedAgent: boolean;
  availableCommands: AvailableCommand[];
  attachments: ChatAttachment[];
  onAttachmentsChange: (items: ChatAttachment[]) => void;
  onImageClick: (src: string) => void;
  onHeightChange?: (contentHeight: number) => void;
  customHeight?: number;
  autoFocus?: boolean;
  isActive?: boolean;
}

export const emptyTranscriptionFeature: AudioTranscriptionFeatureState = {
  id: 'whisper-transcription',
  title: 'Whisper',
  installed: false,
  installing: false,
  supported: false,
  status: 'Loading',
  detail: '',
  installPath: '',
};
