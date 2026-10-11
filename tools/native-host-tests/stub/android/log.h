// Host stand-in for the NDK logging header so the AirPlay core can be built and tested on a desktop.
#pragma once
#include <stdarg.h>
#include <stdio.h>
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
static inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    (void)prio;
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "[%s] ", tag);
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    va_end(ap);
    return 0;
}
