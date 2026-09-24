package cc.gilmore.todoai.data.remote

import cc.gilmore.todoai.data.remote.dto.CreateTodoRequest
import cc.gilmore.todoai.data.remote.dto.RegisterDeviceTokenRequest
import cc.gilmore.todoai.data.remote.dto.TodoDto
import cc.gilmore.todoai.data.remote.dto.TodoListResponse
import cc.gilmore.todoai.data.remote.dto.UpdateTodoRequest
import cc.gilmore.todoai.data.remote.dto.UpdateTodoResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Matches aws-backend/infra/lib/api-stack.ts's routes exactly. Paths have
 * no leading slash — Retrofit resolves them against BuildConfig.API_BASE_URL,
 * which must end in a slash (see app/build.gradle.kts).
 */
interface TodoApiService {
    @GET("todos")
    suspend fun listTodos(@Query("status") status: String? = null): Response<TodoListResponse>

    @GET("todos/{id}")
    suspend fun getTodo(@Path("id") id: String): Response<TodoDto>

    @POST("todos")
    suspend fun createTodo(@Body body: CreateTodoRequest): Response<TodoDto>

    @PATCH("todos/{id}")
    suspend fun updateTodo(@Path("id") id: String, @Body body: UpdateTodoRequest): Response<UpdateTodoResponse>

    @DELETE("todos/{id}")
    suspend fun deleteTodo(@Path("id") id: String): Response<Unit>

    /**
     * NOT implemented server-side yet — see TodoDto.kt's doc comment on
     * RegisterDeviceTokenRequest. Calling this today will 404/fail;
     * TodoRepository.registerDeviceTokenIfNeeded catches that and no-ops
     * rather than crashing, so the rest of the app works fine without it.
     */
    @POST("device/token")
    suspend fun registerDeviceToken(@Body body: RegisterDeviceTokenRequest): Response<Unit>
}
