package com.tingshu.app.ui

/**
 * 极简返回栈。
 *
 * 之前界面只存「上一跳」（previousRoute），从播放器返回书籍后这个变量仍然指向
 * book，于是再按返回键会一直停在书籍页。用真正的栈才能保证连续返回一路退回书架。
 * 这里只放纯逻辑，状态由 Compose 的 rememberSaveable 保存，方便单测覆盖。
 */
class NavStack(routes: List<String> = listOf(ROUTE_LIBRARY)) {

    private val stack = ArrayList<String>().apply {
        addAll(routes.filter { it.isNotBlank() }.ifEmpty { listOf(ROUTE_LIBRARY) })
    }

    val current: String get() = stack.last()

    /** 同一路由不重复入栈：反复点迷你播放器不该把栈撑爆。 */
    fun go(route: String) {
        if (route.isBlank() || route == current) return
        stack.add(route)
    }

    /** 出栈；已经在书架时保持不变，由外层 BackHandler 决定是否退出应用。 */
    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** 当前书籍被删掉等场景：清掉整条历史，直接回书架。 */
    fun reset() {
        stack.clear()
        stack.add(ROUTE_LIBRARY)
    }

    fun snapshot(): List<String> = stack.toList()

    companion object {
        const val ROUTE_LIBRARY = "library"
    }
}
