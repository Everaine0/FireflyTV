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

    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
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
     * 判据走反射取 `isStale()`：smbj 0.15.0 的 `DiskShare` 里没有这个方法
     * （它在更新的版本里才有），直接调会编译不过。拿不到就只信连接的存活状态。
     */
    @Synchronized
    fun isUsable(): Boolean {
        val sh = share ?: return true // 还没连过，不算坏
        return try {
            if (connection?.isConnected != true) return false
            val m = sh.javaClass.methods.firstOrNull { it.name == "isStale" && it.parameterCount == 0 }
            val stale = m?.invoke(sh) as? Boolean ?: false
            !stale
        } catch (_: Throwable) {
            false
        }
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

    /** 这个异常是不是「底层会话已经没了」。 */
    private fun isSessionGone(t: Throwable): Boolean {
        var e: Throwable? = t
        var depth = 0
        while (e != null && depth++ < 8) {
            val m = (e.message ?: "").lowercase()
            if (m.contains("already been closed") ||
                m.contains("connection was closed") ||
                m.contains("transport") && m.contains("closed") ||
                m.contains("broken pipe") ||
                m.contains("socket closed")
            ) {
                return true
            }
            e = e.cause
        }
        return false
    }

    /** 网络断了/NAS 重启后强制丢掉当前会话。 */
    fun drop() = synchronized(lock) {
        runCatching { client?.close() }
        client = null
        current = null
    }
}
