package com.iamxpp.isaver.domain

sealed interface RootStatus {
    data object Available : RootStatus

    data class Unavailable(val reason: String) : RootStatus
}
