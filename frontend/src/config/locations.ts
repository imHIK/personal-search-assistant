/**
 * The backend matches by lowercased substring (`AtsNormalization.matchesLocation`), so list every
 * spelling of a place, skip an alias that contains another entry, and avoid short needles ("uk"
 * matches Fukuoka).
 */
export interface KnownLocation {
  label: string
  /** Display only. */
  note: string
  value: string
  /** Every accepted name; ticking the row stores all of them. */
  values?: string[]
}

export const knownLocations: KnownLocation[] = [
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
