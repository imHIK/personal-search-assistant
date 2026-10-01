import { Sparkles } from 'lucide-react'
import type { AccountDescriptor } from './accounts'

/** Mirrors LlmConnections on the server: the key in auth, everything else in config. */
export const llmAccount: AccountDescriptor = {
  id: 'LLM',
  label: 'LLM',
  description: 'An OpenAI-compatible chat endpoint (Groq, Gemini, Ollama, …) used for answers and tasks.',
  icon: Sparkles,
  section: 'llm',
  authFields: [
    {
      name: 'apiKey',
      kind: 'secret',
      label: 'API key',
      hint: 'Empty sends no Authorization header (a local Ollama).',
    },
  ],
  configFields: [
    {
      name: 'baseUrl',
      kind: 'text',
      label: 'Base URL',
      placeholder: 'https://api.groq.com/openai/v1',
      required: true,
    },
    { name: 'model', kind: 'text', label: 'Model', placeholder: 'openai/gpt-oss-120b', required: true },
    {
      name: 'profile',
      kind: 'text',
      label: 'Profile',
      placeholder: 'lite',
      hint: 'The task profile this serves. Empty: only used as the default.',
    },
    { name: 'temperature', kind: 'number', label: 'Temperature', min: 0, max: 2 },
    { name: 'maxTokens', kind: 'number', label: 'Max tokens', min: 1 },
  ],
  configTitle: 'Model',
}
