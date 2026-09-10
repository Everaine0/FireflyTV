# androidTest/assets — 测试视频

这两个 mp4 **不入库**（`.gitignore` 已排除），由脚本用 ffmpeg 生成：

```powershell
.\scripts\make-test-media.ps1
```

| 文件 | 用途 |
| :--- | :--- |
| `test.mp4` | `+faststart`，moov 在文件头。正常播放与跳读测试都用它 |
| `test_tail_moov.mp4` | 不带 faststart，moov 在文件尾。用来固化「已知限制」那条用例 |

两个文件内容一样，只有 moov 的位置不同 —— 这样对照实验才干净：
同一条 `IMediaDataSource` 通路，只因为 moov 位置不同，一个能播一个不能。
详见 `docs/DESIGN.md` 风险 8。
