package dev.argus.shizuku

internal class FakeShizukuApi(
    var installed: Boolean = false,
    var alive: Boolean = false,
    var old: Boolean = false,
    var granted: Boolean = false,
    var rationale: Boolean = false,
) : ShizukuApi {
    private val binderListeners = mutableSetOf<() -> Unit>()
    private val deadListeners = mutableSetOf<() -> Unit>()
    private val permissionListeners = mutableSetOf<(Int, Int) -> Unit>()
    var lastRequestCode: Int = 0
    var requestCount: Int = 0
    val permissionListenerCount: Int get() = permissionListeners.size

    override fun managerInstalled() = installed
    override fun binderAlive() = alive
    override fun preV11() = old
    override fun permissionGranted() = granted
    override fun shouldShowPermissionRationale() = rationale
    override fun requestPermission(requestCode: Int) {
        lastRequestCode = requestCode
        requestCount++
    }
    override fun addBinderReceivedListener(listener: () -> Unit) { binderListeners += listener }
    override fun removeBinderReceivedListener(listener: () -> Unit) { binderListeners -= listener }
    override fun addBinderDeadListener(listener: () -> Unit) { deadListeners += listener }
    override fun removeBinderDeadListener(listener: () -> Unit) { deadListeners -= listener }
    override fun addPermissionResultListener(listener: (Int, Int) -> Unit) {
        permissionListeners += listener
    }
    override fun removePermissionResultListener(listener: (Int, Int) -> Unit) {
        permissionListeners -= listener
    }

    fun deliverPermission(requestCode: Int, result: Int) {
        permissionListeners.toList().forEach { it(requestCode, result) }
    }

    fun deliverBinderReceived() {
        binderListeners.toList().forEach { it() }
    }

    fun deliverBinderDead() {
        deadListeners.toList().forEach { it() }
    }
}
