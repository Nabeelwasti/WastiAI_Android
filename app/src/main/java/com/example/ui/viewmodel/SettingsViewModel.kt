package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.credential.CredentialRegistry
import com.example.data.credential.CredentialState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    val credentialStates: StateFlow<List<CredentialState>> = CredentialRegistry.credentialStates

    private val _isTestingMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val isTestingMap: StateFlow<Map<String, Boolean>> = _isTestingMap.asStateFlow()

    private val _statusMessageMap = MutableStateFlow<Map<String, String>>(emptyMap())
    val statusMessageMap: StateFlow<Map<String, String>> = _statusMessageMap.asStateFlow()

    init {
        refresh(application)
    }

    fun refresh(context: Context = getApplication()) {
        viewModelScope.launch {
            CredentialRegistry.refreshAll(context)
        }
    }

    fun saveKey(keyName: String, newValue: String, context: Context = getApplication()) {
        viewModelScope.launch {
            CredentialRegistry.saveCredential(keyName, newValue, context)
            _statusMessageMap.value = _statusMessageMap.value + (keyName to "Saved to EncryptedSharedPreferences")
        }
    }

    fun testKey(keyName: String, context: Context = getApplication()) {
        viewModelScope.launch {
            _isTestingMap.value = _isTestingMap.value + (keyName to true)
            _statusMessageMap.value = _statusMessageMap.value + (keyName to "Testing connection...")
            try {
                // If input changed, save first then test
                CredentialRegistry.testSingleCredential(keyName, context)
            } finally {
                _isTestingMap.value = _isTestingMap.value + (keyName to false)
            }
        }
    }

    fun addCustomKey(keyName: String, value: String, context: Context = getApplication()) {
        viewModelScope.launch {
            val formatted = keyName.trim().uppercase().replace(" ", "_")
            if (formatted.isNotBlank()) {
                CredentialRegistry.addCustomKey(formatted, value, context)
                _statusMessageMap.value = _statusMessageMap.value + (formatted to "Custom Key $formatted Added")
            }
        }
    }

    fun deleteCustomKey(keyName: String, context: Context = getApplication()) {
        viewModelScope.launch {
            CredentialRegistry.deleteCustomKey(keyName, context)
        }
    }

    fun clearStatusMessage(keyName: String) {
        _statusMessageMap.value = _statusMessageMap.value - keyName
    }
}
