package cn.edu.ubaa.ui

import cn.edu.ubaa.model.dto.Grade
import cn.edu.ubaa.model.dto.GradeData
import cn.edu.ubaa.model.dto.Term
import cn.edu.ubaa.ui.screens.grade.GradeDataSource
import cn.edu.ubaa.ui.screens.grade.GradeTermsSource
import cn.edu.ubaa.ui.screens.grade.GradeViewModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class GradeViewModelTest {
  @AfterTest
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `force refresh keeps existing grades visible and exposes refresh state`() = runTest {
    setMainDispatcher(testScheduler)
    val termsSource = FakeGradeTermsSource(listOf(sampleTerm()))
    val gradeSource =
        FakeGradeDataSource(
            mutableMapOf(
                "2025-2026-1" to
                    mutableListOf(
                        GradeData(
                            termCode = "2025-2026-1",
                            grades = listOf(Grade(courseName = "高等数学", score = "90")),
                        ),
                        GradeData(
                            termCode = "2025-2026-1",
                            grades = listOf(Grade(courseName = "高等数学", score = "95")),
                        ),
                    )
            )
        )
    val viewModel = GradeViewModel(gradeSource = gradeSource, termsSource = termsSource)

    viewModel.ensureLoaded()
    advanceUntilIdle()

    assertEquals("90", viewModel.uiState.value.gradeData?.grades?.singleOrNull()?.score)

    viewModel.ensureLoaded(forceRefresh = true)
    runCurrent()

    assertFalse(viewModel.uiState.value.isLoading)
    assertTrue(viewModel.uiState.value.isRefreshing)
    assertEquals("90", viewModel.uiState.value.gradeData?.grades?.singleOrNull()?.score)

    advanceUntilIdle()

    assertFalse(viewModel.uiState.value.isRefreshing)
    assertEquals("95", viewModel.uiState.value.gradeData?.grades?.singleOrNull()?.score)
    assertEquals(listOf(false, true), termsSource.forceRefreshRequests)
  }

  private fun setMainDispatcher(testScheduler: TestCoroutineScheduler) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  }
}

private class FakeGradeTermsSource(private val terms: List<Term>) : GradeTermsSource {
  val forceRefreshRequests = mutableListOf<Boolean>()

  override suspend fun getTerms(forceRefresh: Boolean): Result<List<Term>> {
    forceRefreshRequests += forceRefresh
    return Result.success(terms)
  }
}

private class FakeGradeDataSource(
    private val gradeDataByTerm: MutableMap<String, MutableList<GradeData>>
) : GradeDataSource {
  override suspend fun getGrades(termCode: String): Result<GradeData> {
    delay(100)
    val queue = gradeDataByTerm.getValue(termCode)
    return Result.success(queue.removeAt(0))
  }
}

private fun sampleTerm(): Term =
    Term(
        itemCode = "2025-2026-1",
        itemName = "2025-2026学年第一学期",
        selected = true,
        itemIndex = 0,
    )
