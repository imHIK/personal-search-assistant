import { Briefcase, Folder, HardDrive, Hash, Mail, NotebookPen } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import type { SourceType } from '@/api/types'
import { knownCompanies, withCompanyLabels } from './companies'
import { knownLocations } from './locations'
import { roleExcludeTerms, roleIncludeTerms } from './roleTerms'
import { seniorityOptions } from './searchFilters'
import type { FieldSpec } from './fields'

export interface ResultFieldSpec {
  key: string
  /**
   * `flag` shows `label` when true; `option` shows the matching option's label and hides anything
   * unknown; `date` shows `label` plus a relative time.
   */
  kind: 'text' | 'flag' | 'option' | 'date'
  label?: string
  options?: { value: string; label: string }[]
}

export interface ConnectorDescriptor {
  id: SourceType
  label: string
  description: string
  icon: LucideIcon
  /** False for SourceType constants with no connector behind them yet. */
  implemented: boolean
  requiresConnection: boolean
  companyResolver?: boolean
  inputFields: FieldSpec[]
  /**
   * Adds inputs the form does not render, just before a create or edit is sent. `previous` is the
   * stored `inputs` on an edit.
   */
  deriveInputs?: (
    inputs: Record<string, unknown>,
    previous?: Record<string, unknown>,
  ) => Record<string, unknown>
  /** Rendered masked. */
  authFields: FieldSpec[]
  configFields: FieldSpec[]
  credentialHelp?: { title: string; steps: string[]; scopes?: string[] }
  /** The provider's server-side id. */
  oauth?: { provider: string }
  resultFields?: ResultFieldSpec[]
  groupNoun?: { one: string; many: string }
  /**
   * Mirrors the connector's `defaultRetention()`, so an empty retention field can say what it
   * inherits.
   */
  defaultRetention?: string
}

export const googleAuthFields: FieldSpec[] = [
  {
    name: 'refreshToken',
    kind: 'secret',
    label: 'Refresh token',
    hint: 'Normally filled in for you by Connect with Google. Paste one only if you obtained it by hand.',
    technical: true,
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

export const googleConfigFields: FieldSpec[] = [
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
  'Click Connect with Google above and approve the access — that is the whole flow.',
  'The first time, Google shows a "Google hasn\'t verified this app" screen: choose Advanced, then continue.',
  'One-off setup in Google Cloud Console: enable the Gmail and Drive APIs, and create an OAuth client of type "Web application" whose authorised redirect URIs include this console\'s address followed by /api/connections/oauth/google/callback.',
  'Publish the consent screen (OAuth consent screen → Publish app). While it stays in Testing, Google cuts the connection off after 7 days no matter what.',
]

export const connectors: ConnectorDescriptor[] = [
  {
    id: 'LOCAL_FS',
    label: 'Folder on this computer',
    description: 'Documents, PDFs, spreadsheets and notes in a folder you choose.',
    icon: Folder,
    implemented: true,
    requiresConnection: false,
    groupNoun: { one: 'Folder', many: 'Folders' },
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
    resultFields: [{ key: 'modifiedAt', kind: 'date', label: 'Modified' }],
  },
  {
    id: 'GMAIL',
    label: 'Gmail',
    description: 'Your mail, searchable by what it says rather than just the subject line.',
    icon: Mail,
    implemented: true,
    requiresConnection: true,
    groupNoun: { one: 'Label', many: 'Labels' },
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
    oauth: { provider: 'google' },
    credentialHelp: {
      title: 'Connecting Gmail',
      steps: googleHelpSteps,
      scopes: ['https://www.googleapis.com/auth/gmail.readonly'],
    },
    resultFields: [
      { key: 'from', kind: 'text' },
      { key: 'modifiedAt', kind: 'date', label: 'Received' },
    ],
  },
  {
    id: 'GOOGLE_DRIVE',
    label: 'Google Drive',
    description: 'Docs, Sheets, Slides and uploaded files from your Drive.',
    icon: HardDrive,
    implemented: true,
    requiresConnection: true,
    groupNoun: { one: 'Folder', many: 'Folders' },
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
    oauth: { provider: 'google' },
    credentialHelp: {
      title: 'Connecting Google Drive',
      steps: googleHelpSteps,
      scopes: ['https://www.googleapis.com/auth/drive.readonly'],
    },
    resultFields: [{ key: 'modifiedAt', kind: 'date', label: 'Modified' }],
  },
  {
    id: 'JOB_BOARDS',
    label: 'Company job boards',
    description: 'Open roles from company career pages. Add companies by name — the platform they use is worked out for you.',
    icon: Briefcase,
    implemented: true,
    requiresConnection: false,
    companyResolver: true,
    deriveInputs: withCompanyLabels,
    groupNoun: { one: 'Company', many: 'Companies' },
    // Mirrors JobBoardsConnector.defaultRetention().
    defaultRetention: '14d',
    inputFields: [
      {
        name: 'companies',
        kind: 'picklist',
        label: 'Companies',
        hint: 'Tick the ones you want. Anything not listed can be typed in — use the name as it appears in their careers URL, prefix it to pin a platform (lever:paytm), and for Workday, Oracle HCM, iCIMS, Keka, Freshteam, TurboHire, Zwayam or Eightfold paste the address of any one of their jobs. Check an unfamiliar name above before adding it.',
        placeholder: 'Or type another name — e.g. lever:paytm',
        addOwnLabel: 'Add',
        browseNoun: 'companies we have checked',
        options: knownCompanies.map((company) => ({
          value: company.handle,
          label: company.label,
          note: company.platform,
        })),
        required: true,
      },
      {
        name: 'titleInclude',
        kind: 'picklist',
        label: 'Only these kinds of role',
        hint: 'Matched against the job title. Leave empty to keep every role. This is the single biggest lever on how much gets indexed — on a real 71-company watchlist it took ~34,000 chunks down to ~7,000.',
        placeholder: 'Or type another word — e.g. compiler',
        addOwnLabel: 'Add',
        browseNoun: 'common role words',
        options: roleIncludeTerms.map((t) => ({ value: t.value, label: t.label, note: t.note })),
      },
      {
        name: 'titleExclude',
        kind: 'picklist',
        label: 'Never these',
        hint: 'Also matched against the title, and it wins over the list above — “Software Engineering Manager” is dropped even when “software” is ticked. Worth as much as the include list: excluding manager/sales/support alone removed a quarter of what an engineering filter had kept.',
        placeholder: 'Or type another word — e.g. principal',
        addOwnLabel: 'Add',
        browseNoun: 'common exclusions',
        options: roleExcludeTerms.map((t) => ({ value: t.value, label: t.label, note: t.note })),
      },
      {
        name: 'locations',
        kind: 'picklist',
        label: 'Only these locations',
        hint: 'Leave empty to keep every country. Tick cities rather than countries — most boards file a role as "Bengaluru" with no country, so "India" on its own misses them. A posting with no location at all is always kept.',
        placeholder: 'Or type another place — e.g. zurich',
        addOwnLabel: 'Add',
        browseNoun: 'common places',
        options: knownLocations.map((place) => ({
          value: place.value,
          label: place.label,
          note: place.note,
          values: place.values,
        })),
      },
      {
        name: 'includeRemote',
        kind: 'boolean',
        label: 'Keep remote roles that name no place',
        hint: 'A role the board marks remote is kept when its location names no place (just "Remote" or "Anywhere"). A remote role that names a place — "Remote - US", "Czech Republic" — still has to match the places above.',
        placeholder: 'Include remote roles',
      },
      {
        name: 'maxAgeDays',
        kind: 'number',
        label: 'Only postings newer than',
        hint: 'In days. Leave empty to keep everything on the board. A posting whose board publishes no date is always kept — several do not publish one.',
        placeholder: '14',
        min: 1,
        max: 365,
      },
    ],
    authFields: [],
    configFields: [],
    resultFields: [
      { key: 'company', kind: 'text' },
      { key: 'location', kind: 'text' },
      { key: 'seniority', kind: 'option', options: seniorityOptions },
      { key: 'remote', kind: 'flag', label: 'Remote' },
      { key: 'postedAt', kind: 'date', label: 'Posted' },
    ],
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

/** Falls back to a synthetic descriptor, so an unknown SourceType renders as itself. */
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

export const connectorsNeedingAccounts = () =>
  connectors.filter((c) => c.implemented && c.requiresConnection)
