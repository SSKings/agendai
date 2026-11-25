import type { ReactNode } from 'react'

export function AuthCard({ title, children, footer }: { title: string; children: ReactNode; footer: ReactNode }) {
  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-50 px-4">
      <div className="w-full max-w-sm">
        <p className="mb-6 text-center text-2xl font-bold text-indigo-600">Agendaí</p>
        <section className="rounded-2xl bg-white p-6 shadow-sm ring-1 ring-slate-200">
          <h1 className="mb-4 text-xl font-semibold text-slate-900">{title}</h1>
          {children}
        </section>
        <p className="mt-4 text-center text-sm text-slate-600">{footer}</p>
      </div>
    </main>
  )
}
