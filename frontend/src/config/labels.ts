/**
 * Every user-facing string in the app.
 *
 * Two reasons this file exists. First, the interface vocabulary is reviewable in one place rather
 * than scattered across components. Second, the domain vocabulary and the UI vocabulary are
 * deliberately different: the code, API and docs say Knowledge / Entity / Iterable / Cursor
 * (see CLAUDE.md — that is fixed), but none of those words mean anything to someone using the app.
 * The mapping lives here and nowhere else, so changing "Sources" back to "Knowledge" is one edit.
 */

export const nouns = {
  /** Knowledge */
  source: 'Source',
  sources: 'Sources',
  /** Entity */
  item: 'Item',
  items: 'Items',
  /** Connection */
  account: 'Account',
  accounts: 'Accounts',
} as const

export const labels = {
  app: {
    name: 'Personal Search Assistant',
    short: 'Search Assistant',
  },

  nav: {
    search: 'Search',
    sources: nouns.sources,
    accounts: nouns.accounts,
    technicalDetails: 'Technical details',
    technicalDetailsHint: 'Show ids, raw states and internal counters',
    theme: 'Theme',
    serverOnline: 'Connected',
    serverOffline: 'Server unreachable',
    serverChecking: 'Connecting…',
  },

  search: {
    title: 'Search',
    placeholder: 'Search everything you have connected…',
    submit: 'Search',
    mode: 'Search style',
    modeHint: 'How results are matched',
    topK: 'Results',
    scope: 'Look in',
    scopeAll: `All ${nouns.sources.toLowerCase()}`,
    answerToggle: 'Answer my question',
    answerToggleHint: 'Reads the top results and writes a cited answer',
    answerHeading: 'Answer',
    answerUnavailable:
      'The answer service is unavailable, so only results are shown. Check that an LLM provider and API key are configured.',
    narrowBy: 'Narrow by',
    addFilter: 'Add filter',
    filterKey: 'Field',
    filterValue: 'Value',
    empty: 'No results',
    emptyHint: 'Try different words, widen the scope, or check that indexing has finished.',
    idle: 'Search across everything you have connected.',
    idleHint: 'Results come from your own files, mail and documents — nothing leaves this machine unless you ask for an answer.',
    resultCount: (n: number, seconds: string) =>
      `${n} ${n === 1 ? 'result' : 'results'} in ${seconds}s`,
    openOriginal: 'Open',
    copyLink: 'Copy link',
    viewItem: `View ${nouns.item.toLowerCase()}`,
    citation: (n: number) => `Source ${n}`,
  },

  sources: {
    title: nouns.sources,
    subtitle: 'Places this assistant reads from.',
    add: `Add ${nouns.source.toLowerCase()}`,
    empty: `No ${nouns.sources.toLowerCase()} yet`,
    emptyHint: `Connect a folder or an account and everything in it becomes searchable.`,
    itemsSearchable: (n: number) => `${n.toLocaleString()} searchable`,
    itemsProcessing: (n: number) => `${n.toLocaleString()} processing`,
    itemsFailed: (n: number) => `${n.toLocaleString()} couldn't be processed`,
    checkNow: 'Check now',
    checking: 'Checking for new items…',
    pause: 'Pause',
    resume: 'Resume',
    remove: 'Remove',
    removeTitle: (name: string) => `Remove "${name}"?`,
    removeBody:
      'This deletes everything indexed from it. The original files and messages are untouched, and you can add it again later.',
    removeConfirmHint: (name: string) => `Type "${name}" to confirm`,
    lastChecked: 'Last checked',
    nextCheck: 'Next check',
    never: 'never',
    dueNow: 'due now',
  },

  detail: {
    overview: 'Overview',
    items: nouns.items,
    activity: 'Sync activity',
    settings: 'Settings',
    searchable: 'Searchable',
    processing: 'Processing',
    failed: "Couldn't process",
    progress: (indexed: number, total: number) =>
      `${indexed.toLocaleString()} of ${total.toLocaleString()} ready to search`,
  },

  items: {
    all: 'All',
    searchable: 'Searchable',
    processing: 'Processing',
    failed: "Couldn't process",
    empty: 'Nothing here yet',
    emptyHint: 'Items appear as they are imported. This can take a few minutes after connecting.',
    emptyFiltered: 'Nothing matches this filter',
    refresh: 'Reprocess',
    refreshHint: 'Read this item again from what was already downloaded',
    remove: 'Remove',
    removeHint: 'Drop this item from search. It comes back on the next check.',
    added: 'Added',
    kind: 'Kind',
    state: 'State',
    name: 'Name',
    page: (from: number, to: number, total: number) =>
      `${from.toLocaleString()}–${to.toLocaleString()} of ${total.toLocaleString()}`,
    previous: 'Previous',
    next: 'Next',
  },

  activity: {
    empty: 'No sync activity yet',
    emptyHint: 'Activity appears once the first check runs.',
    older: 'Older items',
    newer: 'New items',
    everything: 'Everything',
    imported: (n: number) => `${n.toLocaleString()} imported`,
    lastChecked: (when: string) => `last checked ${when}`,
    neverChecked: 'not checked yet',
    legend: 'What these mean',
  },

  accounts: {
    title: nouns.accounts,
    subtitle: 'Sign-in details reused across sources.',
    add: `Add ${nouns.account.toLowerCase()}`,
    empty: `No ${nouns.accounts.toLowerCase()} yet`,
    emptyHint: 'Google sources need an account before they can be connected.',
    makeDefault: 'Make default',
    isDefault: 'Default',
    defaultHint: (type: string) => `Used automatically by new ${type} sources`,
    edit: 'Edit',
    remove: 'Remove',
    removeTitle: (name: string) => `Remove "${name}"?`,
    removeBody: 'Sources using it will stop working until you connect another account.',
    inUse: 'This account is still used by one or more sources. Remove or repoint them first.',
    verifyFailed: "We couldn't connect to this account",
    reveal: 'Show',
    hide: 'Hide',
    helpTitle: 'How do I get these?',
  },

  wizard: {
    title: `Add a ${nouns.source.toLowerCase()}`,
    stepType: 'What do you want to search?',
    stepAccount: 'Which account?',
    stepInputs: 'What should be included?',
    stepSchedule: 'How often should we check?',
    comingSoon: 'Coming soon',
    back: 'Back',
    next: 'Next',
    create: 'Connect',
    creating: 'Connecting…',
    createFailed: "We couldn't connect this source",
    advanced: 'Advanced',
    advancedHint: 'Defaults suit almost everything. Change these only if you know you need to.',
    useExistingAccount: 'Use an existing account',
    addNewAccount: 'Add a new account',
    backfill: 'Also import everything already there',
    backfillHint: 'Off means only items added from now on are indexed.',
  },

  settings: {
    basics: 'Basics',
    schedule: 'Checking schedule',
    scope: "Change what's included",
    scopeWarning:
      'Saving this re-checks the account and rescans the source. It can take a while, and the source pauses until it finishes.',
    chunking: 'Advanced processing',
    name: 'Name',
    connector: 'Connected via',
    connectorFixed: 'Cannot be changed after creation.',
    scheduleCannotClear:
      'A schedule can be changed but not cleared back to the default — pick an explicit option.',
    save: 'Save changes',
    saving: 'Saving…',
    saved: 'Saved',
    readOnly: 'This source has been removed and can no longer be edited.',
  },

  schedule: {
    manual: 'Only when I ask',
    every15m: 'Every 15 minutes',
    hourly: 'Every hour',
    every6h: 'Every 6 hours',
    daily: 'Once a day',
    custom: 'Custom',
  },

  common: {
    cancel: 'Cancel',
    confirm: 'Confirm',
    close: 'Close',
    retry: 'Try again',
    loading: 'Loading…',
    unknown: 'Unknown',
    none: 'None',
    copy: 'Copy',
    copied: 'Copied',
    showMore: 'Show more',
    showLess: 'Show less',
    optional: 'optional',
    required: 'required',
    technical: 'Technical details',
    rawError: 'Raw message',
  },

  errors: {
    unreachableTitle: "Can't reach the server",
    unreachableBody:
      'The backend is not responding. Start it with ./gradlew quarkusDev and make sure MongoDB and OpenSearch are running.',
    genericTitle: 'Something went wrong',
    notFound: 'Not found',
    notFoundBody: 'It may have been removed.',
  },
} as const
