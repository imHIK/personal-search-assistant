export interface KnownCompany {
  label: string
  handle: string
  /** Display only; never sent to the server. */
  platform: string
  /** The company's own site, for its logo. Absent shows a monogram. */
  domain?: string
}

export const knownCompanies: KnownCompany[] = [
  { label: 'Abnormal Security', handle: 'abnormalsecurity', platform: 'greenhouse', domain: 'abnormalsecurity.com' },
  { label: 'Adobe', handle: 'adobe/external_experienced/wd5', platform: 'workday', domain: 'adobe.com' },
  { label: 'Airbnb', handle: 'airbnb', platform: 'greenhouse', domain: 'airbnb.com' },
  { label: 'Akamai Technologies', handle: 'fa-extu-saasfaprod1.fa.ocs.oraclecloud.com/CX_1', platform: 'oraclehcm', domain: 'akamai.com' },
  { label: 'American Express', handle: 'egug.fa.us2.oraclecloud.com/CX_1', platform: 'oraclehcm', domain: 'americanexpress.com' },
  { label: 'Arcana', handle: 'arcanaanalytics', platform: 'greenhouse' },
  { label: 'Bank of America', handle: 'ghr/lateral-us/wd1', platform: 'workday', domain: 'bankofamerica.com' },
  { label: 'BNY Mellon', handle: 'eofe.fa.us2.oraclecloud.com/BNY-Careers', platform: 'oraclehcm', domain: 'bny.com' },
  { label: 'BrowserStack', handle: 'browserstack/External/wd3', platform: 'workday', domain: 'browserstack.com' },
  { label: 'Canonical', handle: 'canonical', platform: 'greenhouse', domain: 'canonical.com' },
  { label: 'Celonis', handle: 'celonis', platform: 'greenhouse', domain: 'celonis.com' },
  { label: 'Cisco', handle: 'cisco/Cisco_Careers/wd5', platform: 'workday', domain: 'cisco.com' },
  { label: 'Citi', handle: 'citi/2/wd5', platform: 'workday', domain: 'citi.com' },
  { label: 'Coinbase', handle: 'coinbase', platform: 'greenhouse', domain: 'coinbase.com' },
  { label: 'Confluent', handle: 'confluent', platform: 'ashby', domain: 'confluent.io' },
  { label: 'Coupang', handle: 'coupang', platform: 'greenhouse', domain: 'coupang.com' },
  { label: 'CRED', handle: 'cred', platform: 'lever', domain: 'cred.club' },
  { label: 'CrowdStrike', handle: 'crowdstrike/crowdstrikecareers/wd5', platform: 'workday', domain: 'crowdstrike.com' },
  { label: 'Databricks', handle: 'databricks', platform: 'greenhouse', domain: 'databricks.com' },
  { label: 'Datadog', handle: 'datadog', platform: 'greenhouse', domain: 'datadoghq.com' },
  { label: 'DigiCert', handle: 'digicert', platform: 'greenhouse', domain: 'digicert.com' },
  { label: 'DigitalOcean', handle: 'digitalocean98', platform: 'greenhouse', domain: 'digitalocean.com' },
  { label: 'Discord', handle: 'discord', platform: 'greenhouse', domain: 'discord.com' },
  { label: 'Dropbox', handle: 'dropbox', platform: 'greenhouse', domain: 'dropbox.com' },
  { label: 'eBay', handle: 'ebay/apply/wd5', platform: 'workday', domain: 'ebay.com' },
  { label: 'GitLab', handle: 'gitlab', platform: 'greenhouse', domain: 'gitlab.com' },
  { label: 'Glean', handle: 'glean', platform: 'smartrecruiters', domain: 'glean.com' },
  { label: 'Groww', handle: 'groww', platform: 'greenhouse', domain: 'groww.in' },
  { label: 'Harness', handle: 'harnessinc', platform: 'greenhouse', domain: 'harness.io' },
  { label: 'Hewlett Packard Enterprise', handle: 'hpe/Jobsathpe/wd5', platform: 'workday', domain: 'hpe.com' },
  { label: 'Imply', handle: 'imply', platform: 'greenhouse', domain: 'imply.io' },
  { label: 'InMobi', handle: 'inmobi', platform: 'greenhouse', domain: 'inmobi.com' },
  { label: 'Intel', handle: 'intel/External/wd1', platform: 'workday', domain: 'intel.com' },
  { label: 'JPMorgan Chase', handle: 'jpmc.fa.oraclecloud.com/CX_1001', platform: 'oraclehcm', domain: 'jpmorganchase.com' },
  { label: 'Kotak Mahindra Bank', handle: 'hcbt.fa.em2.oraclecloud.com/CX', platform: 'oraclehcm', domain: 'kotak.com' },
  { label: 'LinkedIn', handle: 'linkedin', platform: 'lever', domain: 'linkedin.com' },
  { label: "Lowe's", handle: 'lowes/LWS_External_CS/wd5', platform: 'workday', domain: 'lowes.com' },
  { label: 'Mastercard', handle: 'mastercard/CorporateCareers/wd1', platform: 'workday', domain: 'mastercard.com' },
  { label: 'Media.net', handle: 'medianet', platform: 'smartrecruiters', domain: 'media.net' },
  { label: 'Meesho', handle: 'meesho', platform: 'lever', domain: 'meesho.com' },
  { label: 'Navi', handle: 'navi', platform: 'ashby', domain: 'navi.com' },
  { label: 'Notion', handle: 'notion', platform: 'ashby', domain: 'notion.so' },
  { label: 'Nvidia', handle: 'nvidia/NVIDIAExternalCareerSite/wd5', platform: 'workday', domain: 'nvidia.com' },
  { label: 'Okta', handle: 'okta', platform: 'greenhouse', domain: 'okta.com' },
  { label: 'Oracle', handle: 'eeho.fa.us2.oraclecloud.com/CX_45001', platform: 'oraclehcm', domain: 'oracle.com' },
  { label: 'Paytm', handle: 'paytm', platform: 'lever', domain: 'paytm.com' },
  { label: 'Philips', handle: 'philips/jobs-and-careers/wd3', platform: 'workday', domain: 'philips.com' },
  { label: 'PhonePe', handle: 'PHONEPELIMITED', platform: 'smartrecruiters', domain: 'phonepe.com' },
  { label: 'Postman', handle: 'postman/careers/wd108', platform: 'workday', domain: 'postman.com' },
  { label: 'Pure Storage', handle: 'purestorage', platform: 'ashby', domain: 'purestorage.com' },
  { label: 'Razorpay', handle: 'razorpaysoftwareprivatelimited', platform: 'greenhouse', domain: 'razorpay.com' },
  { label: 'Roblox', handle: 'roblox', platform: 'greenhouse', domain: 'roblox.com' },
  { label: 'Roku', handle: 'roku', platform: 'greenhouse', domain: 'roku.com' },
  { label: 'Rubrik', handle: 'rubrik', platform: 'greenhouse', domain: 'rubrik.com' },
  { label: 'S&P Global', handle: 'spgi/SPGI_Careers/wd5', platform: 'workday', domain: 'spglobal.com' },
  { label: 'Samsung', handle: 'sec/Samsung_Careers/wd3', platform: 'workday', domain: 'samsung.com' },
  { label: 'ServiceNow', handle: 'servicenow', platform: 'smartrecruiters', domain: 'servicenow.com' },
  { label: 'Slice', handle: 'slice', platform: 'greenhouse', domain: 'sliceit.com' },
  { label: 'Snowflake', handle: 'snowflake', platform: 'ashby', domain: 'snowflake.com' },
  { label: 'Spotify', handle: 'spotify', platform: 'lever', domain: 'spotify.com' },
  { label: 'Sprinklr', handle: 'sprinklr/careers/wd1', platform: 'workday', domain: 'sprinklr.com' },
  { label: 'Stripe', handle: 'stripe', platform: 'greenhouse', domain: 'stripe.com' },
  { label: 'Swiggy', handle: 'swiggy', platform: 'smartrecruiters', domain: 'swiggy.com' },
  { label: 'Target', handle: 'target/targetcareers/wd5', platform: 'workday', domain: 'target.com' },
  { label: 'Tekion', handle: 'tekion', platform: 'ashby', domain: 'tekion.com' },
  { label: 'Tower Research Capital', handle: 'towerresearchcapital', platform: 'greenhouse', domain: 'tower-research.com' },
  { label: 'Twilio', handle: 'twilio', platform: 'greenhouse', domain: 'twilio.com' },
  { label: 'Uber', handle: 'uber', platform: 'smartrecruiters', domain: 'uber.com' },
  { label: 'UiPath', handle: 'uipath', platform: 'ashby', domain: 'uipath.com' },
  { label: 'Visa', handle: 'visa/Visa/wd5', platform: 'workday', domain: 'visa.com' },
  { label: 'YugabyteDB', handle: 'yugabyte', platform: 'greenhouse', domain: 'yugabyte.com' },
]

export function knownCompany(handle: string): KnownCompany | undefined {
  const needle = handle.trim().toLowerCase()
  return knownCompanies.find((c) => c.handle.toLowerCase() === needle)
}

const normalize = (name: string) => name.toLowerCase().replace(/[^a-z0-9]/g, '')

/**
 * A posting's `metadata.company` is the user's label, the board's own name or, failing both, a bare
 * handle segment (`CX_1001`); each is tried against the known list.
 */
export function companyFor(name: string | null | undefined): KnownCompany | undefined {
  if (!name) return undefined
  const needle = normalize(name)
  if (!needle) return undefined
  const exact = knownCompanies.find(
    (c) => normalize(c.label) === needle || normalize(c.handle) === needle,
  )
  if (exact) return exact
  // A segment is only trusted when it is unique: two Oracle tenants both end in CX_1.
  const bySegment = knownCompanies.filter((c) => {
    const segments = c.handle.split('/')
    return normalize(segments[0]) === needle || normalize(segments[segments.length - 1]) === needle
  })
  return bySegment.length === 1 ? bySegment[0] : undefined
}

/**
 * Loaded by the browser straight from DuckDuckGo, so it sees which domains are looked up. It answers
 * 404 for an unknown domain, which is what lets the icon fall back to a monogram.
 */
export function companyLogoUrl(domain: string): string {
  return `https://icons.duckduckgo.com/ip3/${encodeURIComponent(domain)}.ico`
}

export function withCompanyLabels(
  inputs: Record<string, unknown>,
  previous?: Record<string, unknown>,
): Record<string, unknown> {
  const companies = Array.isArray(inputs.companies) ? (inputs.companies as string[]) : []
  const kept = (previous?.companyLabels ?? {}) as Record<string, unknown>
  const companyLabels: Record<string, string> = {}
  for (const company of companies) {
    const label =
      knownCompany(company)?.label ??
      knownCompany(company.slice(company.indexOf(':') + 1))?.label ??
      (typeof kept[company] === 'string' ? (kept[company] as string) : undefined)
    if (label) companyLabels[company] = label
  }
  const out = { ...inputs }
  delete out.companyLabels
  if (Object.keys(companyLabels).length > 0) out.companyLabels = companyLabels
  return out
}
