let lastQueryString = ''

/** Remembered by the search page so the Search nav link returns to it. */
export function rememberSearch(queryString: string) {
  lastQueryString = queryString
}

export function lastSearchPath() {
  return lastQueryString ? `/?${lastQueryString}` : '/'
}
