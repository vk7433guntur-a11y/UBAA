package cn.edu.ubaa.ui.screens.grade

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.ubaa.api.feature.GradeApi
import cn.edu.ubaa.model.dto.GradeData
import cn.edu.ubaa.model.dto.Term
import cn.edu.ubaa.repository.GlobalTermRepository
import cn.edu.ubaa.repository.TermRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GradeViewModel(
    private val gradeApi: GradeApi = GradeApi(),
    private val termRepository: TermRepository = GlobalTermRepository.instance,
) : ViewModel() {
  private var loadedOnce = false

  private val _uiState = MutableStateFlow(GradeUiState())
  val uiState: StateFlow<GradeUiState> = _uiState.asStateFlow()

  fun ensureLoaded(forceRefresh: Boolean = false) {
    if (!forceRefresh && loadedOnce) return
    loadTerms(forceRefresh)
  }

  fun loadTerms(forceRefresh: Boolean = false) {
    loadedOnce = true
    viewModelScope.launch {
      _uiState.value =
          _uiState.value.copy(
              isLoading = true,
              isSummaryLoading = false,
              gradeData = null,
              termGrades = emptyMap(),
              error = null,
          )

      termRepository
          .getTerms(forceRefresh)
          .onSuccess { terms ->
            val selectedTerm = terms.find { it.selected } ?: terms.firstOrNull()
            _uiState.value =
                _uiState.value.copy(
                    isLoading = selectedTerm != null,
                    terms = terms,
                    selectedTerm = selectedTerm,
                    error = null,
                )
            if (selectedTerm == null) {
              _uiState.value = _uiState.value.copy(isLoading = false)
            } else {
              loadAllGrades(terms, selectedTerm)
            }
          }
          .onFailure { exception ->
            _uiState.value =
                _uiState.value.copy(isLoading = false, error = exception.message ?: "加载学期信息失败")
          }
    }
  }

  fun selectTerm(term: Term) {
    if (_uiState.value.selectedTerm != term) {
      val cachedGradeData = _uiState.value.termGrades[term.itemCode]
      _uiState.value =
          _uiState.value.copy(
              selectedTerm = term,
              gradeData = cachedGradeData,
              isLoading = cachedGradeData == null,
              error = null,
          )
      if (cachedGradeData == null) {
        loadGrades(term.itemCode)
      }
    }
  }

  private suspend fun loadAllGrades(terms: List<Term>, selectedTerm: Term) {
    _uiState.value = _uiState.value.copy(isSummaryLoading = terms.size > 1)
    val orderedTerms =
        listOf(selectedTerm) + terms.filterNot { it.itemCode == selectedTerm.itemCode }

    orderedTerms.forEachIndexed { index, term ->
      gradeApi
          .getGrades(term.itemCode)
          .onSuccess { gradeData ->
            val updatedTermGrades = _uiState.value.termGrades + (term.itemCode to gradeData)
            val isSelectedTerm = term.itemCode == _uiState.value.selectedTerm?.itemCode
            _uiState.value =
                _uiState.value.copy(
                    isLoading = if (isSelectedTerm) false else _uiState.value.isLoading,
                    isSummaryLoading = index < orderedTerms.lastIndex,
                    gradeData = if (isSelectedTerm) gradeData else _uiState.value.gradeData,
                    termGrades = updatedTermGrades,
                    error = null,
                )
          }
          .onFailure { exception ->
            val isSelectedTerm = term.itemCode == _uiState.value.selectedTerm?.itemCode
            _uiState.value =
                if (isSelectedTerm) {
                  _uiState.value.copy(
                      isLoading = false,
                      isSummaryLoading = false,
                      error = exception.message ?: "加载成绩信息失败",
                  )
                } else {
                  _uiState.value.copy(isSummaryLoading = false)
                }
            return
          }
    }
    _uiState.value = _uiState.value.copy(isSummaryLoading = false)
  }

  private fun loadGrades(termCode: String) {
    viewModelScope.launch {
      _uiState.value = _uiState.value.copy(isLoading = true, error = null)

      gradeApi
          .getGrades(termCode)
          .onSuccess { gradeData ->
            _uiState.value =
                _uiState.value.copy(
                    isLoading = false,
                    gradeData = gradeData,
                    termGrades = _uiState.value.termGrades + (termCode to gradeData),
                    error = null,
                )
          }
          .onFailure { exception ->
            _uiState.value =
                _uiState.value.copy(isLoading = false, error = exception.message ?: "加载成绩信息失败")
          }
    }
  }
}

data class GradeUiState(
    val isLoading: Boolean = false,
    val isSummaryLoading: Boolean = false,
    val terms: List<Term> = emptyList(),
    val selectedTerm: Term? = null,
    val gradeData: GradeData? = null,
    val termGrades: Map<String, GradeData> = emptyMap(),
    val error: String? = null,
)
