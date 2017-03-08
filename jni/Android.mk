LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)


LOCAL_SRC_FILES := rawrender.c \
					colorConvert.c 


LOCAL_C_INCLUDES :=$(LOCAL_PATH)/


LOCAL_MODULE := viautilty

LOCAL_LDLIBS := -llog -ljnigraphics -landroid

include $(BUILD_SHARED_LIBRARY)
