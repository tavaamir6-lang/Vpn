package com.example.data

import com.example.data.model.ServerConfig
import com.example.data.model.Subscription

/**
 * No built-in demo servers are shipped with the app.
 * Servers must come from the user's subscription or an explicit manual import.
 */
object SampleData {
    val defaultSubscriptions: List<Subscription> = emptyList()
    val defaultServers: List<ServerConfig> = emptyList()
}
