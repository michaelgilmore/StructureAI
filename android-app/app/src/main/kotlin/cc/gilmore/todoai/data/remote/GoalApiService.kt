package cc.gilmore.todoai.data.remote

import cc.gilmore.todoai.data.remote.dto.CreateGoalRequest
import cc.gilmore.todoai.data.remote.dto.GoalDto
import cc.gilmore.todoai.data.remote.dto.GoalListResponse
import cc.gilmore.todoai.data.remote.dto.UpdateGoalRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/** Matches aws-backend/infra/lib/api-stack.ts's /goals routes exactly — see TodoApiService.kt's doc comment for the path convention. */
interface GoalApiService {
    @GET("goals")
    suspend fun listGoals(): Response<GoalListResponse>

    @GET("goals/{id}")
    suspend fun getGoal(@Path("id") id: String): Response<GoalDto>

    @POST("goals")
    suspend fun createGoal(@Body body: CreateGoalRequest): Response<GoalDto>

    @PATCH("goals/{id}")
    suspend fun updateGoal(@Path("id") id: String, @Body body: UpdateGoalRequest): Response<GoalDto>

    @DELETE("goals/{id}")
    suspend fun deleteGoal(@Path("id") id: String): Response<Unit>
}
