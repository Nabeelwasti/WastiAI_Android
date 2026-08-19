package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.KnowledgeEntity
import com.example.data.db.MemoryEntity
import com.example.data.di.WastiServiceLocator
import com.example.data.memory.MemoryManager
import com.example.data.memory.model.MemoryItem
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Specialized ViewModel for Memory Store, Knowledge Graph, and Semantic Search.
 */
class MemoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = WastiServiceLocator.repository

    val memories: StateFlow<List<MemoryEntity>> = repository.memories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val knowledge: StateFlow<List<KnowledgeEntity>> = repository.knowledge
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val semanticMemories: StateFlow<List<MemoryItem>> = MemoryManager.memoriesFlow

    fun addMemory(key: String, category: String, value: String) {
        viewModelScope.launch {
            try {
                repository.addMemory(key, category, value)
                MemoryManager.saveMemory(key, category, value)
            } catch (e: Exception) {
                android.util.Log.e("MemoryViewModel", "Add memory failed", e)
            }
        }
    }

    fun updateMemory(id: String, key: String, category: String, value: String) {
        viewModelScope.launch {
            try {
                repository.updateMemory(id, key, category, value)
                MemoryManager.saveMemory(key, category, value)
            } catch (e: Exception) {
                android.util.Log.e("MemoryViewModel", "Update memory failed", e)
            }
        }
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch {
            try {
                repository.deleteMemory(id)
                MemoryManager.deleteMemory(id)
            } catch (e: Exception) {
                android.util.Log.e("MemoryViewModel", "Delete memory failed", e)
            }
        }
    }

    fun addKnowledge(title: String, category: String, content: String, tags: String) {
        viewModelScope.launch {
            try {
                repository.addKnowledge(title, category, content, tags)
            } catch (e: Exception) {
                android.util.Log.e("MemoryViewModel", "Add knowledge failed", e)
            }
        }
    }

    fun updateKnowledge(id: String, title: String, category: String, content: String, tags: String) {
        viewModelScope.launch {
            try {
                repository.updateKnowledge(id, title, category, content, tags)
            } catch (e: Exception) {
                android.util.Log.e("MemoryViewModel", "Update knowledge failed", e)
            }
        }
    }

    fun deleteKnowledge(id: String) {
        viewModelScope.launch {
            try {
                repository.deleteKnowledge(id)
            } catch (e: Exception) {
                android.util.Log.e("MemoryViewModel", "Delete knowledge failed", e)
            }
        }
    }
}
