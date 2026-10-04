import { KeyRound } from 'lucide-react'
import type { LucideIcon } from 'lucide-react'
import { channelDescriptors } from './channels'
import { connectorsNeedingAccounts } from './connectors'
import type { FieldSpec } from './fields'
import { llmAccount } from './llm'

export type AccountSection = 'accounts' | 'llm'

export interface AccountDescriptor {
  id: string
  label: string
  description: string
  icon: LucideIcon
  /** Rendered masked. */
  authFields: FieldSpec[]
  configFields: FieldSpec[]
  credentialHelp?: { title: string; steps: string[]; scopes?: string[] }
  /** The provider's server-side id. */
  oauth?: { provider: string }
  /** Which block of the Accounts page lists it; absent is `accounts`. */
  section?: AccountSection
  /** Title of the config card; absent means the OAuth-client card. */
  configTitle?: string
}

/** Page order; `addType` preselects the type on the add form. */
export const accountSections: { id: AccountSection; addType?: string }[] = [
  { id: 'accounts' },
  { id: 'llm', addType: llmAccount.id },
]

export function sectionOf(descriptor: AccountDescriptor): AccountSection {
  return descriptor.section ?? 'accounts'
}

export function accountTypes(): AccountDescriptor[] {
  const fromChannels = channelDescriptors
    .filter((channel) => channel.implemented)
    .flatMap((channel) => (channel.account ? [channel.account] : []))
  const seen = new Set<string>()
  return [...connectorsNeedingAccounts(), ...fromChannels, llmAccount].filter((descriptor) => {
    if (seen.has(descriptor.id)) return false
    seen.add(descriptor.id)
    return true
  })
}

/** Safe for a connection type this file does not know. */
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
