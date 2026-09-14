import { KeyRound } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { channelDescriptors } from './channels'
import { connectorsNeedingAccounts } from './connectors'
import type { FieldSpec } from './fields'

/**
 * What the Accounts screens need to know about one connection type.
 *
 * A connection is no longer only a source's credentials — the email channel sends through an account
 * of its own — so the Accounts screens render from this rather than from connector descriptors. A
 * connector descriptor already has this shape; a channel type contributes one through
 * `ChannelDescriptor.account`. No component branches on which kind of thing owns an account.
 */
export interface AccountDescriptor {
  /** The connection type, stored as `Connection.type`. */
  id: string
  label: string
  description: string
  icon: LucideIcon
  /** Written into `Connection.auth` — rendered masked. */
  authFields: FieldSpec[]
  /** Written into `Connection.config`. */
  configFields: FieldSpec[]
  credentialHelp?: { title: string; steps: string[]; scopes?: string[] }
  /** Connect through the backend's OAuth flow; the value is the provider's server-side id. */
  oauth?: { provider: string }
}

/** Every account type something in the console can use: sources first, then channels. */
export function accountTypes(): AccountDescriptor[] {
  const fromChannels = channelDescriptors
    .filter((channel) => channel.implemented)
    .flatMap((channel) => (channel.account ? [channel.account] : []))
  const seen = new Set<string>()
  return [...connectorsNeedingAccounts(), ...fromChannels].filter((descriptor) => {
    if (seen.has(descriptor.id)) return false
    seen.add(descriptor.id)
    return true
  })
}

/** Safe for a connection type the backend knows and this file does not. */
export function accountFor(type: string): AccountDescriptor {
  return (
    accountTypes().find((descriptor) => descriptor.id === type) ?? {
      id: type,
      label: type,
      description: '',
      icon: KeyRound,
      authFields: [],
      configFields: [],
    }
  )
}
