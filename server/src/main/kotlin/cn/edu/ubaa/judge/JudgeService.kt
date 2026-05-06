package cn.edu.ubaa.judge

import cn.edu.ubaa.model.dto.JudgeAssignmentDetailDto
import cn.edu.ubaa.model.dto.JudgeAssignmentSummaryDto
import cn.edu.ubaa.model.dto.JudgeAssignmentsResponse
import cn.edu.ubaa.utils.withUpstreamDeadline
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 希冀作业业务服务。 */
internal class JudgeService(private val clientProvider: (String) -> JudgeClient = ::JudgeClient) {
  private data class CachedClient(
      val client: JudgeClient,
      @Volatile var lastAccessAt: Long,
  )

  private val clientCache = ConcurrentHashMap<String, CachedClient>()

  suspend fun getAssignments(username: String): JudgeAssignmentsResponse {
    return withJudgeDeadline("希冀作业列表加载超时") {
      withClient(username) { client ->
        val courses = client.getCourses()
        val assignments =
            courses
                .mapConcurrently(JUDGE_ASSIGNMENT_QUERY_CONCURRENCY) { course ->
                  client.withIsolatedClient { worker ->
                    worker.getAssignments(course).map { assignment -> assignment.toSummary() }
                  }
                }
                .flatten()
                .sortedWith(
                    compareBy<JudgeAssignmentSummaryDto> { it.dueTime ?: "9999-99-99 99:99:99" }
                        .thenBy { it.courseName }
                        .thenBy { it.title }
                )
        JudgeAssignmentsResponse(assignments)
      }
    }
  }

  suspend fun getAssignmentDetail(
      username: String,
      courseId: String,
      assignmentId: String,
  ): JudgeAssignmentDetailDto {
    return withJudgeDeadline("希冀作业详情加载超时") {
      withClient(username) { client ->
        val course =
            client.getCourses().firstOrNull { it.courseId == courseId }
                ?: throw JudgeResourceNotFoundException("希冀课程不存在或无权限访问")

        client.withIsolatedClient { worker ->
          val assignment =
              worker.getAssignments(course).firstOrNull { raw -> raw.assignmentId == assignmentId }
                  ?: throw JudgeResourceNotFoundException("希冀作业不存在或无权限访问")

          worker.getAssignmentDetail(
              courseId = assignment.courseId,
              courseName = assignment.courseName,
              assignmentId = assignment.assignmentId,
              title = assignment.title,
          )
        }
      }
    }
  }

  fun cleanupExpiredClients(maxIdleMillis: Long = DEFAULT_MAX_IDLE_MILLIS): Int {
    val cutoff = System.currentTimeMillis() - maxIdleMillis
    var removed = 0
    for ((username, cached) in clientCache.entries.toList()) {
      if (cached.lastAccessAt > cutoff) continue
      if (!clientCache.remove(username, cached)) continue
      cached.client.close()
      removed++
    }
    return removed
  }

  fun cacheSize(): Int = clientCache.size

  fun clearCache() {
    clientCache.values.forEach { it.client.close() }
    clientCache.clear()
  }

  private suspend fun <T> withClient(username: String, block: suspend (JudgeClient) -> T): T {
    val cached = getCachedClient(username)
    cached.lastAccessAt = System.currentTimeMillis()
    return block(cached.client)
  }

  private fun getCachedClient(username: String): CachedClient {
    val now = System.currentTimeMillis()
    return clientCache.compute(username) { _, existing ->
      existing?.also { it.lastAccessAt = now } ?: CachedClient(clientProvider(username), now)
    }!!
  }

  private suspend fun <T> withJudgeDeadline(message: String, block: suspend () -> T): T {
    return withUpstreamDeadline(9.seconds, message, "judge_timeout", block)
  }

  companion object {
    private const val DEFAULT_MAX_IDLE_MILLIS = 30 * 60 * 1000L
    private const val JUDGE_ASSIGNMENT_QUERY_CONCURRENCY = 4
  }
}

private suspend fun <T, R> Iterable<T>.mapConcurrently(
    concurrency: Int,
    transform: suspend (T) -> R,
): List<R> = coroutineScope {
  val semaphore = Semaphore(concurrency)
  map { item -> async { semaphore.withPermit { transform(item) } } }.awaitAll()
}

internal object GlobalJudgeService {
  val instance: JudgeService by lazy { JudgeService() }
}
