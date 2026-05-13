package cn.edu.ubaa.ui

import cn.edu.ubaa.api.feature.ScheduleApi
import cn.edu.ubaa.api.feature.ScheduleApiBackend
import cn.edu.ubaa.model.dto.ExamArrangementData
import cn.edu.ubaa.model.dto.Term
import cn.edu.ubaa.model.dto.TodayClass
import cn.edu.ubaa.model.dto.Week
import cn.edu.ubaa.model.dto.WeeklySchedule
import cn.edu.ubaa.repository.TermRepository
import cn.edu.ubaa.ui.screens.schedule.ScheduleViewModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModelTest {
  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `current week bootstrap retries after terms failure`() = runTest {
    setMainDispatcher(testScheduler)
    val termsBackend =
        FakeScheduleApiBackend(
            termResults =
                mutableListOf(
                    Result.failure(IllegalStateException("terms unavailable")),
                    Result.success(listOf(sampleTerm())),
                )
        )
    val weeksBackend = FakeScheduleApiBackend()
    val viewModel =
        ScheduleViewModel(
            scheduleApi = ScheduleApi { weeksBackend },
            termRepository = TermRepository(ScheduleApi { termsBackend }),
        )

    viewModel.ensureCurrentWeekLoaded()
    advanceUntilIdle()

    assertFalse(viewModel.hasCurrentWeekLoaded())
    assertEquals(1, termsBackend.termCalls)
    assertEquals(0, weeksBackend.weekCalls)

    viewModel.ensureCurrentWeekLoaded()
    advanceUntilIdle()

    assertTrue(viewModel.hasCurrentWeekLoaded())
    assertEquals(2, termsBackend.termCalls)
    assertEquals(1, weeksBackend.weekCalls)
    assertNotNull(viewModel.uiState.value.currentWeek)
  }

  @Test
  fun `current week bootstrap retries after weeks failure`() = runTest {
    setMainDispatcher(testScheduler)
    val termsBackend =
        FakeScheduleApiBackend(termResults = mutableListOf(Result.success(listOf(sampleTerm()))))
    val weeksBackend =
        FakeScheduleApiBackend(
            weekResults =
                mutableListOf(
                    Result.failure(IllegalStateException("weeks unavailable")),
                    Result.success(listOf(sampleWeek())),
                )
        )
    val viewModel =
        ScheduleViewModel(
            scheduleApi = ScheduleApi { weeksBackend },
            termRepository = TermRepository(ScheduleApi { termsBackend }),
        )

    viewModel.ensureCurrentWeekLoaded()
    advanceUntilIdle()

    assertFalse(viewModel.hasCurrentWeekLoaded())
    assertEquals(1, termsBackend.termCalls)
    assertEquals(1, weeksBackend.weekCalls)

    viewModel.ensureCurrentWeekLoaded()
    advanceUntilIdle()

    assertTrue(viewModel.hasCurrentWeekLoaded())
    assertEquals(1, termsBackend.termCalls)
    assertEquals(2, weeksBackend.weekCalls)
    assertEquals(11, viewModel.uiState.value.currentWeek?.serialNumber)
  }

  private fun setMainDispatcher(testScheduler: TestCoroutineScheduler) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  }

  private class FakeScheduleApiBackend(
      private val termResults: MutableList<Result<List<Term>>> =
          mutableListOf(Result.success(listOf(sampleTerm()))),
      private val weekResults: MutableList<Result<List<Week>>> =
          mutableListOf(Result.success(listOf(sampleWeek()))),
  ) : ScheduleApiBackend {
    var termCalls = 0
      private set

    var weekCalls = 0
      private set

    override suspend fun getTerms(): Result<List<Term>> {
      termCalls++
      return termResults.removeFirstOrNull() ?: Result.success(listOf(sampleTerm()))
    }

    override suspend fun getWeeks(termCode: String): Result<List<Week>> {
      weekCalls++
      return weekResults.removeFirstOrNull() ?: Result.success(listOf(sampleWeek()))
    }

    override suspend fun getWeeklySchedule(termCode: String, week: Int): Result<WeeklySchedule> =
        Result.success(WeeklySchedule(arrangedList = emptyList(), code = termCode, name = "课表"))

    override suspend fun getTodaySchedule(): Result<List<TodayClass>> = Result.success(emptyList())

    override suspend fun getExamArrangement(termCode: String): Result<ExamArrangementData> =
        Result.success(ExamArrangementData())
  }

  companion object {
    private fun sampleTerm(): Term =
        Term(
            itemCode = "2025-2026-2",
            itemName = "2025-2026学年第二学期",
            selected = true,
            itemIndex = 1,
        )

    private fun sampleWeek(): Week =
        Week(
            startDate = "2026-03-23",
            endDate = "2026-03-29",
            term = "2025-2026-2",
            curWeek = true,
            serialNumber = 11,
            name = "第11周",
        )
  }
}
