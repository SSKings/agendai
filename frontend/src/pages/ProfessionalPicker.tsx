import { useId } from 'react'
import type { ProfessionalView } from '../api/types'

export function ProfessionalPicker({ professionals, value, onChange, label = 'Profissional', extra }: {
  professionals: ProfessionalView[]
  value: string
  onChange: (id: string) => void
  label?: string
  extra?: string
}) {
  const id = useId()
  return (
    <div>
      <label htmlFor={id} className="text-sm font-medium text-slate-700">{label}</label>
      <select id={id} value={value} onChange={(e) => onChange(e.target.value)}
        className="mt-1 w-full rounded-lg border border-slate-300 bg-white px-3 py-2">
        {extra !== undefined && <option value="">{extra}</option>}
        {professionals.map((p) => (
          <option key={p.id} value={p.id}>{p.name}{p.active ? '' : ' (inativo)'}</option>
        ))}
      </select>
    </div>
  )
}
