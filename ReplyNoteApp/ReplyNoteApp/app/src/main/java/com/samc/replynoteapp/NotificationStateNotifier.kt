package com.samc.replynoteapp

object NotificationStateNotifier {
    private val listeners = mutableSetOf<() -> Unit>()

    fun register(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun unregister(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun notifyChanged() {
        listeners.toList().forEach { it.invoke() }
    }
}
