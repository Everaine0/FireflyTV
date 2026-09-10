package com.firefly.tv

import com.firefly.tv.smb.SmbClient

/**
 * `SmbClient` 没有实现 `Closeable`（只有 `close()`），所以用不了 Kotlin 的 `use`。
 * 补一个等价的小工具，免得每个测试都写一遍 try/finally。
 */
internal inline fun <T> SmbClient.use0(block: (SmbClient) -> T): T = try {
    block(this)
} finally {
    runCatching { close() }
}
