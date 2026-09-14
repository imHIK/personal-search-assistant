/**
 * Default role terms for the job-board title filter, offered as checkbox lists.
 *
 * Both lists were tuned against a real corpus — 5,124 India postings pulled from the 71 boards in
 * `companies.ts` — rather than picked for plausibility. Measured effect, in chunks that have to be
 * embedded:
 *
 * | filter                              | postings | chunks |
 * |-------------------------------------|----------|--------|
 * | location terms only                 |    5,124 | 33,832 |
 * | + any engineering word              |    2,478 | 16,835 |
 * | + the exclusions below              |    1,853 | 12,845 |
 * | + a narrow backend/platform include |    1,083 |  7,359 |
 *
 * Two things that corpus taught, both of which shape these lists:
 *
 *  - **The exclusions do as much work as the inclusions.** Dropping manager/director/sales/support
 *    alone took 2,478 postings to 1,853, because a board's non-engineering roles outnumber its
 *    out-of-region ones at most of these companies.
 *  - **Narrow include lists lose real roles.** A backend-flavoured list drops 1,025 clearly technical
 *    postings — `Computer Scientist` (Adobe's name for a backend engineer), `Software Development
 *    Engineer 4` (which does not contain "sde "), `Machine Learning Engineer`, and every one of
 *    Samsung's 57 silicon roles. The generic terms below are the safe default; narrow it by adding
 *    your own, knowing what it costs.
 */
export interface TermOption {
  label: string
  value: string
  note?: string
}

/** Generic enough to keep oddly-named engineering roles. Ticking none keeps everything. */
export const roleIncludeTerms: TermOption[] = [
  { label: 'Engineer', value: 'engineer', note: 'widest' },
  { label: 'Developer', value: 'developer' },
  { label: 'Software', value: 'software' },
  { label: 'SDE', value: 'sde' },
  { label: 'SWE', value: 'swe' },
  { label: 'SSE', value: 'sse', note: 'also hits "assessment"' },
  { label: 'Software Engineer', value: 'software engineer' },
  { label: 'Software Developer', value: 'software developer' },
  { label: 'Software Development', value: 'software development' },
  { label: 'Member of Technical Staff', value: 'member of technical staff' },
  { label: 'Member, Tech', value: 'member, tech', note: 'Oracle/Salesforce style' },
  { label: 'Senior Member, Tech', value: 'senior member, tech' },
  { label: 'MTS', value: 'mts' },
  { label: 'AMTS', value: 'amts' },
  { label: 'Architect', value: 'architect' },
  { label: 'Computer Scientist', value: 'computer scientist', note: 'Adobe et al.' },
  { label: 'Backend', value: 'backend' },
  { label: 'Back end', value: 'back end' },
  { label: 'Full stack', value: 'full stack' },
  { label: 'Platform', value: 'platform' },
  { label: 'Infrastructure', value: 'infrastructure' },
  { label: 'Distributed', value: 'distributed' },
  { label: 'Microservice', value: 'microservice' },
  { label: 'API', value: 'api' },
  { label: 'Java', value: 'java' },
  { label: 'Python', value: 'python' },
  { label: 'Golang', value: 'golang' },
  { label: 'Data engineer', value: 'data engineer' },
  { label: 'Data scientist', value: 'data scientist' },
  { label: 'Machine learning', value: 'machine learning' },
  { label: 'SRE', value: 'sre' },
  { label: 'Site reliability', value: 'site reliability' },
  { label: 'DevOps', value: 'devops' },
  { label: 'Cloud', value: 'cloud' },
  { label: 'Security', value: 'security' },
  { label: 'Mobile', value: 'mobile' },
  { label: 'Android', value: 'android' },
  { label: 'iOS', value: 'ios' },
  { label: 'Frontend', value: 'frontend' },
  { label: 'Front end', value: 'front end' },
]

/** Applied after the include list and beats it — see `BoardFilter`. */
export const roleExcludeTerms: TermOption[] = [
  { label: 'Manager', value: 'manager', note: 'biggest single win' },
  { label: 'Director', value: 'director' },
  { label: 'Vice President', value: 'vice president' },
  { label: 'Head of', value: 'head of' },
  { label: 'Intern', value: 'intern' },
  { label: 'Sales', value: 'sales' },
  { label: 'Account Executive', value: 'account executive' },
  { label: 'Marketing', value: 'marketing' },
  { label: 'Recruiter', value: 'recruit' },
  { label: 'Support', value: 'support' },
  { label: 'Customer Success', value: 'customer success' },
  { label: 'Finance', value: 'finance' },
  { label: 'Legal', value: 'legal' },
  { label: 'Operations', value: 'operations' },
  { label: 'Specialist', value: 'specialist' },
  { label: 'Analyst', value: 'analyst' },
  { label: 'Consultant', value: 'consultant' },
  { label: 'Principal', value: 'principal', note: 'if targeting IC levels' },
  { label: 'Staff', value: 'staff' },
]
