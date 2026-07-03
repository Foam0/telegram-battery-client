/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2015-2018.
 */

#include "ProxyCheckInfo.h"
#include "ConnectionsManager.h"
#include "FileLog.h"

#ifdef ANDROID
static JNIEnv *getCurrentJNIEnv(bool *attached) {
    JNIEnv *env = nullptr;
    *attached = false;
    if (javaVm == nullptr) {
        return nullptr;
    }
    if (javaVm->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }
    if (javaVm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        *attached = true;
        return env;
    }
    return nullptr;
}
#endif

ProxyCheckInfo::~ProxyCheckInfo() {
#ifdef ANDROID
    if (ptr1 != nullptr) {
        bool attached = false;
        JNIEnv *env = getCurrentJNIEnv(&attached);
        DEBUG_DELREF("tgnet (2) request ptr1");
        if (env != nullptr) {
            env->DeleteGlobalRef(ptr1);
        }
        ptr1 = nullptr;
        if (attached) {
            javaVm->DetachCurrentThread();
        }
    }
#endif
}
