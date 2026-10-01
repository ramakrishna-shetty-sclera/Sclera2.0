import type { DefinitionDocument } from '../api/types'

/**
 * A procedure version's content, read-only.
 *
 * Shared by the detail screen and the version viewer, because a published
 * version and a draft render identically — the difference between them is what
 * you may do to them, not what they look like.
 *
 * Question keys are shown deliberately. They are what a stored answer, a rule
 * and a diff all refer to, so seeing them makes "q4 was reworded" legible when
 * you come back to the version history.
 */
export function DefinitionView({ definition }: { definition: DefinitionDocument }) {
  if (definition.categories.length === 0) {
    return <p className="muted">No categories yet.</p>
  }

  return (
    <>
      {definition.categories.map((category, index) => (
        <div className="card" key={category.key ?? `c${index}`}>
          <h2>
            {category.name}
            {category.key && <span className="rt-key-inline"> {category.key}</span>}
          </h2>
          {category.questions.length === 0 ? (
            <p className="muted small">No questions in this category.</p>
          ) : (
            <ol className="question-list">
              {category.questions.map((question, qIndex) => (
                <li key={question.key ?? `q${qIndex}`}>
                  <div className="q-text">
                    {question.text} {question.required && <span className="req">*</span>}
                  </div>
                  <div className="muted small">
                    {question.type}
                    {question.key && <span className="rt-key-inline"> {question.key}</span>}
                  </div>
                  {question.helpText && <div className="muted small">{question.helpText}</div>}
                </li>
              ))}
            </ol>
          )}
        </div>
      ))}
    </>
  )
}
