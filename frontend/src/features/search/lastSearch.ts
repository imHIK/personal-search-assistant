let lastQueryString = ''

export function rememberSearch(queryString: string) {
  lastQueryString = queryString
}

export function lastSearchPath() {
  return lastQueryString ? `/?${lastQueryString}` : '/'
}
