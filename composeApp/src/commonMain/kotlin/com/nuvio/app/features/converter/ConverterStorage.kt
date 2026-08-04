package com.nuvio.app.features.converter

internal expect object ConverterStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
