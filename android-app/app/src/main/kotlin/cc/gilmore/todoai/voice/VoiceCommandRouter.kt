package cc.gilmore.todoai.voice

import android.util.Log
import cc.gilmore.todoai.data.local.TodoEntity
import cc.gilmore.todoai.model.ChatSubjectType
import cc.gilmore.todoai.model.TodoStatus
import cc.gilmore.todoai.repository.AiRepository
import cc.gilmore.todoai.repository.TodoRepository
import cc.gilmore.todoai.util.DateUtils
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * What to do after routing one voice-first utterance. [reply] is always
 * spoken back to the person (see ui/components/VoiceInputButton.kt's
 * `say`). [continueListening] says whether the session should loop
 * around and ask "What else?" again; when false, the session ends right
 * after speaking [reply] — either because the person said they're done,
 * or because [startPlanning] is true, meaning the caller should navigate
 * into the Planning conversation once [reply] has been spoken (after
 * that, PlannerScreen's own dialog takes over — this loop has nothing
 * further to do).
 */
data class VoiceCommandOutcome(
    val reply: String,
    val continueListening: Boolean,
    val startPlanning: Boolean = false,
)

/**
 * Routes one voice-first utterance to either a small set of fixed local
 * commands, or — for anything that doesn't match one — the same
 * general-purpose chat endpoint `DialogScreen` already uses
 * (`AiRepository.sendChatMessage`, `ChatSubjectType.GENERAL`). See
 * android-app/README.md's "Voice-first" section for why this hybrid split
 * was chosen over routing everything to the AI or recognizing a larger
 * fixed command set.
 *
 * The local commands below exist for things the GENERAL chat endpoint
 * genuinely can't do today (mark a todo complete — there's no
 * `completeTodos` field anywhere in aws-backend/shared/ai-schema.ts's
 * `chatToolOutputSchema`, only `createTodos`/`suggestedDueDateChanges*),
 * things that are a navigation rather than something a chat reply could
 * ever express (starting the Planning conversation), ending this loop
 * (a local control signal, not a backend request), and a quick
 * "what's on today" read-back that's cheap to answer from data already
 * cached in Room, with no AI round trip or network latency needed.
 *
 * Everything else — very much including "add a todo to ..." — falls
 * through to the AI. That's deliberate, not a gap: the AI already creates
 * todos automatically from a chat message server-side (see
 * aws-backend/shared/ai-finalize.ts's `createRequestedTodos` — real
 * DynamoDB writes, "not a preview the app has to separately confirm")
 * with far more flexible natural-language date/detail parsing than a
 * local keyword match could ever manage, so there's no reason to
 * reimplement "add" as a fixed command here.
 *
 * (*If a future pass adds a `completeTodos`/`deleteTodos` field to the
 * chat backend, the local [isCompletePhrase] branch below should either
 * go away or become a fallback for when the AI path is unreachable —
 * this doc comment is the place to update first.)
 *
 * One router instance is meant to be created fresh per voice-first
 * session (see `rememberVoiceCommandLauncher`), so [conversationId]
 * accumulates across every AI-routed turn within that one session,
 * giving the model real multi-turn context ("add one to call the vet"
 * then "actually make that due Friday") without the caller managing that
 * state itself.
 */
class VoiceCommandRouter(
    private val todoRepository: TodoRepository,
    private val aiRepository: AiRepository,
) {
    private var conversationId: String? = null

    suspend fun route(transcript: String): VoiceCommandOutcome {
        val text = transcript.trim()
        return when {
            text.isBlank() ->
                VoiceCommandOutcome("Sorry, I didn't catch that.", continueListening = true)
            isStopPhrase(text) ->
                VoiceCommandOutcome("Okay.", continueListening = false)
            isPlanningPhrase(text) ->
                VoiceCommandOutcome("Let's plan.", continueListening = false, startPlanning = true)
            isCompletePhrase(text) ->
                handleComplete(text)
            isSummaryPhrase(text) ->
                handleSummary()
            else ->
                handleGeneral(text)
        }
    }

    private suspend fun handleComplete(text: String): VoiceCommandOutcome {
        val query = stripCompletePrefix(text)
        if (query.isBlank()) {
            return VoiceCommandOutcome("Which todo would you like to mark done?", continueListening = true)
        }
        val open = todoRepository.observeTodos().first().filter { it.status == TodoStatus.OPEN }
        val match = bestTitleMatch(query, open)
        if (match == null) {
            return VoiceCommandOutcome("I couldn't find an open todo matching \"$query.\"", continueListening = true)
        }
        todoRepository.completeTodo(match.id)
        return VoiceCommandOutcome("Marked \"${match.title}\" as done.", continueListening = true)
    }

    private suspend fun handleSummary(): VoiceCommandOutcome {
        val today = LocalDate.now()
        val open = todoRepository.observeTodos().first().filter { it.status == TodoStatus.OPEN }
        val overdue = open.filter { todo -> DateUtils.parseDueDate(todo.dueDate)?.isBefore(today) == true }
        val dueToday = open.filter { todo -> DateUtils.parseDueDate(todo.dueDate) == today }
        val items = overdue + dueToday
        if (items.isEmpty()) {
            return VoiceCommandOutcome("Nothing due today or overdue. You're all caught up.", continueListening = true)
        }
        val titles = items.take(5).joinToString(", ") { it.title }
        val extra = if (items.size > 5) ", and ${items.size - 5} more" else ""
        val overdueNote = if (overdue.isNotEmpty()) "${overdue.size} overdue. " else ""
        return VoiceCommandOutcome("$overdueNote${items.size} due today or overdue: $titles$extra.", continueListening = true)
    }

    private suspend fun handleGeneral(text: String): VoiceCommandOutcome {
        val result = aiRepository.sendChatMessage(
            subjectType = ChatSubjectType.GENERAL,
            subjectId = null,
            conversationId = conversationId,
            message = text,
        )
        return result.fold(
            onSuccess = { dto ->
                conversationId = dto.conversationId
                // Same upsert TodoRepository.upsertFromServer's doc comment
                // describes for DialogViewModel — a todo the AI just
                // created shows up in Room immediately rather than waiting
                // for the next sync.
                dto.createdTodos.forEach { todoRepository.upsertFromServer(it) }
                VoiceCommandOutcome(dto.reply, continueListening = true)
            },
            onFailure = { e ->
                Log.w(TAG, "handleGeneral: AI request failed", e)
                VoiceCommandOutcome("Sorry, I couldn't reach the assistant just now.", continueListening = true)
            },
        )
    }

    private companion object {
        const val TAG = "TodoAi.VoiceCommandRouter"
    }
}

private val STOP_PHRASES = listOf(
    "nothing else", "that's all", "that is all", "i'm done", "im done", "no thanks",
    "no thank you", "stop", "cancel", "never mind", "nevermind", "that's it", "that is it",
)
private val PLANNING_PHRASES = listOf("start planning", "let's plan", "lets plan", "plan my day", "plan my week", "help me plan")
private val COMPLETE_PREFIXES = listOf(
    "complete ", "mark done ", "mark as done ", "mark complete ", "finish ",
    "i finished ", "i've finished ", "ive finished ", "i'm done with ", "im done with ",
    "check off ", "done with ",
)
private val SUMMARY_PHRASES = listOf(
    "what's due today", "whats due today", "what's on today", "whats on today",
    "what's on my plate", "whats on my plate", "show today", "show my day",
    "what's today look like", "whats today look like", "what do i have today",
    "what's due", "whats due", "anything due",
)

private fun isStopPhrase(text: String): Boolean {
    val lower = text.lowercase().trim().trimEnd('.', '!')
    return STOP_PHRASES.any { lower == it || lower.endsWith(" $it") }
}

private fun isPlanningPhrase(text: String): Boolean {
    val lower = text.lowercase()
    return PLANNING_PHRASES.any { lower.contains(it) }
}

private fun isSummaryPhrase(text: String): Boolean {
    val lower = text.lowercase()
    return SUMMARY_PHRASES.any { lower.contains(it) }
}

private fun isCompletePhrase(text: String): Boolean {
    val lower = text.lowercase()
    return COMPLETE_PREFIXES.any { lower.startsWith(it) }
}

private fun stripCompletePrefix(text: String): String {
    val lower = text.lowercase()
    val prefix = COMPLETE_PREFIXES.firstOrNull { lower.startsWith(it) } ?: return text.trim()
    return text.substring(prefix.length).trim().removeSuffix(".").trim()
}

/**
 * Picks the OPEN todo whose title shares the most whole words with
 * [query] (case-insensitive), requiring at least one shared word — good
 * enough for "finish the quarterly report" to find "Quarterly report"
 * without a full NLP stack, and deliberately local rather than a reason
 * to call out to the AI for a match the on-device list already has the
 * answer to.
 */
private fun bestTitleMatch(query: String, candidates: List<TodoEntity>): TodoEntity? {
    val queryWords = query.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }.toSet()
    if (queryWords.isEmpty()) return null
    return candidates
        .map { todo -> todo to todo.title.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }.toSet() }
        .map { (todo, words) -> todo to queryWords.intersect(words).size }
        .filter { (_, score) -> score > 0 }
        .maxByOrNull { (_, score) -> score }
        ?.first
}
