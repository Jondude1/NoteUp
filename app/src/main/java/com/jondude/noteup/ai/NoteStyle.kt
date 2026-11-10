package com.jondude.noteup.ai

/** The shape of the AI's meeting notes. Every style ends with an ACTIONS list so action items still reach Tasks. */
enum class NoteStyle(val label: String, val hint: String, val format: String) {
    GENERAL("General", "Summary, key points, decisions", """
SUMMARY
(3-5 sentences)

KEY POINTS
- ...

DECISIONS
- ... (or "- none")

ACTIONS
- [owner if known] task [due: YYYY-MM-DD]   ← add the [due: ...] part only when a date or day is mentioned"""),

    STANDUP("Stand-up", "Done, doing, blocked per person", """
SUMMARY
(1-2 sentences)

DONE
- [person] ...

DOING
- [person] ...

BLOCKED
- [person] ... (or "- none")

ACTIONS
- [owner if known] task [due: YYYY-MM-DD]   ← add the [due: ...] part only when a date or day is mentioned"""),

    ONE_ON_ONE("1:1", "Topics, feedback, follow-ups", """
SUMMARY
(2-4 sentences)

TOPICS DISCUSSED
- ...

FEEDBACK AND CONCERNS
- ... (or "- none")

DECISIONS
- ... (or "- none")

ACTIONS
- [owner if known] task [due: YYYY-MM-DD]   ← add the [due: ...] part only when a date or day is mentioned"""),

    SUPPORT("Support call", "Issue, steps tried, resolution", """
SUMMARY
(2-3 sentences: who called, what was wrong, how it ended)

ISSUE
- what the problem was, product or model, versions if mentioned

STEPS TRIED
- ...

RESOLUTION
- ... (or "- unresolved")

ACTIONS
- [owner if known] follow-up [due: YYYY-MM-DD]   ← add the [due: ...] part only when a date or day is mentioned"""),

    CUSTOMER("Customer call", "Needs, objections, commitments", """
SUMMARY
(3-4 sentences)

NEEDS AND PAIN POINTS
- ...

OBJECTIONS
- ... (or "- none")

COMMITMENTS
- who promised what

ACTIONS
- [owner if known] next step [due: YYYY-MM-DD]   ← add the [due: ...] part only when a date or day is mentioned"""),

    INTERVIEW("Interview", "Questions, answers, impressions", """
SUMMARY
(3-4 sentences)

QUESTIONS AND ANSWERS
- Q: ... A: ...

STRENGTHS
- ...

CONCERNS
- ... (or "- none")

ACTIONS
- [owner if known] next step [due: YYYY-MM-DD]   ← add the [due: ...] part only when a date or day is mentioned"""),

    LECTURE("Lecture", "Concepts, definitions, review questions", """
SUMMARY
(2-3 sentences: the topic and the main takeaway)

KEY CONCEPTS
- ...

DEFINITIONS
- term: meaning (or "- none")

EXAMPLES
- ... (or "- none")

QUESTIONS TO REVIEW
- ...

ACTIONS
- study or homework task [due: YYYY-MM-DD]   ← add the [due: ...] part only when a date or day is mentioned"""),

    BRAINSTORM("Brainstorm", "Ideas grouped, favorites, open questions", """
SUMMARY
(2-3 sentences: the goal and where the group landed)

IDEAS
- grouped by theme, one line each

FAVORITES
- ... (or "- none")

OPEN QUESTIONS
- ... (or "- none")

ACTIONS
- [owner if known] next step [due: YYYY-MM-DD]   ← add the [due: ...] part only when a date or day is mentioned""");

    companion object {
        fun from(name: String?): NoteStyle = entries.firstOrNull { it.name == name } ?: GENERAL
    }
}
