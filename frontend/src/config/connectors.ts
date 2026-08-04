import { Folder, HardDrive, Hash, Mail, NotebookPen } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import type { SourceType } from '@/api/types'
import type { FieldSpec } from './fields'

/**
 * One descriptor per connector. This is the frontend's mirror of the backend's CDI discovery:
 * over there a connector is "add an @ApplicationScoped bean", over here it is "append an object
 * to this array". The wizard, the type filters, the account forms, the settings form and the
 * empty states all render from these — no component branches on a SourceType anywhere.
 *
 * To add a connector once the backend supports it: add the object, flip `implemented` to true.
 */
export interface ConnectorDescriptor {
  id: SourceType
  /** What the user picks in the wizard. */
  label: string
  /** One sentence answering "what will this let me search?". */
  description: string
  icon: LucideIcon
  /** False for SourceType constants with no connector behind them yet. */
  implemented: boolean
  /** Whether an Account must be selected before this source can be created. */
  requiresConnection: boolean
  /** Written into `Knowledge.inputs`. */
  inputFields: FieldSpec[]
  /** Written into `Connection.auth` — rendered masked. */
  authFields: FieldSpec[]
  /** Written into `Connection.config`. */
  configFields: FieldSpec[]
  /** Rendered in the "how do I get these?" panel on the account form. */
  credentialHelp?: { title: string; steps: string[]; scopes?: string[] }
}

const googleAuthFields: FieldSpec[] = [
  {
    name: 'refreshToken',
    kind: 'secret',
    label: 'Refresh token',
    hint: 'Long-lived token used to stay signed in. Stored on this machine only.',
    required: true,
  },
  {
    name: 'accessToken',
    kind: 'secret',
    label: 'Access token',
    hint: 'Optional. Short-lived; one is fetched automatically when needed.',
    technical: true,
  },
  {
    name: 'expiresAtEpochSec',
    kind: 'number',
    label: 'Access token expiry',
    hint: 'Unix seconds. Leave blank unless you pasted an access token above.',
    technical: true,
  },
]

const googleConfigFields: FieldSpec[] = [
  {
    name: 'clientId',
    kind: 'text',
    label: 'OAuth client ID',
    hint: 'Leave blank to use the one configured on the server.',
    placeholder: '…apps.googleusercontent.com',
  },
  {
    name: 'clientSecret',
    kind: 'secret',
    label: 'OAuth client secret',
    hint: 'Leave blank to use the one configured on the server.',
  },
]

const googleHelpSteps = [
  'In Google Cloud Console, create an OAuth 2.0 Client ID of type "Desktop app".',
  'Enable the Gmail API and the Google Drive API for that project.',
  'Run the OAuth consent flow for your own account and copy the refresh token it returns.',
  'Paste the refresh token above. Client ID and secret are only needed if the server has none configured.',
]

export const connectors: ConnectorDescriptor[] = [
  {
    id: 'LOCAL_FS',
    label: 'Folder on this computer',
    description: 'Documents, PDFs, spreadsheets and notes in a folder you choose.',
    icon: Folder,
    implemented: true,
    requiresConnection: false,
    inputFields: [
      {
        name: 'rootPath',
        kind: 'text',
        label: 'Folder',
        hint: 'The full path to the folder to index, including everything inside it.',
        placeholder: '/Users/you/Documents',
        required: true,
        validate: (value) =>
          typeof value === 'string' && value.trim() && !value.startsWith('/')
            ? 'Enter a full path starting with /'
            : undefined,
      },
    ],
    authFields: [],
    configFields: [],
  },
  {
    id: 'GMAIL',
    label: 'Gmail',
    description: 'Your mail, searchable by what it says rather than just the subject line.',
    icon: Mail,
    implemented: true,
    requiresConnection: true,
    inputFields: [
      {
        name: 'labelIds',
        kind: 'list',
        label: 'Labels',
        hint: 'Leave empty to include all mail. Add labels to limit it, one per line.',
        placeholder: 'INBOX',
      },
      {
        name: 'query',
        kind: 'text',
        label: 'Filter',
        hint: 'Optional Gmail search, applied to everything imported. e.g. -in:spam',
        placeholder: '-in:spam -in:trash',
      },
    ],
    authFields: googleAuthFields,
    configFields: googleConfigFields,
    credentialHelp: {
      title: 'Connecting Gmail',
      steps: googleHelpSteps,
      scopes: ['https://www.googleapis.com/auth/gmail.readonly'],
    },
  },
  {
    id: 'GOOGLE_DRIVE',
    label: 'Google Drive',
    description: 'Docs, Sheets, Slides and uploaded files from your Drive.',
    icon: HardDrive,
    implemented: true,
    requiresConnection: true,
    inputFields: [
      {
        name: 'folderIds',
        kind: 'list',
        label: 'Folders',
        hint: 'Leave empty for your whole Drive. Add folder IDs to limit it, one per line.',
        placeholder: 'root',
      },
    ],
    authFields: googleAuthFields,
    configFields: googleConfigFields,
    credentialHelp: {
      title: 'Connecting Google Drive',
      steps: googleHelpSteps,
      scopes: ['https://www.googleapis.com/auth/drive.readonly'],
    },
  },
  {
    id: 'SLACK',
    label: 'Slack',
    description: 'Channel history and threads.',
    icon: Hash,
    implemented: false,
    requiresConnection: true,
    inputFields: [],
    authFields: [],
    configFields: [],
  },
  {
    id: 'NOTION',
    label: 'Notion',
    description: 'Pages and databases from a workspace.',
    icon: NotebookPen,
    implemented: false,
    requiresConnection: true,
    inputFields: [],
    authFields: [],
    configFields: [],
  },
]

const byId = new Map(connectors.map((c) => [c.id, c]))

/**
 * Look up a descriptor. Falls back to a synthetic one so an unknown SourceType from the backend
 * renders as itself rather than crashing the page.
 */
export function connectorFor(type: SourceType | string): ConnectorDescriptor {
  return (
    byId.get(type as SourceType) ?? {
      id: type as SourceType,
      label: type,
      description: '',
      icon: Folder,
      implemented: false,
      requiresConnection: false,
      inputFields: [],
      authFields: [],
      configFields: [],
    }
  )
}

export const availableConnectors = () => connectors.filter((c) => c.implemented)

/** Connectors that need an Account — drives whether the Accounts screen is relevant at all. */
export const connectorsNeedingAccounts = () =>
  connectors.filter((c) => c.implemented && c.requiresConnection)
