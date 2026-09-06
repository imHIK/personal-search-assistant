/**
 * Companies known to sit on a supported job board, offered as a checkbox list so nobody has to guess
 * the handle.
 *
 * The old form asked for the name "as it appears in their careers URL", which is a thing a user
 * cannot know without going and looking — and a wrong guess is indistinguishable from a company that
 * simply is not on Greenhouse/Lever/Ashby/SmartRecruiters. Picking from a list removes the guess for
 * the common case; the free-text row beside it still takes anything not listed.
 *
 * `handle` is the bare name, not a pinned `platform:handle`, on purpose: a bare name is re-probed
 * against every platform at discovery, so a company that migrates ATS can be recovered by re-saving
 * the source. `platform` here is display only — it tells the user which board they will be reading,
 * and is not sent anywhere.
 *
 * Workday is the exception, and the reason the catalog earns its keep most: its handle is a
 * `tenant/site/wdN` triple that **cannot be derived from the company name or even from the careers
 * URL**, because large employers front Workday with a vanity domain (Mastercard's job pages live on
 * `careers.mastercard.com`, which names neither the tenant nor the pod). Someone has to read the
 * triple off an Apply link once; putting it here means nobody reads it twice.
 *
 * **Every entry resolved against the live `POST /api/connectors/job-boards/lookup`.** Anything added
 * here must too — an unverified handle looks authoritative and is worse than no list. Note what that
 * check does and does not prove: it proves a board exists under that handle, not that the board
 * belongs to the company on the label. A handle guessed from a truncated name can land on a stranger
 * ("Automated System Design" resolves a board called `automated`), so only full-name handles are
 * listed, and an entry carrying very few postings for a large employer is worth re-checking.
 *
 * Absent by design: employers that run their own careers stack (Amazon, Apple, Google, Meta,
 * Microsoft, Flipkart, Zomato, Myntra, Jio), and everyone on an ATS with no connector yet — iCIMS
 * (Booking.com), Avature (Bloomberg, Delta), Eightfold (Netflix, PayPal, Morgan Stanley),
 * SuccessFactors (HCL), Radancy (Intuit), RippleHire (7-Eleven), TurboHire (Flipkart).
 *
 * Note how little of a handle is guessable, which is the argument for this file existing at all:
 * DigitalOcean's Greenhouse token is `digitalocean98`, Bank of America's Workday tenant is `ghr`,
 * Samsung's is `sec`, and Akamai's Oracle pod is `fa-extu-saasfaprod1.fa.ocs.oraclecloud.com`. Every
 * one had to be read off a live careers page; none could be derived from the company name.
 */
export interface KnownCompany {
  /** What the user sees. */
  label: string
  /** What goes into `inputs.companies`. */
  handle: string
  /** Which board hosted it when it was checked. Shown next to the name; never sent to the server. */
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
  { label: 'Postman', handle: 'postman', platform: 'greenhouse' },
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

/** Catalog entry for `handle`, if it is one we ship. */
export function knownCompany(handle: string): KnownCompany | undefined {
  const needle = handle.trim().toLowerCase()
  return knownCompanies.find((c) => c.handle.toLowerCase() === needle)
}
