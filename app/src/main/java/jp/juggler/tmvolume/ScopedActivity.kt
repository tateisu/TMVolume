package jp.juggler.tmvolume

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

abstract class ScopedActivity : AppCompatActivity(), CoroutineScope {
    protected lateinit var activityJob: Job

    override val coroutineContext: CoroutineContext
        get() = activityJob + Dispatchers.Main

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityJob = Job()
    }

    override fun onDestroy() {
        super.onDestroy()
        (activityJob + Dispatchers.Default).cancel()
    }
}
