## 1.0.4-beta3 - 2026-11-04

### 中文

- 针对 MiDuo 等桌面侧栏增加回收站确认兼容：确认框打开期间临时隐藏普通悬浮窗，完成或取消后自动恢复。
- 覆盖确认失败、取消和 Activity 销毁等异常路径，避免应用窗口一直保留隐藏悬浮窗标志。

### English

- Improve trash-confirmation compatibility with MiDuo and similar desktop sidebars by temporarily hiding ordinary overlay windows while the system prompt is open.
- Restore the overlay state after success, cancellation, launch failure, or activity destruction.

## 1.0.4-beta2 - 2026-11-04

### 中文

- 打开系统回收站确认框前，取消应用自身的浮动提示，减少对「允许」按钮触摸的潜在干扰。
- 在设置和使用说明中补充排查方法：「允许」无法点击时，先关闭侧边栏、悬浮球等悬浮窗后重试；也可开启「删除时免确认」。
- 此版本为测试版，仍需在反馈问题的设备上验证；应用无法直接修改系统确认框的触摸处理。

### English

- Cancel the app's toast before opening the system trash confirmation to reduce potential interference with the Allow button.
- Add troubleshooting guidance in settings and the README: close sidebars and other floating overlays before retrying, or enable media management to remove screenshots without confirmation.
- This beta still needs verification on the affected device; the app cannot directly change touch handling in the system confirmation.
