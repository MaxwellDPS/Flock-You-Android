# Flock You - Android.mk for legacy AOSP build systems
#
# This file provides compatibility with older make-based build systems.
# For Soong (Android.bp) based builds, use Android.bp instead.
#
# Usage:
#   1. Copy this directory to vendor/flockyou/ or packages/apps/FlockYou/
#   2. Build the APK: ./gradlew assembleOemRelease (or assembleSystemRelease)
#   3. Copy the APK to this directory as FlockYou.apk
#   4. Add "FlockYou" to PRODUCT_PACKAGES in your device.mk

LOCAL_PATH := $(call my-dir)

#
# FlockYou privileged system app
#
include $(CLEAR_VARS)

LOCAL_MODULE := FlockYou
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_TAGS := optional

# Install as privileged app
LOCAL_PRIVILEGED_MODULE := true

# For Android 11+, install to system_ext partition
LOCAL_SYSTEM_EXT_MODULE := true

# Signing configuration:
# - Use "platform" for OEM builds (maximum privileges)
# - Use "PRESIGNED" for pre-signed APKs (System mode)
LOCAL_CERTIFICATE := platform

# APK source
LOCAL_SRC_FILES := FlockYou.apk
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)

# DEX optimization
LOCAL_DEX_PREOPT := true

# Required modules
LOCAL_REQUIRED_MODULES := \
    privapp-permissions-flockyou.xml \
    default-permissions-flockyou.xml

# Replace any existing installation
LOCAL_OVERRIDES_PACKAGES := FlockYou

include $(BUILD_PREBUILT)

#
# Permission whitelist configuration
#
include $(CLEAR_VARS)

LOCAL_MODULE := privapp-permissions-flockyou.xml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional

# Install to system_ext/etc/permissions/ for Android 11+
LOCAL_SYSTEM_EXT_MODULE := true
LOCAL_MODULE_PATH := $(TARGET_OUT_SYSTEM_EXT_ETC)/permissions

LOCAL_SRC_FILES := privapp-permissions-flockyou.xml

include $(BUILD_PREBUILT)

#
# Default runtime permissions (pre-granted on first boot)
#
include $(CLEAR_VARS)

LOCAL_MODULE := default-permissions-flockyou.xml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional

# Install to system_ext/etc/default-permissions/ for Android 11+
LOCAL_SYSTEM_EXT_MODULE := true
LOCAL_MODULE_PATH := $(TARGET_OUT_SYSTEM_EXT_ETC)/default-permissions

LOCAL_SRC_FILES := default-permissions-flockyou.xml

include $(BUILD_PREBUILT)

#
# Alternative: Legacy installation for Android 10 and earlier
# Uncomment the section below and comment out LOCAL_SYSTEM_EXT_MODULE lines above
#
# For Android 10 and earlier, change:
#   LOCAL_SYSTEM_EXT_MODULE := true  ->  (remove this line)
#   LOCAL_MODULE_PATH := $(TARGET_OUT_SYSTEM_EXT_ETC)/permissions  ->  LOCAL_MODULE_PATH := $(TARGET_OUT_ETC)/permissions
#
