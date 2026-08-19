package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.ProjectEntity
import com.example.data.db.TaskEntity
import com.example.data.di.WastiServiceLocator
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Specialized ViewModel for Projects, Tasks, and Code Workspace coordination.
 */
class ProjectViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = WastiServiceLocator.repository

    val projects: StateFlow<List<ProjectEntity>> = repository.projects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTasks: StateFlow<List<TaskEntity>> = repository.allTasks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addProject(name: String, description: String, priority: String) {
        viewModelScope.launch {
            try {
                repository.addProject(name, description, priority)
            } catch (e: Exception) {
                android.util.Log.e("ProjectViewModel", "Add project failed", e)
            }
        }
    }

    fun addTask(projectId: String, title: String, description: String, assignedAgentId: String, priority: String) {
        viewModelScope.launch {
            try {
                repository.addTask(projectId, title, description, assignedAgentId, priority)
            } catch (e: Exception) {
                android.util.Log.e("ProjectViewModel", "Add task failed", e)
            }
        }
    }

    fun toggleTaskStatus(taskId: String, currentStatus: Boolean) {
        viewModelScope.launch {
            try {
                repository.toggleTaskStatus(taskId, currentStatus)
            } catch (e: Exception) {
                android.util.Log.e("ProjectViewModel", "Toggle task status failed", e)
            }
        }
    }
}
