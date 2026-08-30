package com.wirelessdebug.service;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * 免 Root 无障碍控制通道：系统无障碍服务授予读屏（节点树）与手势注入能力，
 * 全程不截图、无 OCR。宿主无需 Root / 无线调试 / Shizuku 即可读屏并点击、滑动、输入。
 * 与无线调试通道二选一使用（设备控制优先级：Root → 无线调试 → Shizuku → 无障碍）。
 */
public class AccessibilityControlService extends AccessibilityService {

    private static volatile AccessibilityControlService instance;

    /** 当前已连接的服务实例（系统绑定后非 null）。 */
    public static AccessibilityControlService instance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 读屏为按需拉取节点树，无需缓存事件
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
