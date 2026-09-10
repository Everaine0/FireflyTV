# androidTest/assets — 测试视频

这里的媒体文件**都不入库**（`.gitignore` 已排除），用两条脚本生成：

```powershell
.\scripts\make-test-media.ps1       # moov 位置对照用的 mp4
.\scripts\make-audio-fixtures.ps1   # 音频编码对照用的样本
```

## moov 位置对照（`make-test-media.ps1`）

| 文件 | 用途 |
| :--- | :--- |
| `test.mp4` | `+faststart`，moov 在文件头。正常播放与跳读测试都用它 |
| `test_tail_moov.mp4` | 不带 faststart，moov 在文件尾。用来固化「已知限制」那条用例 |

两个文件内容一样，只有 moov 的位置不同 —— 这样对照实验才干净：
同一条 `IMediaDataSource` 通路，只因为 moov 位置不同，一个能播一个不能。
详见 `docs/DESIGN.md` 风险 8。

## 音频编码对照（`make-audio-fixtures.ps1`）

用来把「内核缺某个解码器」和「这条流有别的问题」彻底分开。
六份样本**视频完全相同，只有音频编码不同**，所以差异只可能来自音频：

| 文件 | 音频编码 | 容器 |
| :--- | :--- | :--- |
| `audio-aac.ts` | AAC | MPEG-TS |
| `audio-ac3.ts` | AC-3 | MPEG-TS |
| `audio-eac3.ts` | E-AC-3 | MPEG-TS |
| `audio-mp2.ts` | MP2 | MPEG-TS |
| `audio-aac.mp4` | AAC | MP4 |
| `audio-ac3.mp4` | AC-3 | MP4 |

视频刻意压到 320×240@15fps（MPEG-2 编码），保证模拟器也能实时软解 ——
否则「没声音」会被误判成「画面解不动」。

消费者是 `AudioCodecMatrixTest`，判据是 ijkplayer 的
`MEDIA_INFO_AUDIO_RENDERING_START` 回调（音频真的开始往 AudioTrack 送数据时才触发）。

> 这套对照是排查《娘道》没声音时做出来的，结论见 `docs/DESIGN.md`
> 「格式兼容性」一节。**它证明内核能播 AC-3，从而把嫌疑从「解码器能力」
> 推到了「流参数探测」——那才是真正的根因。**
