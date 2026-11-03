package cc.gilmore.todoai.data.remote

import cc.gilmore.todoai.data.remote.dto.CreateRoutineRequest
import cc.gilmore.todoai.data.remote.dto.RoutineDto
import cc.gilmore.todoai.data.remote.dto.RoutineListResponse
import cc.gilmore.todoai.data.remote.dto.UpdateRoutineRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/** Matches aws-backend/infra/lib/api-stack.ts's /routines routes exactly — see TodoApiService.kt's doc comment for the path convention. */
interface RoutineApiService {
    @GET("routines")
    suspend fun listRoutines(): Response<RoutineListResponse>

    @GET("routines/{id}")
    suspend fun getRoutine(@Path("id") id: String): Response<RoutineDto>

    @POST("routines")
    suspend fun createRoutine(@Body body: CreateRoutineRequest): Response<RoutineDto>

    @PATCH("routines/{id}")
    suspend fun updateRoutine(@Path("id") id: String, @Body body: UpdateRoutineRequest): Response<RoutineDto>

    @DELETE("routines/{id}")
    suspend fun deleteRoutine(@Path("id") id: String): Response<Unit>
}
