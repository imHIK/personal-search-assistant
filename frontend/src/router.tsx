import { createBrowserRouter } from 'react-router-dom'
import { Layout } from '@/components/Layout'
import { RouteError } from '@/components/RouteError'
import { AccountFormPage } from '@/features/accounts/AccountFormPage'
import { AccountsPage } from '@/features/accounts/AccountsPage'
import { ChannelFormPage } from '@/features/channels/ChannelFormPage'
import { ChannelsPage } from '@/features/channels/ChannelsPage'
import { DigestDetailPage } from '@/features/digests/DigestDetailPage'
import { DigestsPage } from '@/features/digests/DigestsPage'
import { JobsDashboardPage } from '@/features/jobs/JobsDashboardPage'
import { SearchPage } from '@/features/search/SearchPage'
import { SourceDetailPage } from '@/features/sources/SourceDetailPage'
import { SourcesPage } from '@/features/sources/SourcesPage'
import { AddSourcePage } from '@/features/sources/AddSourcePage'
import { TaskFormPage } from '@/features/tasks/TaskFormPage'
import { TasksPage } from '@/features/tasks/TasksPage'

/**
 * Paths keep the domain names (`/knowledge`, `/connections`) to match the API; only the labels
 * differ.
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
      { path: 'digests', element: <DigestsPage /> },
      { path: 'digests/:id', element: <DigestDetailPage /> },
      { path: 'tasks', element: <TasksPage /> },
      { path: 'tasks/new', element: <TaskFormPage /> },
      { path: 'tasks/:id', element: <TaskFormPage /> },
      { path: 'connections', element: <AccountsPage /> },
      { path: 'connections/new', element: <AccountFormPage /> },
      { path: 'connections/:id', element: <AccountFormPage /> },
      { path: 'channels', element: <ChannelsPage /> },
      { path: 'channels/new', element: <ChannelFormPage /> },
      { path: 'channels/:id', element: <ChannelFormPage /> },
    ],
  },
  // Outside the Layout route on purpose: a standalone page with no nav entry.
  { path: '/jobs', element: <JobsDashboardPage />, errorElement: <RouteError /> },
])
