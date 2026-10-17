import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import type { CompanyTier, WatchedCompany, WatchedCompanyBody } from '@/api/types'
import { Button } from '@/components/ui/Button'
import { ConfirmDialog, Dialog } from '@/components/ui/Dialog'
import { Field, Input, Select, Textarea } from '@/components/ui/Input'
import { friendlyError } from '@/config/errors'
import { jobDashboard } from '@/config/jobDashboard'
import { labels } from '@/config/labels'
import { companyTierOrder, knownTier, presentCompanyTier } from '@/config/presentation'
import { useWatchedCompanyMutations } from '@/hooks/queries'

const t = labels.unsupported
const f = t.fields

type Form = Required<{ [K in keyof WatchedCompanyBody]: string }>

const EMPTY: Form = {
  name: '',
  domain: '',
  careersUrl: '',
  platform: '',
  tier: jobDashboard.defaultTier,
  reason: '',
  note: '',
  lookupHandle: '',
}

function formOf(company: WatchedCompany | null): Form {
  if (!company) return EMPTY
  return {
    name: company.name,
    domain: company.domain ?? '',
    careersUrl: company.careersUrl ?? '',
    platform: company.platform ?? '',
    tier: knownTier(company.tier) ?? '',
    reason: company.reason ?? '',
    note: company.note ?? '',
    lookupHandle: company.lookupHandle ?? '',
  }
}

/** `company` null adds a new one. */
export function WatchedCompanyDialog({
  company,
  open,
  onOpenChange,
}: {
  company: WatchedCompany | null
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const [form, setForm] = useState<Form>(EMPTY)
  const [confirmRemove, setConfirmRemove] = useState(false)
  const { create, patch, remove } = useWatchedCompanyMutations()

  useEffect(() => {
    if (open) setForm(formOf(company))
  }, [open, company])

  const field = (key: keyof Form) => ({
    id: `watched-${key}`,
    value: form[key],
    onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) =>
      setForm((prev) => ({ ...prev, [key]: e.target.value })),
  })

  const onError = (cause: unknown) => toast.error(t.saveFailed, { description: friendlyError(cause).detail })
  const close = () => onOpenChange(false)

  // Every field is sent on edit: "" is how PATCH clears one. The tier cannot be cleared, so a company
  // with none omits it and stays untiered for the next import to fill.
  const save = () => {
    const body: WatchedCompanyBody = { ...form, tier: (form.tier || undefined) as CompanyTier | undefined }
    if (company) patch.mutate({ id: company.id, body }, { onSuccess: close, onError })
    else create.mutate(body, { onSuccess: close, onError })
  }

  return (
    <>
      <Dialog
        open={open}
        onOpenChange={onOpenChange}
        title={company ? t.editTitle(company.name) : t.addTitle}
        footer={
          <>
            {company && (
              <Button variant="dangerGhost" className="mr-auto" onClick={() => setConfirmRemove(true)}>
                {labels.common.remove}
              </Button>
            )}
            <Button variant="ghost" onClick={close}>
              {labels.common.cancel}
            </Button>
            <Button
              variant="primary"
              disabled={!form.name.trim()}
              loading={create.isPending || patch.isPending}
              onClick={save}
            >
              {labels.common.save}
            </Button>
          </>
        }
      >
        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault()
            if (form.name.trim()) save()
          }}
        >
          <Field label={f.name} htmlFor="watched-name" required>
            <Input {...field('name')} autoFocus />
          </Field>
          <Field label={f.careersUrl} htmlFor="watched-careersUrl" hint={f.careersUrlHint}>
            <Input {...field('careersUrl')} type="url" placeholder="https://" />
          </Field>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label={f.tier} htmlFor="watched-tier">
              <Select {...field('tier')}>
                {!form.tier && <option value="">{presentCompanyTier(null).label}</option>}
                {companyTierOrder.map((tier) => (
                  <option key={tier} value={tier}>
                    {presentCompanyTier(tier).label}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label={f.platform} htmlFor="watched-platform">
              <Input {...field('platform')} />
            </Field>
            <Field label={f.domain} htmlFor="watched-domain" hint={f.domainHint}>
              <Input {...field('domain')} />
            </Field>
          </div>
          <Field label={f.reason} htmlFor="watched-reason">
            <Input {...field('reason')} />
          </Field>
          <Field label={f.note} htmlFor="watched-note">
            <Textarea {...field('note')} rows={3} />
          </Field>
          <Field label={f.lookupHandle} htmlFor="watched-lookupHandle" hint={f.lookupHandleHint}>
            <Input {...field('lookupHandle')} />
          </Field>
          <button type="submit" hidden />
        </form>
      </Dialog>

      {company && (
        <ConfirmDialog
          open={confirmRemove}
          onOpenChange={setConfirmRemove}
          title={t.removeTitle(company.name)}
          description={t.removeBody}
          confirmLabel={labels.common.remove}
          loading={remove.isPending}
          onConfirm={() =>
            remove.mutate(company.id, {
              onSuccess: () => {
                setConfirmRemove(false)
                close()
              },
              onError,
            })
          }
        />
      )}
    </>
  )
}
