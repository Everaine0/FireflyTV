package com.firefly.tv.core

/**
 * 自然排序：数字段按数值比较，第 2 集排在第 10 集前（DESIGN §4）。
 * 只比较字符串里的数字段，其余部分按不区分大小写的字典序。
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var i2 = i
                while (i2 < a.length && a[i2].isDigit()) i2++
                var j2 = j
                while (j2 < b.length && b[j2].isDigit()) j2++

                // 去掉前导零后比长度，再逐位比，避免大数字溢出
                val na = a.substring(i, i2).trimStart('0')
                val nb = b.substring(j, j2).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val cmp = na.compareTo(nb)
                if (cmp != 0) return cmp
                // 数值相同（如 "01" 与 "1"）时短的那个在前，保证稳定
                if (i2 - i != j2 - j) return (i2 - i) - (j2 - j)
                i = i2
                j = j2
            } else {
                val la = ca.lowercaseChar()
                val lb = cb.lowercaseChar()
                if (la != lb) return la - lb
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
