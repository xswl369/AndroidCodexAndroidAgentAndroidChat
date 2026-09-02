
#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <pthread.h>
#include <android/log.h>
#include <Python.h>

#define TAG "PyEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static volatile int g_ready = 0;
static char g_fatal[512] = {0};
static PyObject *g_sys_backup[2] = {NULL, NULL};

static void init_python(const char *home) {
    PyConfig cfg;
    PyConfig_InitIsolatedConfig(&cfg);
    PyStatus r1 = PyConfig_SetBytesString(&cfg, &cfg.home, home);
    PyStatus r2 = PyConfig_SetBytesString(&cfg, &cfg.prefix, home);
    PyStatus r3 = PyConfig_SetBytesString(&cfg, &cfg.program_name, "python3.14");
    if (PyStatus_Exception(r1) || PyStatus_Exception(r2) || PyStatus_Exception(r3)) {
        PyConfig_Clear(&cfg);
        snprintf(g_fatal, sizeof(g_fatal), "init: set config failed");
        g_ready = 0;
        return;
    }
    cfg.parse_argv = 0;
    cfg.install_signal_handlers = 0;
    cfg.site_import = 0;
    cfg.use_environment = 0;
    PyStatus st = Py_InitializeFromConfig(&cfg);
    PyConfig_Clear(&cfg);
    if (PyStatus_Exception(st)) {
        snprintf(g_fatal, sizeof(g_fatal), "python init: %s", st.err_msg ? st.err_msg : "unknown");
        g_ready = 0;
        return;
    }
    PyObject *sys = PyImport_ImportModule("sys");
    if (sys) {
        g_sys_backup[0] = PyObject_GetAttrString(sys, "stdout");
        g_sys_backup[1] = PyObject_GetAttrString(sys, "stderr");
        Py_DECREF(sys);
    }
    g_ready = 1;
}

static int make_args(PyObject *sys_mod, const char *args) {
    PyObject *list = PyList_New(0);
    if (!list) return -1;
    PyObject *s0 = PyUnicode_DecodeUTF8("inline.py", 9, "strict");
    if (!s0) { Py_DECREF(list); return -1; }
    PyList_Append(list, s0); Py_DECREF(s0);
    if (args != NULL && args[0] != '\0') {
        PyObject *a = PyUnicode_DecodeUTF8(args, (Py_ssize_t)strlen(args), "strict");
        if (a) { PyList_Append(list, a); Py_DECREF(a); }
    }
    int rc = PyObject_SetAttrString(sys_mod, "argv", list);
    Py_DECREF(list);
    return rc;
}

static jbyteArray make_ret(JNIEnv *env, const char *out, const char *err) {
    size_t no = out ? strlen(out) : 0;
    size_t ne = err ? strlen(err) : 0;
    size_t cap = no + ne + 1; /* out + 1 sep + err(可空) */
    char *buf = (char *)malloc(cap);
    if (buf) memset(buf, 0, cap);
    if (!buf) {
        buf = (char *)malloc(9);
        memcpy(buf, "o-o-m", 5); buf[5] = 0x1e; buf[6] = 0; buf[7] = 0; cap = 8;
    }
    memcpy(buf, out ? out : "", no);
    buf[no] = 0x1e;
    memcpy(buf + no + 1, err ? err : "", ne);
    jbyteArray jb = (*env)->NewByteArray(env, (jsize)cap);
    if (jb) (*env)->SetByteArrayRegion(env, jb, 0, (jsize)cap, (const jbyte *)buf);
    free(buf);
    return jb;
}

JNIEXPORT jbyteArray JNICALL
Java_com_xs_chat_plugins_PyEngine_nativeRun(JNIEnv *env, jobject thiz,
                                      jstring jHome, jstring jNativeDir,
                                      jstring jCode, jstring jArgs) {
    const char *home   = jHome      ? (*env)->GetStringUTFChars(env, jHome, NULL) : NULL;
    const char *ndir   = jNativeDir ? (*env)->GetStringUTFChars(env, jNativeDir, NULL) : NULL;
    const char *code   = jCode      ? (*env)->GetStringUTFChars(env, jCode, NULL) : "";
    const char *args   = jArgs      ? (*env)->GetStringUTFChars(env, jArgs, NULL) : "";
    jbyteArray ret = NULL;

    pthread_mutex_lock(&g_lock);
    if (!g_ready) {
        if (home) init_python(home);
    }
    if (!g_ready) {
        ret = make_ret(env, "", g_fatal[0] ? g_fatal : "python not initialized");
        goto out;
    }
    {
        PyGILState_STATE gil = PyGILState_Ensure();
        PyObject *sys = PyImport_ImportModule("sys");
        PyObject *io = NULL;
        PyObject *outbuf = NULL, *errbuf = NULL;
        PyObject *old_out = NULL, *old_err = NULL;
        PyObject *path = NULL, *nat = NULL, *rc_obj = NULL;
        PyObject *bout = NULL, *berr = NULL;
        const char *res_out = "", *res_err = "";
        if (!sys) { PyErr_Print(); goto py_clean; }

        old_out = PyObject_GetAttrString(sys, "stdout");
        old_err = PyObject_GetAttrString(sys, "stderr");
        io = PyImport_ImportModule("io");

        make_args(sys, args);

        if (ndir) {
            path = PyObject_GetAttrString(sys, "path");
            nat = PyUnicode_DecodeUTF8(ndir, (Py_ssize_t)strlen(ndir), "strict");
            if (path && nat) {
                int found = PySequence_Contains(path, nat);
                if (found == 0) PyList_Insert(path, 0, nat);
            }
        }
        if (io) {
            outbuf = PyObject_CallMethod(io, "StringIO", NULL);
            errbuf = PyObject_CallMethod(io, "StringIO", NULL);
            if (outbuf) PyObject_SetAttrString(sys, "stdout", outbuf);
            if (errbuf) PyObject_SetAttrString(sys, "stderr", errbuf);
        }

        PyObject *gdict = PyImport_GetModuleDict();
        rc_obj = PyRun_StringFlags(code, Py_file_input, gdict, gdict, NULL);
        if (!rc_obj) PyErr_Print();

        if (outbuf) {
            bout = PyObject_CallMethod(outbuf, "getvalue", NULL);
            if (bout && PyUnicode_Check(bout)) res_out = PyUnicode_AsUTF8(bout);
        }
        if (errbuf) {
            berr = PyObject_CallMethod(errbuf, "getvalue", NULL);
            if (berr && PyUnicode_Check(berr)) res_err = PyUnicode_AsUTF8(berr);
        }
        ret = make_ret(env, res_out, res_err);

    py_clean:
        if (sys) {
            if (old_out) PyObject_SetAttrString(sys, "stdout", old_out);
            if (old_err) PyObject_SetAttrString(sys, "stderr", old_err);
        }
        Py_XDECREF(bout); Py_XDECREF(berr);
        Py_XDECREF(rc_obj);
        Py_XDECREF(path); Py_XDECREF(nat);
        Py_XDECREF(outbuf); Py_XDECREF(errbuf);
        Py_XDECREF(old_out); Py_XDECREF(old_err);
        Py_XDECREF(sys); Py_XDECREF(io);
        PyGILState_Release(gil);
    }
out:
    pthread_mutex_unlock(&g_lock);
    if (home)      (*env)->ReleaseStringUTFChars(env, jHome, home);
    if (ndir)      (*env)->ReleaseStringUTFChars(env, jNativeDir, ndir);
    if (jCode)     (*env)->ReleaseStringUTFChars(env, jCode, code);
    if (jArgs)     (*env)->ReleaseStringUTFChars(env, jArgs, args);
    return ret;
}

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    return JNI_VERSION_1_6;
}
