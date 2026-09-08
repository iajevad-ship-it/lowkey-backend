package com.lowkey.backend.services

import com.lowkey.backend.routes.HealthResponse

class HealthService {
    fun check(): HealthResponse = HealthResponse(status = "ok")
}
