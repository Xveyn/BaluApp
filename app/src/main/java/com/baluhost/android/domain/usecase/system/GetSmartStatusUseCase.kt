package com.baluhost.android.domain.usecase.system

import com.baluhost.android.data.remote.api.SystemApi
import com.baluhost.android.domain.model.SmartAttribute
import com.baluhost.android.domain.model.SmartDeviceInfo
import com.baluhost.android.domain.model.SmartSelfTest
import com.baluhost.android.domain.model.SmartStatusInfo
import com.baluhost.android.util.Result
import javax.inject.Inject

class GetSmartStatusUseCase @Inject constructor(
    private val systemApi: SystemApi
) {
    suspend operator fun invoke(): Result<SmartStatusInfo> {
        return try {
            val response = systemApi.getSmartStatus()

            val devices = response.devices.map { dto ->
                SmartDeviceInfo(
                    name = dto.name,
                    model = dto.model,
                    serial = dto.serial,
                    temperature = dto.temperature,
                    status = dto.status,
                    capacityBytes = dto.capacityBytes,
                    usedBytes = dto.usedBytes,
                    usedPercent = dto.usedPercent?.toDouble(),
                    mountPoint = dto.mountPoint,
                    raidMemberOf = dto.raidMemberOf,
                    lastSelfTest = dto.lastSelfTest?.let {
                        SmartSelfTest(
                            testType = it.testType,
                            status = it.status,
                            passed = it.passed,
                            powerOnHours = it.powerOnHours
                        )
                    },
                    attributes = (dto.attributes ?: emptyList()).map { attr ->
                        SmartAttribute(
                            id = attr.id,
                            name = attr.name,
                            value = attr.value,
                            worst = attr.worst,
                            threshold = attr.threshold,
                            raw = attr.raw,
                            status = attr.status
                        )
                    }
                )
            }

            Result.Success(
                SmartStatusInfo(
                    checkedAt = response.checkedAt,
                    devices = devices
                )
            )
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
