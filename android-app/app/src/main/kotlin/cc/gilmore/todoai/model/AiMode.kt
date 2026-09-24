package cc.gilmore.todoai.model

/**
 * Mirrors the `mode` field in aws-backend/shared/ai-schema.ts's request
 * schema — "cloud" (Bedrock) or "home" (your own Ollama, reached over
 * Telegram). [wireValue] is exactly what the API sends/expects, same
 * convention as PlanScope/ChatSubjectType.
 *
 * HOME is modeled here and persisted by [cc.gilmore.todoai.data.local.AiModeStore],
 * but nothing reachable from the UI can select it yet — the backend
 * rejects `mode: "home"` with a 400 until Phase 3's Telegram bridge +
 * home-bridge-bot exist to actually answer for it (docs/architecture.md
 * §11), so SettingsScreen's Home chip stays `enabled = false` and every
 * request is built with CLOUD regardless of what's stored. This type
 * exists now so that plumbing (storage, AiRepository, Settings) doesn't
 * need to be built again under time pressure once Phase 3 lands — at that
 * point enabling the toggle is a UI-only change.
 */
enum class AiMode(val wireValue: String, val label: String) {
    CLOUD("cloud", "Cloud"),
    HOME("home", "Home");

    companion object {
        fun fromWire(value: String?): AiMode = entries.firstOrNull { it.wireValue == value } ?: CLOUD
    }
}
