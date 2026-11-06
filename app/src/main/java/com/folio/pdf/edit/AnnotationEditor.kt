package com.folio.pdf.edit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EditorState(
    val annotations: List<Annotation> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** True when there are changes that haven't been saved to the PDF. */
    val dirty: Boolean = false,
)

/**
 * Holds the document's annotations with snapshot-based undo/redo.
 * Every change produces a new immutable list, so undo is just "go back one list".
 * Call from the main thread.
 */
class AnnotationEditor {
    private val undoStack = ArrayDeque<List<Annotation>>()
    private val redoStack = ArrayDeque<List<Annotation>>()
    private var saved: List<Annotation> = emptyList()
    private var gestureStart: List<Annotation>? = null

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val current: List<Annotation> get() = _state.value.annotations

    /** Replace everything (e.g. annotations read from the file); clears history. */
    fun load(list: List<Annotation>) {
        undoStack.clear()
        redoStack.clear()
        saved = list
        publish(list)
    }

    fun add(a: Annotation) = commit(current + a)

    fun remove(id: Long) = commit(current.filterNot { it.id == id })

    fun update(a: Annotation) = commit(current.map { if (it.id == a.id) a else it })

    fun find(id: Long?): Annotation? = id?.let { i -> current.firstOrNull { it.id == i } }

    // A drag (move/resize/erase) changes the list many times but is ONE undo step.
    fun beginGesture() {
        if (gestureStart == null) gestureStart = current
    }

    fun updateDuringGesture(a: Annotation) {
        beginGesture()
        publish(current.map { if (it.id == a.id) a else it })
    }

    fun removeDuringGesture(id: Long) {
        beginGesture()
        publish(current.filterNot { it.id == id })
    }

    fun endGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        if (start != current) {
            push(start)
            redoStack.clear()
            publish(current)
        }
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current)
        publish(previous)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        push(current)
        publish(next)
    }

    fun markSaved() {
        saved = current
        publish(current)
    }

    private fun commit(next: List<Annotation>) {
        if (next == current) return
        push(current)
        redoStack.clear()
        publish(next)
    }

    private fun push(list: List<Annotation>) {
        undoStack.addLast(list)
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
    }

    private fun publish(list: List<Annotation>) {
        _state.value = EditorState(
            annotations = list,
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
            dirty = list != saved,
        )
    }

    private companion object {
        const val MAX_HISTORY = 100
    }
}
