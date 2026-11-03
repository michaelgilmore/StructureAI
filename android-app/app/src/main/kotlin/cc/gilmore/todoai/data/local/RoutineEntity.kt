package cc.gilmore.todoai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import cc.gilmore.todoai.model.RoutinePeriod
import cc.gilmore.todoai.model.RoutineStatus

/**
 * The local cache AND the offline outbox for routines — same combined-row
 * design as TodoEntity/GoalEntity (see TodoEntity's doc comment). A
 * routine itself carries no progress count; that's derived, per the
 * current period, from how many TodoEntity rows have this routine's id in
 * [TodoEntity.routineId] and status DONE with a completedAt inside the
 * period — see RoutineRepository/ui/goals' doneCount computation (and
 * the server-side mirror in aws-backend/lambdas/ai-orchestrator/index.ts's
 * routineProgress, which is what actually drives the AI's reminders).
 */
@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey val id: String,
    val title: String,
    val notes: String?,
    val targetCount: Int,
    val period: RoutinePeriod,
    val status: RoutineStatus,
    val createdAt: String,
    val updatedAt: String,
    val syncState: SyncState,
)
