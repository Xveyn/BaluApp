package com.baluhost.android.domain.repository

import com.baluhost.android.domain.model.AlwaysAwake
import com.baluhost.android.util.Result
import java.time.Instant

interface SleepConfigRepository {

    suspend fun getAlwaysAwake(): Result<AlwaysAwake>

    /**
     * @param until null means permanent. Ignored when [enabled] is false — the
     *   server clears the expiry itself in that case.
     */
    suspend fun setAlwaysAwake(enabled: Boolean, until: Instant?): Result<AlwaysAwake>
}
