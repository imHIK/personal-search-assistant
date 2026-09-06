/**
 * Places a job posting is commonly filed under, offered as a checkbox list.
 *
 * The backend matches a location by **lowercased substring** on whatever string the board printed
 * (`AtsNormalization.matchesLocation`), and a posting with no location at all is always kept. Three
 * consequences shape this list:
 *
 *  - A country name alone usually misses. Boards file a role as "Bengaluru" with no country, so
 *    "India" on its own drops most Indian roles. Cities are the useful unit; `India` is here only for
 *    the boards that do print it, and is never sufficient on its own.
 *  - A place with more than one accepted name needs every one of them — "bengaluru" does not contain
 *    "bangalore". Those rows carry `values`, so one tick stores the whole group and the trap goes away.
 *  - **An alias that is already a substring of another is redundant**, and one that is short is
 *    dangerous. "New Delhi" contains "delhi" and "Greater Noida" contains "noida", so neither is
 *    listed; "blr", "sf" and "uk" are not listed either, because a two- or three-letter needle
 *    matches inside unrelated words ("uk" hits Fukuoka) and there is no word-boundary check to save it.
 *
 * Values are lowercase because that is what the matcher compares against; the label is what the user
 * reads. Anything not listed can still be typed into the row beside the list.
 */
export interface KnownLocation {
  label: string
  /** Region shown beside the name. Display only. */
  note: string
  /** Stored term, and the group's identity. */
  value: string
  /** Every accepted name, when the place has more than one. Ticking the row stores all of them. */
  values?: string[]
}

export const knownLocations: KnownLocation[] = [
  // India — the alias pairs here are the ones that actually cost roles: a board files a Bengaluru
  // job under either spelling depending on how old the requisition is.
  { label: 'Bengaluru / Bangalore', note: 'India', value: 'bengaluru', values: ['bengaluru', 'bangalore'] },
  { label: 'Delhi / NCR', note: 'India', value: 'delhi', values: ['delhi', 'noida', 'faridabad', 'ghaziabad'] },
  { label: 'Gurugram / Gurgaon', note: 'India', value: 'gurugram', values: ['gurugram', 'gurgaon'] },
  { label: 'Mumbai / Bombay', note: 'India', value: 'mumbai', values: ['mumbai', 'bombay', 'navi mumbai', 'thane'] },
  { label: 'Chennai / Madras', note: 'India', value: 'chennai', values: ['chennai', 'madras'] },
  { label: 'Kolkata / Calcutta', note: 'India', value: 'kolkata', values: ['kolkata', 'calcutta'] },
  { label: 'Hyderabad / Secunderabad', note: 'India', value: 'hyderabad', values: ['hyderabad', 'secunderabad'] },
  { label: 'Kochi / Cochin / Ernakulam', note: 'India', value: 'kochi', values: ['kochi', 'cochin', 'ernakulam'] },
  { label: 'Thiruvananthapuram / Trivandrum', note: 'India', value: 'thiruvananthapuram', values: ['thiruvananthapuram', 'trivandrum'] },
  { label: 'Vadodara / Baroda', note: 'India', value: 'vadodara', values: ['vadodara', 'baroda'] },
  { label: 'Mysuru / Mysore', note: 'India', value: 'mysuru', values: ['mysuru', 'mysore'] },
  { label: 'Visakhapatnam / Vizag', note: 'India', value: 'visakhapatnam', values: ['visakhapatnam', 'vizag'] },
  { label: 'Puducherry / Pondicherry', note: 'India', value: 'puducherry', values: ['puducherry', 'pondicherry'] },
  { label: 'Chandigarh / Mohali', note: 'India', value: 'chandigarh', values: ['chandigarh', 'mohali'] },
  { label: 'Pune', note: 'India', value: 'pune' },
  { label: 'Ahmedabad', note: 'India', value: 'ahmedabad' },
  { label: 'Jaipur', note: 'India', value: 'jaipur' },
  { label: 'Coimbatore', note: 'India', value: 'coimbatore' },
  { label: 'Indore', note: 'India', value: 'indore' },
  { label: 'Nagpur', note: 'India', value: 'nagpur' },
  { label: 'Bhubaneswar', note: 'India', value: 'bhubaneswar' },
  { label: 'India (only where the board prints it)', note: 'India', value: 'india' },

  { label: 'Remote', note: 'Anywhere', value: 'remote', values: ['remote', 'work from home'] },

  { label: 'San Francisco / Bay Area', note: 'United States', value: 'san francisco', values: ['san francisco', 'bay area', 'palo alto', 'mountain view', 'sunnyvale'] },
  { label: 'New York / NYC', note: 'United States', value: 'new york', values: ['new york', 'nyc', 'brooklyn'] },
  { label: 'Seattle / Bellevue', note: 'United States', value: 'seattle', values: ['seattle', 'bellevue', 'redmond'] },
  { label: 'Austin', note: 'United States', value: 'austin' },
  { label: 'Boston / Cambridge, MA', note: 'United States', value: 'boston', values: ['boston', 'cambridge, ma'] },
  { label: 'United States', note: 'United States', value: 'united states', values: ['united states', 'usa'] },
  { label: 'London', note: 'United Kingdom', value: 'london' },
  { label: 'Dublin', note: 'Ireland', value: 'dublin' },
  { label: 'Berlin', note: 'Germany', value: 'berlin' },
  { label: 'Munich / München', note: 'Germany', value: 'munich', values: ['munich', 'münchen', 'muenchen'] },
  { label: 'Amsterdam', note: 'Netherlands', value: 'amsterdam' },
  { label: 'Zurich / Zürich', note: 'Switzerland', value: 'zurich', values: ['zurich', 'zürich'] },
  { label: 'Warsaw / Warszawa', note: 'Poland', value: 'warsaw', values: ['warsaw', 'warszawa'] },
  { label: 'Singapore', note: 'Singapore', value: 'singapore' },
  { label: 'Tokyo', note: 'Japan', value: 'tokyo' },
  { label: 'Toronto', note: 'Canada', value: 'toronto' },
  { label: 'Vancouver', note: 'Canada', value: 'vancouver' },
  { label: 'Sydney', note: 'Australia', value: 'sydney' },
  { label: 'São Paulo / Sao Paulo', note: 'Brazil', value: 'são paulo', values: ['são paulo', 'sao paulo'] },
  { label: 'Tel Aviv', note: 'Israel', value: 'tel aviv' },
]
