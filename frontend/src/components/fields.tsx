import { useId, type InputHTMLAttributes, type ReactNode } from 'react'

export function Field({ label, hint, ...input }: { label: string; hint?: string } & InputHTMLAttributes<HTMLInputElement>) {
  const id = useId()
  return (
    <div>
      <label htmlFor={id} className="text-sm font-medium text-slate-700">{label}</label>
      <input
        {...input}
        id={id}
        aria-describedby={hint ? `${id}-hint` : undefined}
        className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 focus:border-indigo-500 focus:outline-none focus:ring-2 focus:ring-indigo-200"
      />
      {hint && <p id={`${id}-hint`} className="mt-1 text-xs text-slate-500">{hint}</p>}
    </div>
  )
}

export function Alert({ children, kind = 'error' }: { children: ReactNode; kind?: 'error' | 'success' }) {
  const style = kind === 'error' ? 'border-red-200 bg-red-50 text-red-700' : 'border-emerald-200 bg-emerald-50 text-emerald-700'
  return (
    <p role={kind === 'error' ? 'alert' : 'status'} className={`rounded-lg border px-3 py-2 text-sm ${style}`}>
      {children}
    </p>
  )
}

export function Submit({ busy, children }: { busy: boolean; children: ReactNode }) {
  return (
    <button
      type="submit"
      disabled={busy}
      className="w-full rounded-lg bg-indigo-600 px-4 py-2 font-semibold text-white hover:bg-indigo-700 disabled:opacity-60"
    >
      {busy ? 'Aguarde…' : children}
    </button>
  )
}

export function Check({ label, ...input }: { label: string } & InputHTMLAttributes<HTMLInputElement>) {
  return (
    <label className="flex items-center gap-2 text-sm text-slate-700">
      <input type="checkbox" {...input} className="h-4 w-4 rounded border-slate-300 text-indigo-600" />
      {label}
    </label>
  )
}

export function Button({ children, ...props }: React.ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button type="button" {...props} className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50">
      {children}
    </button>
  )
}
