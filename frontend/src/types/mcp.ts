export type McpTransport = 'stdio' | 'http' | 'sse';

export interface McpEnvVar {
  name: string;
  value: string;
}

export interface McpHeader {
  name: string;
  value: string;
}

export interface McpServerConfig {
  id: string;
  name: string;
  enabled: boolean;
  transport: McpTransport;
  // stdio
  command?: string;
  args?: string[];
  env?: McpEnvVar[];
  // http / sse
  url?: string;
  headers?: McpHeader[];
}
