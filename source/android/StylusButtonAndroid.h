#pragma once

#include <QObject>

/**
 * Stylus barrel button bridge (BUG-A010).
 *
 * Android reports the side button of a Wacom AES pen (Lenovo Precision Pen 2)
 * or an S Pen as a button bit on motion events, or as a stylus key code on some
 * OEM pen stacks. Qt's Android plugin turns none of that into a tablet event
 * the app can act on, so SpeedyNoteActivity.java detects the click and calls
 * into this bridge.
 *
 * The click arrives on the Android UI thread; clicked() is emitted on the GUI
 * thread so listeners can touch widgets.
 */
class StylusButtonAndroid : public QObject
{
    Q_OBJECT

public:
    /**
     * Returns the bridge, creating it on first call.
     *
     * Must be called from the GUI thread, which also publishes the bridge to
     * the JNI callback: clicks arriving before then have no listener and are
     * dropped.
     */
    static StylusButtonAndroid* instance();

    /// Queues clicked() onto the GUI thread. Safe to call from any thread.
    void reportClick();

signals:
    /// A barrel button click, already debounced on the Java side.
    void clicked();

private:
    explicit StylusButtonAndroid(QObject* parent = nullptr);
};
