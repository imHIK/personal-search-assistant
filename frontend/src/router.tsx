import { createBrowserRouter } from 'react-router-dom'
import { Layout } from '@/components/Layout'
import { RouteError } from '@/components/RouteError'
import { AccountFormPage } from '@/features/accounts/AccountFormPage'
import { AccountsPage } from '@/features/accounts/AccountsPage'
import { SearchPage } from '@/features/search/SearchPage'
import { SourceDetailPage } from '@/features/sources/SourceDetailPage'
import { SourcesPage } from '@/features/sources/SourcesPage'
import { AddSourcePage } from '@/features/sources/AddSourcePage'

/**
 * Routes keep the domain paths (`/knowledge`, `/connections`) so they line up with the API and
 * with anything already bookmarked; only the labels differ. See config/labels.ts.
 */
export const router = createBrowserRouter([
  {
    path: '/',
    element: <Layout />,
    errorElement: <RouteError />,
    children: [
      { index: true, element: <SearchPage /> },
      { path: 'knowledge', element: <SourcesPage /> },
      { path: 'knowledge/new', element: <AddSourcePage /> },
      { path: 'knowledge/:id', element: <SourceDetailPage /> },
      { path: 'connections', element: <AccountsPage /> },
      { path: 'connections/new', element: <AccountFormPage /> },
      { path: 'connections/:id', element: <AccountFormPage /> },
    ],
  },
])
