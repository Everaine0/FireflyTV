# androidTest/assets — 测试媒体

这里的媒体文件**都不入库**（`.gitignore` 已排除），用两条脚本生成：

```powershell
.\scripts\make-test-media.ps1       # moov 位置对照 + 视频编码对照
.\scripts\make-audio-fixtures.ps1   # 音频编码对照
```

## moov 位置对照（`make-test-media.ps1`）

| 文件 | 用途 |
| :--- | :--- |
| `test.mp4` | `+faststart`，moov 在文件头。正常播放与跳读测试都用它 |
| `test_tail_moov.mp4` | 不带 faststart，moov 在文件尾，用来验证 `MoovRelocatingSource` |

两个文件内容一样，只有 moov 的位置不同 —— 这样对照实验才干净：
同一条 `IMediaDataSource` 通路，只因为 moov 位置不同，一个能播一个不能。
详见 `docs/DESIGN.md` 风险 8。

> ⚠️ 这两个样本**偏小（约 125 KB）**，`stco` 里的 chunk 偏移也很小。
> 所以它们**抓不到**「搬 moov 却没改 stco 偏移」那个 bug ——
> 偏移太小，改不改都"看起来对"。真实片源才有 245 MB 量级的 mdat。
> 那条 bug 是靠真实 NAS 上的 `EveryShowPlaysTest` 抓到的。

## 音频编码对照（`make-audio-fixtures.ps1`）

用来把「内核缺某个解码器」和「这条流有别的问题」彻底分开。
样本**只换音频编码**，所以差异只可能来自音频：

| 文件 | 音频编码 | 容器 |
| :--- | :--- | :--- |
| `audio-aac.ts` | AAC | MPEG-TS |
| `audio-ac3.ts` | AC-3 | MPEG-TS |
| `audio-eac3.ts` | E-AC-3 | MPEG-TS |
| `audio-mp2.ts` | MP2 | MPEG-TS |
| `audio-aac.mp4` | AAC | MP4 |
| `audio-ac3.mp4` | AC-3 | MP4 |
| `audio-aac2.mp4` | AAC | MP4（不同视频编码与采样率） |

消费者是 `AudioCodecMatrixTest`，判据是 ijkplayer 的
`MEDIA_INFO_AUDIO_RENDERING_START` 回调（音频真的开始往 AudioTrack 送数据时才触发）。

> **已知特例**：`audio-aac.mp4` 在模拟器上可复现地不出声
> （`onPrepared` 之后立刻 `onCompletion`），而同批另两个 mp4 正常，
> 差别在视频编码（它是唯一 H.264 的）。同类组合在真实片源上没问题
> （CCTV1 就是 H.264+AAC）。测试里如实标注、不判红，详见该测试的
> `knownFlakyOnEmulator`。

## 视频编码对照（`make-test-media.ps1`）

| 文件 | 视频编码 |
| :--- | :--- |
| `video-h264.mp4` | H.264 |
| `video-hevc.mp4` | H.265 |

同内容、同容器、同分辨率、都 faststart、都走内存字节源，
**只差视频编码** —— 用来回答「模拟器能不能解 H.265」这类问题，
把变量清干净。消费者是 `VideoCodecMatrixTest`。

> 做这套对照的背景：真实 NAS 上出问题的两部剧都是 H.265，
> 第一反应会怀疑「模拟器解不了 H.265」。实测两份都能出首帧，
> 从而把嫌疑排除掉，逼着去找真正的原因（moov 重排没改 stco 偏移）。
