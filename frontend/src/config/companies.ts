export interface KnownCompany {
  label: string
  handle: string
  /** Display only; never sent to the server. */
  platform: string
}

export const knownCompanies: KnownCompany[] = [
  { label: 'Abnormal Security', handle: 'abnormalsecurity', platform: 'greenhouse' },
  { label: 'Adobe', handle: 'adobe/external_experienced/wd5', platform: 'workday' },
  { label: 'Airbnb', handle: 'airbnb', platform: 'greenhouse' },
  { label: 'Akamai Technologies', handle: 'fa-extu-saasfaprod1.fa.ocs.oraclecloud.com/CX_1', platform: 'oraclehcm' },
  { label: 'American Express', handle: 'egug.fa.us2.oraclecloud.com/CX_1', platform: 'oraclehcm' },
  { label: 'Arcana', handle: 'arcanaanalytics', platform: 'greenhouse' },
  { label: 'Bank of America', handle: 'ghr/lateral-us/wd1', platform: 'workday' },
  { label: 'BNY Mellon', handle: 'eofe.fa.us2.oraclecloud.com/BNY-Careers', platform: 'oraclehcm' },
  { label: 'BrowserStack', handle: 'browserstack/External/wd3', platform: 'workday' },
  { label: 'Canonical', handle: 'canonical', platform: 'greenhouse' },
  { label: 'Celonis', handle: 'celonis', platform: 'greenhouse' },
  { label: 'Cisco', handle: 'cisco/Cisco_Careers/wd5', platform: 'workday' },
  { label: 'Citi', handle: 'citi/2/wd5', platform: 'workday' },
  { label: 'Coinbase', handle: 'coinbase', platform: 'greenhouse' },
  { label: 'Confluent', handle: 'confluent', platform: 'ashby' },
  { label: 'Coupang', handle: 'coupang', platform: 'greenhouse' },
  { label: 'CRED', handle: 'cred', platform: 'lever' },
  { label: 'CrowdStrike', handle: 'crowdstrike/crowdstrikecareers/wd5', platform: 'workday' },
  { label: 'Databricks', handle: 'databricks', platform: 'greenhouse' },
  { label: 'Datadog', handle: 'datadog', platform: 'greenhouse' },
  { label: 'DigiCert', handle: 'digicert', platform: 'greenhouse' },
  { label: 'DigitalOcean', handle: 'digitalocean98', platform: 'greenhouse' },
  { label: 'Discord', handle: 'discord', platform: 'greenhouse' },
  { label: 'Dropbox', handle: 'dropbox', platform: 'greenhouse' },
  { label: 'eBay', handle: 'ebay/apply/wd5', platform: 'workday' },
  { label: 'GitLab', handle: 'gitlab', platform: 'greenhouse' },
  { label: 'Glean', handle: 'glean', platform: 'smartrecruiters' },
  { label: 'Groww', handle: 'groww', platform: 'greenhouse' },
  { label: 'Harness', handle: 'harnessinc', platform: 'greenhouse' },
  { label: 'Hewlett Packard Enterprise', handle: 'hpe/Jobsathpe/wd5', platform: 'workday' },
  { label: 'Imply', handle: 'imply', platform: 'greenhouse' },
  { label: 'InMobi', handle: 'inmobi', platform: 'greenhouse' },
  { label: 'Intel', handle: 'intel/External/wd1', platform: 'workday' },
  { label: 'JPMorgan Chase', handle: 'jpmc.fa.oraclecloud.com/CX_1001', platform: 'oraclehcm' },
  { label: 'Kotak Mahindra Bank', handle: 'hcbt.fa.em2.oraclecloud.com/CX', platform: 'oraclehcm' },
  { label: 'LinkedIn', handle: 'linkedin', platform: 'lever' },
  { label: "Lowe's", handle: 'lowes/LWS_External_CS/wd5', platform: 'workday' },
  { label: 'Mastercard', handle: 'mastercard/CorporateCareers/wd1', platform: 'workday' },
  { label: 'Media.net', handle: 'medianet', platform: 'smartrecruiters' },
  { label: 'Meesho', handle: 'meesho', platform: 'lever' },
  { label: 'Navi', handle: 'navi', platform: 'ashby' },
  { label: 'Notion', handle: 'notion', platform: 'ashby' },
  { label: 'Nvidia', handle: 'nvidia/NVIDIAExternalCareerSite/wd5', platform: 'workday' },
  { label: 'Okta', handle: 'okta', platform: 'greenhouse' },
  { label: 'Oracle', handle: 'eeho.fa.us2.oraclecloud.com/CX_45001', platform: 'oraclehcm' },
  { label: 'Paytm', handle: 'paytm', platform: 'lever' },
  { label: 'Philips', handle: 'philips/jobs-and-careers/wd3', platform: 'workday' },
  { label: 'PhonePe', handle: 'PHONEPELIMITED', platform: 'smartrecruiters' },
  { label: 'Postman', handle: 'postman/careers/wd108', platform: 'workday' },
  { label: 'Pure Storage', handle: 'purestorage', platform: 'ashby' },
  { label: 'Razorpay', handle: 'razorpaysoftwareprivatelimited', platform: 'greenhouse' },
  { label: 'Roblox', handle: 'roblox', platform: 'greenhouse' },
  { label: 'Roku', handle: 'roku', platform: 'greenhouse' },
  { label: 'Rubrik', handle: 'rubrik', platform: 'greenhouse' },
  { label: 'S&P Global', handle: 'spgi/SPGI_Careers/wd5', platform: 'workday' },
  { label: 'Samsung', handle: 'sec/Samsung_Careers/wd3', platform: 'workday' },
  { label: 'ServiceNow', handle: 'servicenow', platform: 'smartrecruiters' },
  { label: 'Slice', handle: 'slice', platform: 'greenhouse' },
  { label: 'Snowflake', handle: 'snowflake', platform: 'ashby' },
  { label: 'Spotify', handle: 'spotify', platform: 'lever' },
  { label: 'Sprinklr', handle: 'sprinklr/careers/wd1', platform: 'workday' },
  { label: 'Stripe', handle: 'stripe', platform: 'greenhouse' },
  { label: 'Swiggy', handle: 'swiggy', platform: 'smartrecruiters' },
  { label: 'Target', handle: 'target/targetcareers/wd5', platform: 'workday' },
  { label: 'Tekion', handle: 'tekion', platform: 'ashby' },
  { label: 'Tower Research Capital', handle: 'towerresearchcapital', platform: 'greenhouse' },
  { label: 'Twilio', handle: 'twilio', platform: 'greenhouse' },
  { label: 'Uber', handle: 'uber', platform: 'smartrecruiters' },
  { label: 'UiPath', handle: 'uipath', platform: 'ashby' },
  { label: 'Visa', handle: 'visa/Visa/wd5', platform: 'workday' },
  { label: 'YugabyteDB', handle: 'yugabyte', platform: 'greenhouse' },
]

export function knownCompany(handle: string): KnownCompany | undefined {
  const needle = handle.trim().toLowerCase()
  return knownCompanies.find((c) => c.handle.toLowerCase() === needle)
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
