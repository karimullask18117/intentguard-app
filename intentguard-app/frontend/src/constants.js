export const TOOLS = [
  'read_email', 'read_file', 'search_files', 'http_get',
  'write_file', 'edit_file', 'create_event',
  'send_email', 'post_message', 'http_post',
  'delete_file', 'run_shell', 'make_payment',
];

export const SOURCES = [
  { value: 'agent', label: "Agent's own plan" },
  { value: 'user', label: 'You (direct)' },
  { value: 'email', label: 'Email content' },
  { value: 'webpage', label: 'Web page' },
  { value: 'document', label: 'Document' },
  { value: 'tool_output', label: 'Tool output' },
];

export const MODES = [
  { value: 'strict', label: 'Strict' },
  { value: 'balanced', label: 'Balanced' },
  { value: 'relaxed', label: 'Relaxed' },
];

export const UNTRUSTED = ['email', 'webpage', 'document', 'tool_output'];
