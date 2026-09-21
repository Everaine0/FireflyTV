package com.firefly.tv.smb

import com.firefly.tv.core.Config
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.Closeable
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB2/3 访问层。目标设备只有 1–2 条连接、2GB RAM，所以：
 *  - 一个实例持有一条 connection / session / share
 *  - 列目录只列一层，绝不递归
 *  - 所有调用由 [SmbStore] 串行化
 *
 * 配置通过构造参数注入（不是全局单例），这样配置页可以拿用户刚填的值先试连，不影响现行配置。
 */
class SmbClient(private val cfg: Config.Smb) {

    class Entry(val name: String, val isDir: Boolean, val size: Long)

    class Handle(val file: File, val size: Long) : Closeable {
        fun read(offset: Long, buf: ByteArray, off: Int, len: Int): Int = file.read(buf, offset, off, len)

        override fun close() {
            runCatching { file.close() }
        }
    }

    /**
     * ## ⚠️ 关于 `withSoTimeout`：**故意不设**（保持 smbj 默认的 0 = 不超时）
     *
     * 曾经设成 30 秒，理由是"别让一次读永远挂着"。但核对 smbj 0.15.0 的实现后确认
     * 它有反作用：socket 读超时会抛 `SocketTimeoutException`，而 `PacketReader` 把它
     * 当成**致命传输错误**处理（`TransportException` → `Connection.handleError` →
     * `close()`），也就是**把整条连接关掉**。
     *
     * 后果很具体：用户暂停一下、或者停在故障页/诊断页超过 30 秒（SMB 侧没有任何包），
     * 连接就被 smbj 自己关掉 —— 而播放器手里那个已经打开的 `File` 句柄跟着失效。
     * 这正好是「看一会儿突然说这个视频无法播放」的一类成因。
     *
     * 真正需要"别永远挂着"的是**每一次请求**，那由 `withTimeout(20s)` 保证
     * （smbj 对每个请求都做 `Futures.get(fut, timeout)`）。
     */
    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            // 老 NAS 可能只会 SMB1/2，允许降级协商
            .withMultiProtocolNegotiate(true)
            .build()
    )

    private val s = cfg.normalized()

    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    @Synchronized
    private fun share(): DiskShare {
        val conn = connection?.takeIf { it.isConnected }
            ?: client.connect(s.host).also {
                connection = it
                session = null
                share = null
            }
        val sess = session ?: conn.authenticate(authContext()).also {
            session = it
            share = null
        }
        return share ?: (sess.connectShare(s.share) as DiskShare).also { share = it }
    }

    private fun authContext(): AuthenticationContext =
        if (s.user.isBlank() && s.pass.isEmpty()) {
            AuthenticationContext.anonymous()
        } else {
            // 用户名写成 "域\用户" 时 smbj 会自行拆分域
            AuthenticationContext(s.user, s.pass.toCharArray(), s.domain.ifBlank { null })
        }

    /** 相对库根的路径 → 共享内路径（统一正斜杠）。 */
    fun path(relative: String): String {
        val rel = relative.trim('/')
        return when {
            s.root.isBlank() -> rel
            rel.isBlank() -> s.root
            else -> "${s.root}/$rel"
        }
    }

    /**
     * 这条连接还能用吗。
     *
     * 为什么需要：底层 socket 断了、NAS 重启了、或者别处把这个 share close 掉了以后，
     * 原来缓存的 share 会一直报 "DiskShare has already been closed"，
     * 之后**每一次**调用都失败 —— 表现就是「电视再也连不上 NAS，只能重启应用」。
     * 有了这个判断，[SmbStore] 就能把它丢掉重连。
     *
     * ## ⚠️ 这个判据在 smbj 0.15.0 上基本等于"没判"
     *
     * 老实现打算走反射取 `isStale()` 来问"这个 share 是不是已经废了"。
     * 核对了 0.15.0 的源码：**`Share`/`DiskShare` 上根本没有这个方法**
     * （它在更新的版本里才有），于是反射永远拿不到 → 只剩
     * `connection.isConnected`，而那个在 0.15.0 里是
     * `socket != null && socket.isConnected() && !socket.isClosed()` ——
     * **半死的连接（对端没了但本地 socket 还没关）照样返回 true**。
     *
     * 所以这里不再假装能判：只做"连接对象在不在"的廉价检查，
     * 真正的自愈交给 [SmbStore.with] 那层**按异常类型**判定的重连（见 `isSessionGone`），
     * 以及播放器那条路自己的重试。
     */
    @Synchronized
    fun isUsable(): Boolean = try {
        connection?.isConnected != false || share == null
    } catch (_: Throwable) {
        false
    }

    /** 只列一层。返回结果已剔除 . 与 ..。 */
    fun list(relative: String): List<Entry> {
        val p = path(relative)
        val raw = if (p.isBlank()) share().list("") else share().list(p)
        return raw.mapNotNull { it.toEntry() }
    }

    private fun FileIdBothDirectoryInformation.toEntry(): Entry? {
        val name = fileName ?: return null
        if (name == "." || name == "..") return null
        val isDir = (fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
        return Entry(name, isDir, if (isDir) 0L else endOfFile)
    }

    /** 打开文件用于随机读。[Handle.size] 取真实文件大小，续播与拖进度依赖它。 */
    fun open(relative: String): Handle {
        val file = share().openFile(
            path(relative),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        // FileAllocationInfo 是分配大小（可能大于真实大小），标准信息里的 endOfFile 才是真实大小
        val size = file.fileInformation.standardInformation.endOfFile
        return Handle(file, size)
    }

    /**
     * 只读开头若干字节，用来按内容判断文件类型。
     * 给「后缀名不可信」的片源用（例如实际是 MPEG-TS 却叫 .mp4）。
     * 读不到就返回空数组，调用方按「不认识」处理。
     */
    fun head(relative: String, count: Int): ByteArray {
        return try {
            open(relative).use { h ->
                val n = minOf(count.toLong(), h.size).toInt()
                if (n <= 0) return ByteArray(0)
                val buf = ByteArray(n)
                var got = 0
                while (got < n) {
                    val r = h.read(got.toLong(), buf, got, n - got)
                    if (r <= 0) break
                    got += r
                }
                if (got == n) buf else buf.copyOf(got)
            }
        } catch (_: Throwable) {
            ByteArray(0)
        }
    }

    /** 配置页「逐项实测」用：连得上 + 共享存在 + 根目录能列。 */
    fun probe(): String = try {
        val dirs = list("").count { it.isDir }
        "连接成功，根目录下发现 $dirs 个文件夹"
    } catch (t: Throwable) {
        throw SmbUnavailable(describe(t), t)
    }

    @Synchronized
    fun close() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        share = null
        session = null
        connection = null
    }

    companion object {
        /** 把底层异常翻译成老人看得懂的中文，不出现技术术语（DESIGN §8）。 */
        fun describe(t: Throwable): String {
            val m = (t.message ?: "").lowercase()
            val n = t.javaClass.simpleName.lowercase()
            return when {
                m.contains("logon failure") || m.contains("wrong password") || m.contains("logon_failure") ->
                    "账号或密码不正确"
                m.contains("access denied") || m.contains("access_denied") ->
                    "账号没有访问这个文件夹的权限"
                m.contains("bad network name") || m.contains("bad_network_name") ->
                    "找不到这个共享文件夹"
                m.contains("object name not found") || m.contains("object_name_not_found") ->
                    "找不到这个文件夹"
                t is java.net.UnknownHostException -> "找不到这个地址"
                t is java.net.SocketTimeoutException || m.contains("timed out") || m.contains("timeout") ->
                    "连接超时，检查地址和网络"
                t is java.net.ConnectException || m.contains("connection refused") ->
                    "无法连接，检查电视和 NAS 是否在同一网络"
                n.contains("smb") || m.contains("smb") -> "NAS 拒绝了这次连接"
                else -> "无法连接 NAS，请检查网络"
            }
        }
    }
}

/** 带上「给老人看的中文原因」的 SMB 异常。 */
class SmbUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 全进程唯一的 SMB 访问点：一条连接，全部操作串行。
 * ijkplayer 的读线程、扫描线程、配置页测试线程都共用它，避免连接数超限。
 */
object SmbStore {

    private val lock = Any()
    private var client: SmbClient? = null
    private var current: Config.Smb? = null

    /**
     * 用指定配置串行执行。
     *
     * 两条自愈规则，都是被真实故障逼出来的：
     *  1. 连接不可用（socket 断了 / NAS 重启了）→ 丢掉重连
     *  2. 操作因为**会话已关**失败 → 丢掉重连，**再试一次**
     *
     * 第 2 条是必须的：smbj 0.15.0 的 `DiskShare` 没有能查出「已经被关掉」的方法
     * （`isStale()` 在更新的版本里才有），所以光靠事前检查查不出来。
     * 没有重试的话，底层断过一次以后每次调用都会撞
     * "DiskShare has already been closed"，表现就是「电视再也连不上 NAS，只能重启应用」。
     */
    fun <T> with(cfg: Config.Smb, block: (SmbClient) -> T): T = synchronized(lock) {
        try {
            run(cfg, block)
        } catch (t: Throwable) {
            if (!isSessionGone(t)) throw t
            runCatching { client?.close() }
            client = null
            current = null
            run(cfg, block)
        }
    }

    private fun <T> run(cfg: Config.Smb, block: (SmbClient) -> T): T {
        val wanted = cfg.normalized()
        val cached = client
        val reusable = cached != null && current == wanted && cached.isUsable()
        if (cached != null && !reusable) runCatching { cached.close() }

        val live = if (reusable) cached!! else SmbClient(wanted)
        client = live
        current = wanted
        try {
            return block(live)
        } catch (t: Throwable) {
            // 会话可能是坏的，下次调用重建
            runCatching { live.close() }
            client = null
            current = null
            throw t
        }
    }

    /**
     * 这个异常是不是「底层会话已经没了」—— 是的话就丢掉重连、再试一次。
     *
     * ## 为什么不能只看异常消息（老实现就是那么写的，基本没生效）
     *
     * 老实现只匹配几个英文字符串（"already been closed" / "connection was closed" /
     * "broken pipe" / "socket closed"）。核对了 smbj 0.15.0 真正会抛的东西，
     * **最常见的几种一个都没被匹配到**：
     *
     * ```
     * TransportException("Cannot write <packet> as transport is disconnected")
     *     ← socket 刚死时抛的就是它，消息里没有 "closed"
     * SMBRuntimeException: ... Caused by: java.util.concurrent.TimeoutException
     * SMBApiException: STATUS_FILE_CLOSED / STATUS_USER_SESSION_DELETED /
     *                  STATUS_NETWORK_SESSION_EXPIRED
     * java.net.SocketException: Connection reset by peer
     * ```
     *
     * 于是注释里承诺的"自动重连一次"多数时候不会发生，异常直接冒到调用方 ——
     * 而调用方（扫描那条路）只会把它表现成"这次没扫到"，用户看到的是列表莫名其妙少东西。
     *
     * 现在改成**按类型/状态判**，不再靠英文措辞：
     *  - 断链类异常（`TransportException` / `SocketException` / `IOException`）直接算会话没了；
     *  - `SMBApiException` 看 `status`（`getStatus()`），命中"会话/文件已失效"那一族才算；
     *  - 原来的消息匹配留作兜底（有些异常类型拿不到，只有消息）。
     */
    private fun isSessionGone(t: Throwable): Boolean {
        var e: Throwable? = t
        var depth = 0
        while (e != null && depth++ < 8) {
            if (byType(e)) return true
            if (byMessage(e)) return true
            e = e.cause
        }
        return false
    }

    /** 按异常类型判（0.15.0 上真正会抛的那几种）。 */
    private fun byType(e: Throwable): Boolean {
        // 传输层/套接字层断了：这一类不用看消息，必然是会话没了
        val n = e.javaClass.name
        if (n.startsWith("com.hierynomus.protocol.transport.TransportException")) return true
        if (e is java.net.SocketException) return true
        if (e is java.io.InterruptedIOException) return true
        if (e is java.util.concurrent.TimeoutException) return true
        if (e is java.io.EOFException) return true

        // smbj 的 API 异常：只有"会话/文件已经失效"那一族才值得重连，
        // 其余（权限不足、路径不存在）重连也没用，应该原样报给用户。
        val status = runCatching {
            val m = e.javaClass.methods.firstOrNull { it.name == "getStatus" && it.parameterCount == 0 }
            m?.invoke(e)?.toString().orEmpty()
        }.getOrDefault("")
        return when {
            status.contains("FILE_CLOSED") -> true
            status.contains("USER_SESSION_DELETED") -> true
            status.contains("NETWORK_SESSION_EXPIRED") -> true
            status.contains("CONNECTION_DISCONNECTED") -> true
            status.contains("CONNECTION_RESET") -> true
            status.contains("INSUFFICIENT_RESOURCES") -> true
            else -> false
        }
    }

    /** 消息兜底：有些异常拿不到类型/状态，只能看措辞。 */
    private fun byMessage(e: Throwable): Boolean {
        val m = (e.message ?: "").lowercase()
        return m.contains("already been closed") ||
            m.contains("connection was closed") ||
            m.contains("transport") && m.contains("disconnect") ||
            m.contains("transport") && m.contains("closed") ||
            m.contains("broken pipe") ||
            m.contains("socket closed") ||
            m.contains("connection reset") ||
            m.contains("session expired") ||
            m.contains("session deleted")
    }

    /** 网络断了/NAS 重启后强制丢掉当前会话。 */
    fun drop() = synchronized(lock) {
        runCatching { client?.close() }
        client = null
        current = null
    }
}
