import { Hash, Mail, MessageCircle, Send } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import type { Blob, ChannelType } from '@/api/types'
import type { AccountDescriptor } from './accounts'
import { googleAuthFields, googleConfigFields } from './connectors'
import type { FieldSpec } from './fields'

/**
 * One descriptor per publishing channel type — the same idea as `connectors.ts`, for the write side.
 * The backend discovers a `Publisher` bean per type; here a type is one object, and the channel
 * form renders its `targetFields` through `<SchemaForm>`. No component branches on a ChannelType.
 *
 * To add a platform once the backend has a publisher for it: fill in `targetFields` and flip
 * `implemented` to true.
 */
export interface ChannelDescriptor {
  id: ChannelType
  /** What the user picks. */
  label: string
  /** One sentence answering "where will this send?". */
  description: string
  icon: LucideIcon
  /** False for ChannelType constants with no publisher behind them yet. */
  implemented: boolean
  /** Written into `Channel.target`. */
  targetFields: FieldSpec[]
  /**
   * The kind of account this channel sends through, for a platform that sends as a user. It is offered
   * on the Accounts screen like a source's account, and as the "Send from" choice on the channel form.
   */
  account?: AccountDescriptor
  /** One line naming the destination in the channel list, so no component reads target keys. */
  summary?: (target: Blob) => string | undefined
}

export const channelDescriptors: ChannelDescriptor[] = [
  {
    id: 'EMAIL',
    label: 'Email',
    description: 'Send messages to one or more email addresses.',
    icon: Mail,
    implemented: true,
    account: {
      id: 'GMAIL_SEND',
      label: 'Gmail (sending)',
      description:
        'Sends messages from your Gmail address. It is allowed to send mail and nothing else — it cannot read your inbox.',
      icon: Send,
      authFields: googleAuthFields,
      configFields: googleConfigFields,
      credentialHelp: {
        title: 'Connecting a sending account',
        steps: [
          'Click Connect with Google above and approve sending — that is the only permission requested.',
          'One-off setup in Google Cloud Console: on the OAuth consent screen, under Data access, add the gmail.send scope.',
          'The first time, Google shows a "Google hasn\'t verified this app" screen: choose Advanced, then continue.',
          'Publish the consent screen (OAuth consent screen → Publish app). While it stays in Testing, Google cuts the connection off after 7 days.',
        ],
        scopes: ['https://www.googleapis.com/auth/gmail.send'],
      },
      oauth: { provider: 'google' },
    },
    targetFields: [
      {
        name: 'to',
        kind: 'list',
        label: 'Send to',
        hint: 'One or more email addresses. Your own address works — the message arrives in your inbox.',
        placeholder: 'you@example.com',
        required: true,
      },
      {
        name: 'cc',
        kind: 'list',
        label: 'Copy to',
        placeholder: 'someone@example.com',
      },
      {
        name: 'subjectPrefix',
        kind: 'text',
        label: 'Subject prefix',
        hint: 'Put in front of every subject — handy for a mail filter that labels these messages.',
        placeholder: '[assistant]',
      },
    ],
    summary: (target) => {
      const to = target.to
      if (Array.isArray(to)) return to.join(', ') || undefined
      return typeof to === 'string' ? to : undefined
    },
  },
  {
    id: 'SLACK',
    label: 'Slack',
    description: 'Post messages to a Slack channel.',
    icon: Hash,
    implemented: false,
    targetFields: [],
  },
  {
    id: 'WHATSAPP',
    label: 'WhatsApp',
    description: 'Send messages to a WhatsApp chat.',
    icon: MessageCircle,
    implemented: false,
    targetFields: [],
  },
]

const unknownChannel = (type: string): ChannelDescriptor => ({
  id: type as ChannelType,
  label: type,
  description: '',
  icon: MessageCircle,
  implemented: false,
  targetFields: [],
})

/** Safe for a type the backend added before this file learned about it. */
export function channelFor(type: ChannelType | string): ChannelDescriptor {
  return channelDescriptors.find((d) => d.id === type) ?? unknownChannel(type)
}

export const implementedChannels = () => channelDescriptors.filter((d) => d.implemented)
