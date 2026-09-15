#include "StylusButtonAndroid.h"

#ifdef Q_OS_ANDROID

#include <QAtomicPointer>
#include <QCoreApplication>
#include <jni.h>

namespace {
    // Published by instance() on the GUI thread, read by the JNI callback on the
    // Android UI thread; atomic because those are different threads.
    QAtomicPointer<StylusButtonAndroid> s_bridge = nullptr;
}

StylusButtonAndroid::StylusButtonAndroid(QObject* parent)
    : QObject(parent)
{
}

StylusButtonAndroid* StylusButtonAndroid::instance()
{
    StylusButtonAndroid* bridge = s_bridge.loadAcquire();
    if (!bridge) {
        bridge = new StylusButtonAndroid(qApp);
        s_bridge.storeRelease(bridge);
    }
    return bridge;
}

void StylusButtonAndroid::reportClick()
{
    QMetaObject::invokeMethod(this, [this]() { emit clicked(); }, Qt::QueuedConnection);
}

// JNI callback: called from SpeedyNoteActivity.java when the pen's side button
// is clicked. Runs on the Android UI thread.
extern "C" JNIEXPORT void JNICALL
Java_org_speedynote_app_SpeedyNoteActivity_onStylusButtonClicked(JNIEnv* /*env*/, jclass /*clazz*/)
{
    if (StylusButtonAndroid* bridge = s_bridge.loadAcquire()) {
        bridge->reportClick();
    }
}

#endif // Q_OS_ANDROID
