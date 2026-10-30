package com.pinepi.thinksmart.launcher

/** Main-thread state; retains one test message in memory, never logs its content. */
object HubTestState {
    var status = R.string.hub_pending
        private set
    var availableHubs: List<DiscoveredHub> = emptyList()
        private set
    fun candidates(value: List<DiscoveredHub>) {
        availableHubs = value
        onChanged?.invoke()
    }

    var latestText: String? = null
        private set
    var acceptedCount = 0
        private set
    var onChanged: (() -> Unit)? = null

    fun status(value: Int) {
        status = value
        onChanged?.invoke()
    }

    fun received(text: String) {
        latestText = text
        acceptedCount++
        onChanged?.invoke()
    }
}
