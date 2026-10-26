import { useEffect, useState } from "react"
import { futureApi, type EducationIndex, type LessonDetail } from "../../lib/futureApi"
import { describeFutureError } from "../../lib/futureLogic"
import { ErrorState } from "../../components/ui/StatePanel"

export function LearnTab() {
  const [index, setIndex] = useState<EducationIndex | null>(null)
  const [open, setOpen] = useState<LessonDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const load = () => futureApi.education().then(setIndex).catch((e) => setError(describeFutureError(e)))
  useEffect(() => { load() }, [])

  async function openLesson(id: string) {
    try { setOpen(await futureApi.lesson(id)) } catch (e) { setError(describeFutureError(e)) }
  }
  if (error) return <ErrorState description={error} onRetry={() => { setError(null); load() }} />
  if (!index) return <div className="spinner" role="status" aria-label="Loading" />

  if (open) {
    const l = open.lesson
    return (
      <div className="card stack">
        <button className="btn btn-ghost btn-sm" onClick={() => setOpen(null)}>← All lessons</button>
        <h2>{l.title}</h2>
        {l.body.map((p, i) => <p key={i}>{p}</p>)}
        <div className="card"><strong>Try it:</strong> {l.tryIt}</div>
        <div className="field-hint">{open.disclaimer}</div>
      </div>
    )
  }
  return (
    <div className="stack">
      <div className="field-hint">{index.disclaimer}</div>
      {index.lessons.map((l) => (
        <button key={l.id} className="card card-interactive stack" style={{ textAlign: "left" }} onClick={() => openLesson(l.id)}>
          <strong>{l.title}</strong>
          <span className="badge badge-muted">{l.level.toLowerCase()}</span>
          <span>{l.summary}</span>
        </button>
      ))}
      <div className="card stack">
        <h3>Glossary</h3>
        <dl>{index.glossary.map((t) => <div key={t.term}><dt><strong>{t.term}</strong></dt><dd>{t.meaning}</dd></div>)}</dl>
      </div>
    </div>
  )
}
