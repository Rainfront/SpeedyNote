package org.speedynote.app;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.qtproject.qt.android.bindings.QtActivity;

/**
 * Custom Activity for SpeedyNote.
 * 
 * Extends QtActivity to:
 * 1. Handle Activity results from the PDF file picker (BUG-A003)
 * 2. Handle Activity results from the .snbx package importer (Phase 2)
 * 3. Enable high-rate stylus input via requestUnbufferedDispatch() (BUG-A004)
 * 4. Provide system dark mode detection for theme synchronization (BUG-A007)
 * 5. Report stylus barrel button clicks to C++ (BUG-A010)
 * 
 * This is necessary because:
 * - PDF picker: We need to process the file picker result while SAF permission is valid
 * - Package importer: Same SAF handling for .snbx files
 * - Stylus input: Android batches touch events at 60Hz by default; we want 240Hz
 * - Dark mode: Qt doesn't automatically detect Android's system theme setting
 * - Barrel button: Qt turns the side button into no tablet event the app can act on
 */
public class SpeedyNoteActivity extends QtActivity {
    private static final String TAG = "SpeedyNoteActivity";
    
    // Singleton reference for JNI calls
    private static SpeedyNoteActivity sInstance;
    
    // Stylus eraser tool detection (BUG-A008: Hardware eraser not working)
    // Qt on Android doesn't properly translate Android's TOOL_TYPE_ERASER
    // to QPointingDevice::PointerType::Eraser, so we detect it here and
    // expose it via JNI for C++ to query.
    private static volatile boolean sEraserToolActive = false;
    
    // Stylus barrel button tracking (BUG-A010: side button does nothing)
    // Two families of pens reach this Activity differently:
    //
    // 1. Digitizer pens (Wacom AES / S Pen): button bits on MotionEvent, or
    //    KEYCODE_STYLUS_BUTTON_* (522-524) on API 33+.
    // 2. Lenovo Bluetooth pens (Pen Plus / Precision Pen on Idea Tab etc.):
    //    the side button is NOT a HID stylus button. Lenovo's framework
    //    delivers vendor KeyEvents 600-604 to the foreground Activity:
    //      600 = single press, 601 = double press, 602 = triple,
    //      603 = long press, 604 = long press + click.
    //    Detect on ACTION_UP only: 600/602/603/604 are UP-only, and 601
    //    fires both DOWN and UP (counting DOWN would toggle twice).
    private static final int STYLUS_BUTTON_MASK =
            MotionEvent.BUTTON_STYLUS_PRIMARY
            | MotionEvent.BUTTON_STYLUS_SECONDARY
            | MotionEvent.BUTTON_SECONDARY
            | MotionEvent.BUTTON_TERTIARY;
    
    // KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY/SECONDARY/TERTIARY (API 33+).
    private static final int KEYCODE_STYLUS_BUTTON_PRIMARY = 522;
    private static final int KEYCODE_STYLUS_BUTTON_TERTIARY = 524;
    
    // Lenovo BluetoothPenInputPolicy vendor keycodes (not in the public SDK).
    private static final int KEYCODE_LENOVO_PEN_SINGLE = 600;
    private static final int KEYCODE_LENOVO_PEN_DOUBLE = 601;
    private static final int KEYCODE_LENOVO_PEN_LONG_CLICK = 604;
    
    /** Cross-transport debounce so a pen that reports two channels only toggles once. */
    private static final long BUTTON_CLICK_DEBOUNCE_MS = 150;
    
    private static volatile boolean sStylusButtonDown = false;
    private static long sLastButtonClickMs = 0;
    private static boolean sButtonCallbackMissing = false;
    
    /** Called on the Android UI thread; C++ re-emits it on the Qt thread. */
    private static native void onStylusButtonClicked();
    
    // ===== Native Touch Tracking (for gesture reliability) =====
    // Qt's touch event layer can lose track of touch points after sleep/wake
    // or app switching. These values are tracked at the native Android level
    // and queried via JNI when Qt's touch count seems wrong.
    
    /** Current number of active touch points, tracked at the native Android level. */
    private static volatile int sNativeTouchCount = 0;
    
    /** Last time any touch event was received (epoch ms), for stale detection. */
    private static volatile long sLastTouchTimestamp = 0;
    
    /** Positions of active touch points (max 2 tracked): x1, y1, x2, y2. */
    private static volatile float[] sTouchPositions = new float[4];
    
    // Cached content view for unbuffered dispatch (performance optimization)
    // Avoids calling findViewById() on every touch event at 240Hz
    private View mCachedContentView = null;
    
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = this;
    }
    
    @Override
    protected void onDestroy() {
        if (sInstance == this) {
            sInstance = null;
        }
        super.onDestroy();
    }
    
    /**
     * Check if the system is in dark mode.
     * Called from C++ via JNI to sync Qt's palette with Android's system theme.
     * 
     * @return true if dark mode is enabled, false otherwise
     */
    public static boolean isDarkMode() {
        if (sInstance == null) {
            Log.w(TAG, "isDarkMode: Activity not available, defaulting to light mode");
            return false;
        }
        
        Configuration config = sInstance.getResources().getConfiguration();
        int nightMode = config.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean isDark = (nightMode == Configuration.UI_MODE_NIGHT_YES);
        Log.d(TAG, "isDarkMode: " + isDark + " (uiMode=" + config.uiMode + ")");
        return isDark;
    }
    
    /**
     * Check if the stylus eraser tool is currently active.
     * Called from C++ via JNI because Qt doesn't properly detect eraser tool type.
     * 
     * @return true if eraser tip is being used, false for pen tip or other input
     */
    public static boolean isEraserToolActive() {
        return sEraserToolActive;
    }
    
    /**
     * Update barrel button state from a stylus motion event, reporting the
     * press edge as a click.
     * 
     * Restricted to stylus tool types so a mouse's right or middle button,
     * which shares BUTTON_SECONDARY/BUTTON_TERTIARY with some pens, never
     * swaps the drawing tool.
     */
    private static void trackStylusButton(MotionEvent event) {
        int toolType = event.getToolType(0);
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS && toolType != MotionEvent.TOOL_TYPE_ERASER) {
            return;
        }
        
        // Prefer the discrete button-press action when the OEM sends it
        // (hover click). Fall back to button-state edges on move/touch.
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_BUTTON_PRESS) {
            int pressed = event.getActionButton();
            if ((pressed & STYLUS_BUTTON_MASK) != 0) {
                sStylusButtonDown = true;
                reportStylusButtonClick("motion-button-press");
                return;
            }
        } else if (action == MotionEvent.ACTION_BUTTON_RELEASE) {
            sStylusButtonDown = false;
            return;
        }
        
        boolean down = (event.getButtonState() & STYLUS_BUTTON_MASK) != 0;
        if (down && !sStylusButtonDown) {
            reportStylusButtonClick("motion-button-state");
        }
        sStylusButtonDown = down;
    }
    
    private static boolean isStylusButtonKeyCode(int keyCode) {
        return keyCode >= KEYCODE_STYLUS_BUTTON_PRIMARY && keyCode <= KEYCODE_STYLUS_BUTTON_TERTIARY;
    }
    
    /**
     * Lenovo vendor pen gestures that should toggle pen/eraser.
     * Single and double press cover the common mappings; long+click is rare
     * but harmless to treat the same way.
     */
    private static boolean isLenovoPenToggleKeyCode(int keyCode) {
        return keyCode == KEYCODE_LENOVO_PEN_SINGLE
                || keyCode == KEYCODE_LENOVO_PEN_DOUBLE
                || keyCode == KEYCODE_LENOVO_PEN_LONG_CLICK;
    }
    
    private static boolean isLenovoPenKeyCode(int keyCode) {
        return keyCode >= KEYCODE_LENOVO_PEN_SINGLE && keyCode <= KEYCODE_LENOVO_PEN_LONG_CLICK;
    }
    
    private static void reportStylusButtonClick() {
        reportStylusButtonClick("button");
    }
    
    private static void reportStylusButtonClick(String source) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastButtonClickMs < BUTTON_CLICK_DEBOUNCE_MS) {
            return;
        }
        sLastButtonClickMs = now;
        
        Log.i(TAG, "Stylus barrel button clicked (" + source + ")");
        
        if (sButtonCallbackMissing) {
            return;
        }
        try {
            onStylusButtonClicked();
        } catch (UnsatisfiedLinkError e) {
            // Give up permanently rather than throwing on every future click.
            sButtonCallbackMissing = true;
            Log.w(TAG, "Stylus button callback not available: " + e.getMessage());
        }
    }
    
    // ===== Native Touch Query Methods (called from C++ via JNI) =====
    
    /**
     * Get the current native touch count.
     * Called from C++ to verify Qt's touch state when gestures seem unreliable.
     * 
     * @return Number of fingers currently touching the screen (0-10)
     */
    public static int getNativeTouchCount() {
        return sNativeTouchCount;
    }
    
    /**
     * Get milliseconds since last touch event.
     * Used to detect if native touch state is stale.
     * 
     * @return Time in milliseconds since last touch event
     */
    public static long getTimeSinceLastTouch() {
        return System.currentTimeMillis() - sLastTouchTimestamp;
    }
    
    /**
     * Get native touch positions (x1, y1, x2, y2).
     * Returns positions of first 2 touch points for pinch gesture verification.
     * 
     * @return float array with [x1, y1, x2, y2] in screen coordinates
     */
    public static float[] getNativeTouchPositions() {
        return sTouchPositions;
    }
    
    /**
     * Intercept all touch events for:
     * 1. Requesting unbuffered dispatch (240Hz stylus input)
     * 2. Detecting eraser tool type (BUG-A008 fix)
     * 
     * On API 31+, this tells Android to deliver touch/stylus events at the
     * hardware's native rate (e.g., 240Hz) instead of batching them at 60Hz.
     * This results in smoother, more responsive drawing.
     * 
     * Performance: All operations here are O(1) with cached values.
     * - Content view is cached (avoids findViewById at 240Hz)
     * - Eraser check uses early-exit loop
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // Detect eraser tool type before Qt processes the event
        // Only check for stylus events (optimization: skip finger events)
        int toolType = event.getToolType(0);
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            sEraserToolActive = (toolType == MotionEvent.TOOL_TYPE_ERASER);
        }
        
        // Barrel button held while the tip is on the glass (BUG-A010)
        trackStylusButton(event);
        
        // ===== Track native touch count for gesture reliability =====
        // Qt's touch tracking can become corrupted after sleep/wake.
        // Track ground truth at native Android level for JNI verification.
        int action = event.getActionMasked();
        
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            sNativeTouchCount = event.getPointerCount();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            sNativeTouchCount = 0;
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            sNativeTouchCount = event.getPointerCount() - 1;
        } else if (action == MotionEvent.ACTION_MOVE) {
            sNativeTouchCount = event.getPointerCount();
        }
        
        sLastTouchTimestamp = System.currentTimeMillis();
        
        // Track positions of first 2 touch points (for pinch gesture verification)
        if (event.getPointerCount() >= 1) {
            sTouchPositions[0] = event.getX(0);
            sTouchPositions[1] = event.getY(0);
        }
        if (event.getPointerCount() >= 2) {
            sTouchPositions[2] = event.getX(1);
            sTouchPositions[3] = event.getY(1);
        }
        
        // Request unbuffered dispatch for high-rate stylus input (API 31+)
        // Use cached content view to avoid findViewById() overhead at 240Hz
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (mCachedContentView == null) {
                mCachedContentView = findViewById(android.R.id.content);
            }
            if (mCachedContentView != null) {
                mCachedContentView.requestUnbufferedDispatch(event);
            }
        }
        return super.dispatchTouchEvent(event);
    }
    
    /**
     * Catch barrel button clicks made while the pen hovers above the screen.
     * 
     * Hover and ACTION_BUTTON_PRESS events never reach dispatchTouchEvent(),
     * which is why a click in the air used to be lost entirely.
     */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        trackStylusButton(event);
        return super.dispatchGenericMotionEvent(event);
    }
    
    /**
     * Catch pens whose OEM stack reports the barrel button as a key event.
     *
     * Covers:
     * - KEYCODE_STYLUS_BUTTON_* (API 33+, digitizer pens)
     * - Lenovo vendor keycodes 600-604 (Bluetooth pen button gestures)
     *
     * Lenovo events are handled on ACTION_UP only (see field comment). Stylus
     * button keycodes keep the press-edge path used for hold-style pens.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        
        if (isLenovoPenKeyCode(keyCode)) {
            if (event.getAction() == KeyEvent.ACTION_UP && isLenovoPenToggleKeyCode(keyCode)) {
                String gesture = keyCode == KEYCODE_LENOVO_PEN_DOUBLE ? "lenovo-double"
                        : keyCode == KEYCODE_LENOVO_PEN_SINGLE ? "lenovo-single"
                        : "lenovo-" + keyCode;
                reportStylusButtonClick(gesture);
            } else {
                Log.d(TAG, "Lenovo pen key ignored: code=" + keyCode
                        + " action=" + event.getAction());
            }
            // Consume so Qt does not treat the vendor code as an unknown shortcut.
            return true;
        }
        
        if (isStylusButtonKeyCode(keyCode)) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                sStylusButtonDown = false;
            } else if (event.getRepeatCount() == 0 && !sStylusButtonDown) {
                sStylusButtonDown = true;
                reportStylusButtonClick("stylus-keycode-" + keyCode);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode);
        
        // First, let our PDF helper try to handle it
        if (PdfFileHelper.handleActivityResult(requestCode, resultCode, data)) {
            Log.d(TAG, "PdfFileHelper handled the result");
            return;
        }
        
        // Try the package import helper
        if (ImportHelper.handleActivityResult(requestCode, resultCode, data)) {
            Log.d(TAG, "ImportHelper handled the result");
            return;
        }
        
        // If not handled, pass to Qt's default handling
        super.onActivityResult(requestCode, resultCode, data);
    }
}

