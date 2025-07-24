package com.deped.studentscanner

enum class TaskStatus { PENDING, RUNNING, SUCCESS, ERROR }

data class TermuxTask(
    val id: Int,
    val command: String,
    var status: TaskStatus = TaskStatus.PENDING,
    var result: String? = null,
    var error: String? = null
)

object TaskManager {
    private val tasks = mutableListOf<TermuxTask>()
    private var nextId = 1

    fun createTask(command: String): TermuxTask {
        val task = TermuxTask(nextId++, command)
        tasks.add(task)
        return task
    }

    fun updateTask(id: Int, status: TaskStatus, result: String? = null, error: String? = null) {
        tasks.find { it.id == id }?.apply {
            this.status = status
            this.result = result
            this.error = error
        }
    }

    fun getTasks(): List<TermuxTask> = tasks.toList()
    fun getTask(id: Int): TermuxTask? = tasks.find { it.id == id }
} 